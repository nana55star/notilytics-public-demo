package controllers;

import actors.search.SearchParentActor;
import actors.search.SearchParentActor.SearchPreparation;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Flow;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.apache.pekko.util.ByteString;
import play.inject.ApplicationLifecycle;
import play.libs.F;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Http.RequestBuilder;
import play.mvc.Result;
import play.mvc.Results;
import play.mvc.WebSocket;
import services.NewsService;
import services.ReadabilityService;
import services.SentimentService;
import services.SourceProfileService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import scala.jdk.javaapi.FutureConverters;

/**
 * Controller responsible for handling all user-facing search-related actions
 * in the NotiLytics web application. All asynchronous work is offloaded to
 * {@link SearchParentActor} and its child actors.
 */
@Singleton
public class SearchController extends Controller {

    private final ActorSystem<SearchParentActor.Command> searchActorSystem;
    private final ActorRef<SearchParentActor.Command> searchActor;
    private final Scheduler scheduler;
    private final Duration askTimeout;
    private final Materializer materializer;

    @Inject
    public SearchController(NewsService newsService,
                            SentimentService sentimentService,
                            SourceProfileService sourceProfileService,
                            ReadabilityService readabilityService,
                            ApplicationLifecycle lifecycle) {
        Behavior<SearchParentActor.Command> parentBehavior =
                Behaviors.supervise(
                                SearchParentActor.create(
                                        newsService,
                                        sentimentService,
                                        sourceProfileService,
                                        readabilityService))
                        .onFailure(SupervisorStrategy.restart().withLoggingEnabled(true));

        this.searchActorSystem = ActorSystem.create(parentBehavior, "notilytics-search-parent");
        this.searchActor = searchActorSystem;
        this.scheduler = searchActorSystem.scheduler();
        this.askTimeout = Duration.ofSeconds(15);
        this.materializer = SystemMaterializer.get(searchActorSystem).materializer();

        lifecycle.addStopHook(() -> {
            searchActorSystem.terminate();
            return FutureConverters.asJava(searchActorSystem.whenTerminated()).thenApply(done -> null);
        });
    }

    public Result index(Http.Request request) {
        return ok(views.html.index.render(request));
    }

    public Result source(Http.Request request) {
        String selectedId = coalesce(request.getQueryString("sourceId"), "");
        String selectedName = coalesce(request.getQueryString("sourceName"), "");
        return ok(views.html.source.render(selectedId, selectedName, request));
    }

    public Result wordstats() {
        return ok(views.html.wordstats.render("Word Stats"));
    }

    public CompletionStage<Result> search(Http.Request request) {
        return AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.PerformSearch(request, replyTo),
                askTimeout,
                scheduler
        );
    }

    public CompletionStage<Result> streamSearch(Http.Request request) {
        CompletionStage<SearchPreparation> preparation = AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.ResolveStreamSpec(request, replyTo),
                askTimeout,
                scheduler
        );

        return preparation.thenApply(prep -> {
            if (prep.errorResult != null) {
                return prep.errorResult;
            }

            String sessionId = Optional.ofNullable(request.getQueryString("sessionId"))
                    .filter(s -> !s.isBlank())
                    .orElse(UUID.randomUUID().toString());

            Pair<SourceQueueWithComplete<String>, Source<String, NotUsed>> pair =
                    Source.<String>queue(32, OverflowStrategy.dropHead())
                            .preMaterialize(materializer);

            SourceQueueWithComplete<String> queue = pair.first();
            Source<String, NotUsed> source = pair.second();

            searchActor.tell(new SearchParentActor.StartUserSession(prep.spec, sessionId, queue));

            Source<ByteString, NotUsed> sseSource = source.map(ByteString::fromString);
            return Results.ok().chunked(sseSource).as(Http.MimeTypes.EVENT_STREAM);
        });
    }

    public WebSocket streamSearchWs() {
        return WebSocket.Text.acceptOrResult(this::streamSearchWsFlow);
    }

    CompletionStage<F.Either<Result, Flow<String, String, ?>>> streamSearchWsFlow(Http.RequestHeader requestHeader) {
        Http.Request fakeRequest = new RequestBuilder()
                .method("GET")
                .uri(requestHeader.uri())
                .build();

        CompletionStage<SearchPreparation> preparation = AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.ResolveStreamSpec(fakeRequest, replyTo),
                askTimeout,
                scheduler
        );

        return preparation.thenApply(prep -> {
            if (prep.errorResult != null) {
                return F.Either.Left(prep.errorResult);
            }

            String sessionId = Optional.ofNullable(fakeRequest.getQueryString("sessionId"))
                    .filter(s -> !s.isBlank())
                    .orElse(UUID.randomUUID().toString());

            Pair<SourceQueueWithComplete<String>, Source<String, NotUsed>> pair =
                    Source.<String>queue(32, OverflowStrategy.dropHead())
                            .preMaterialize(materializer);

            SourceQueueWithComplete<String> queue = pair.first();
            Source<String, NotUsed> source = pair.second();

            searchActor.tell(new SearchParentActor.StartUserSession(
                    prep.spec,
                    sessionId,
                    queue
            ));

            Flow<String, String, NotUsed> flow = Flow.fromSinkAndSourceCoupled(
                    Sink.ignore(),
                    source
            );

            Flow<String, String, ?> upcast = flow;
            return F.Either.Right(upcast);
        });
    }

    public CompletionStage<Result> sources(Http.Request request) {
        return AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.ListSources(request, replyTo),
                askTimeout,
                scheduler
        );
    }

    public CompletionStage<Result> readabilityJson(Http.Request request) {
        return AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.ComputeReadability(request, replyTo),
                askTimeout,
                scheduler
        );
    }

    public CompletionStage<Result> wordStats(Http.Request request) {
        return AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.ComputeWordStats(request, replyTo),
                askTimeout,
                scheduler
        );
    }

    public CompletionStage<Result> sessionHistory(Http.Request request) {
        String sessionId = Optional.ofNullable(request.getQueryString("sessionId"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(null);
        if (sessionId == null) {
            ObjectNode err = Json.newObject().put("error", "Missing required parameter 'sessionId'");
            return CompletableFuture.completedFuture(Results.badRequest(err).as("application/json"));
        }

        return AskPattern.ask(
                searchActor,
                replyTo -> new SearchParentActor.FetchSessionHistory(sessionId, replyTo),
                askTimeout,
                scheduler
        );
    }

    private static String coalesce(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }
}

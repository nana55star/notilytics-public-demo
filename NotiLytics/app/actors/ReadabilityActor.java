package actors;

import actors.search.SearchParentActor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import services.NewsService;
import services.ReadabilityService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * ------------------------------------------------------------
 * Child actor for Task B: Readability (/readability)
 * Author: @author Mustafa Kaya
 * ------------------------------------------------------------
 */
public final class ReadabilityActor extends AbstractBehavior<ReadabilityActor.Command> {

    public interface Command {}

    public static final class HandleRequest implements Command {
        public final Http.Request request;
        public final ActorRef<Result> replyTo;
        public final ActorRef<SearchParentActor.Command> parent;

        public HandleRequest(Http.Request request,
                             ActorRef<Result> replyTo,
                             ActorRef<SearchParentActor.Command> parent) {
            this.request = request;
            this.replyTo = replyTo;
            this.parent = parent;
        }
    }

    private final NewsService newsService;
    private final ReadabilityService readabilityService;
    private final ActorRef<SearchParentActor.Command> parent;

    public static Behavior<Command> create(NewsService newsService,
                                           ReadabilityService readabilityService,
                                           ActorRef<SearchParentActor.Command> parent) {
        Behavior<Command> behavior = Behaviors.setup(ctx ->
                new ReadabilityActor(ctx, newsService, readabilityService, parent));
        return Behaviors.supervise(behavior)
                .onFailure(SupervisorStrategy.restart().withLimit(3, Duration.ofMinutes(1)));
    }

    private ReadabilityActor(ActorContext<Command> context,
                             NewsService newsService,
                             ReadabilityService readabilityService,
                             ActorRef<SearchParentActor.Command> parent) {
        super(context);
        this.newsService = newsService;
        this.readabilityService = readabilityService;
        this.parent = parent;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleRequest.class, this::onHandleRequest)
                .build();
    }

    private Behavior<Command> onHandleRequest(HandleRequest cmd) {
        handleReadability(cmd.request).whenComplete((result, error) -> {
            if (error != null) {
                getContext().getLog().error("ReadabilityActor failure", error);
                ObjectNode err = Json.newObject()
                        .put("status", 500)
                        .put("error", "Failed to compute readability");
                parent.tell(new SearchParentActor.ReadabilityCompleted(
                        Results.internalServerError(err).as("application/json"),
                        cmd.replyTo));
            } else {
                parent.tell(new SearchParentActor.ReadabilityCompleted(result, cmd.replyTo));
            }
        });
        return Behaviors.stopped();
    }

    private CompletionStage<Result> handleReadability(Http.Request request) {
        final String query = Optional.ofNullable(request.getQueryString("query")).orElse("").trim();
        final String langParam = Optional.ofNullable(request.getQueryString("lang"))
                .orElse(Optional.ofNullable(request.getQueryString("language")).orElse("en"));
        final String normalizedLang = SearchParentActor.normalizeLanguageParam(langParam);
        final String lang = normalizedLang == null ? "en" : normalizedLang;
        final String sourcesCsv = SearchParentActor.joinCsvParams(request, "sources");

        String normalizedSources = sourcesCsv.isBlank() ? null : sourcesCsv;

        return newsService.searchEverything(query, normalizedSources, null, null, lang, null, null)
                .thenApply(resp -> {
                    if (resp.status >= 400) {
                        ObjectNode err = Json.newObject()
                                .put("status", resp.status)
                                .put("error", "Failed to fetch articles");
                        return Results.status(resp.status, err);
                    }

                    List<String> descriptions = SearchParentActor.extractDescriptions(resp.body);
                    ReadabilityService.Result r = readabilityService.bundleForArticles(descriptions);

                    ObjectNode out = Json.newObject();
                    out.put("query", query);
                    out.put("language", lang);
                    out.put("averageReadingEase", r.getAverageReadingEase());
                    out.put("averageGradeLevel", r.getAverageGradeLevel());

                    var items = Json.newArray();
                    r.getItems().forEach(m -> {
                        ObjectNode item = Json.newObject();
                        item.put("readingEase", m.getReadingEase());
                        item.put("gradeLevel", m.getGradeLevel());
                        item.put("words", m.getWordCount());
                        item.put("sentences", m.getSentenceCount());
                        item.put("syllables", m.getSyllableCount());
                        items.add(item);
                    });

                    out.set("items", items);
                    return Results.ok(out);
                });
    }
}

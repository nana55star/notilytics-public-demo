package actors;

import actors.search.SearchParentActor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.ErrorInfo;
import models.SourceProfile;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import services.NewsService;
import services.SourceProfileService;

import java.util.concurrent.CompletionStage;

/**
 * ------------------------------------------------------------
 * Child actor for News Resources (/sources)
 * Author: * @author Alex Sutherland
 * ------------------------------------------------------------
 */
public final class SourcesActor extends AbstractBehavior<SourcesActor.Command> {

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
    private final SourceProfileService sourceProfileService;
    private final ActorRef<SearchParentActor.Command> parent;

    public static Behavior<Command> create(NewsService newsService,
                                           SourceProfileService sourceProfileService,
                                           ActorRef<SearchParentActor.Command> parent) {
        return Behaviors.setup(ctx -> new SourcesActor(ctx, newsService, sourceProfileService, parent));
    }

    private SourcesActor(ActorContext<Command> context,
                         NewsService newsService,
                         SourceProfileService sourceProfileService,
                         ActorRef<SearchParentActor.Command> parent) {
        super(context);
        this.newsService = newsService;
        this.sourceProfileService = sourceProfileService;
        this.parent = parent;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleRequest.class, this::onHandleRequest)
                .build();
    }

    private Behavior<Command> onHandleRequest(HandleRequest cmd) {
        handleSources(cmd.request).whenComplete((result, error) -> {
            if (error != null) {
                getContext().getLog().error("SourcesActor failure", error);
                ObjectNode err = Json.newObject()
                        .put("status", "error")
                        .put("message", "Failed to process sources request");
                cmd.parent.tell(new SearchParentActor.SourcesCompleted(
                        Results.internalServerError(err).as("application/json"),
                        cmd.replyTo));
            } else {
                cmd.parent.tell(new SearchParentActor.SourcesCompleted(result, cmd.replyTo));
            }
        });
        return Behaviors.stopped();
    }

    private CompletionStage<Result> handleSources(Http.Request request) {
        String sourceId = SearchParentActor.coalesce(request.getQueryString("sourceId"), request.getQueryString("id"));

        if (sourceId != null && !sourceId.isBlank()) {
            String language = SearchParentActor.coalesce(request.getQueryString("language"), "all");
            String category = SearchParentActor.coalesce(request.getQueryString("category"), "");
            String country = SearchParentActor.coalesce(request.getQueryString("country"), "");
            String sortBy = SearchParentActor.coalesce(request.getQueryString("sortBy"), "publishedAt");
            String pageSize = SearchParentActor.coalesce(request.getQueryString("pageSize"), "10");

            return sourceProfileService.fetchSourceProfile(sourceId, language, category, country, sortBy, pageSize)
                    .thenApply(result -> {
                        if (result.isSuccess() && result.getData().isPresent()) {
                            SourceProfile profile = result.getData().get();
                            ObjectNode response = Json.newObject();
                            response.put("status", "ok");
                            response.set("source", Json.toJson(profile.getSource()));
                            response.set("articles", Json.toJson(profile.getArticles()));
                            response.put("totalResults", profile.getTotalResults());
                            return Results.status(result.getStatus(), response).as("application/json");
                        } else if (result.getError().isPresent()) {
                            ErrorInfo errorInfo = result.getError().get();
                            ObjectNode error = Json.newObject();
                            error.put("status", "error");
                            error.put("message", errorInfo.getMessage());
                            error.set("details", errorInfo.getDetails());
                            return Results.status(result.getStatus(), error).as("application/json");
                        } else {
                            ObjectNode error = Json.newObject();
                            error.put("status", "error");
                            error.put("message", "Unknown error fetching source profile.");
                            return Results.internalServerError(error).as("application/json");
                        }
                    });
        }

        String language = SearchParentActor.coalesce(request.getQueryString("language"), "");
        String category = SearchParentActor.coalesce(request.getQueryString("category"), "");
        String country = SearchParentActor.coalesce(request.getQueryString("country"), "");

        return newsService.listSources(language, category, country)
                .thenApply(r -> Results.status(r.status, r.body).as("application/json"));
    }
}

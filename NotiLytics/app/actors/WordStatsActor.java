package actors;

import actors.search.SearchParentActor;
import com.fasterxml.jackson.databind.JsonNode;
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
import services.NewsResponse;
import services.NewsService;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Pekko actor responsible for computing word-frequency stats.
 * @author Khashayar Zardoui
 */
public final class WordStatsActor extends AbstractBehavior<WordStatsActor.Command> {

    public interface Command {}

    public static final class HandleRequest implements Command {
        public final Http.Request request;
        public final ActorRef<Result> replyTo;

        public HandleRequest(Http.Request request, ActorRef<Result> replyTo) {
            this.request = request;
            this.replyTo = replyTo;
        }
    }

    private final NewsService newsService;
    private final ActorRef<SearchParentActor.Command> parent;

    public static Behavior<Command> create(NewsService newsService,
                                           ActorRef<SearchParentActor.Command> parent) {
        Behavior<Command> behavior = Behaviors.setup(ctx -> new WordStatsActor(ctx, newsService, parent));
        return Behaviors.supervise(behavior)
                .onFailure(SupervisorStrategy.restart().withLimit(3, Duration.ofMinutes(1)));
    }

    private WordStatsActor(ActorContext<Command> context,
                           NewsService newsService,
                           ActorRef<SearchParentActor.Command> parent) {
        super(context);
        this.newsService = newsService;
        this.parent = parent;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleRequest.class, this::onHandleRequest)
                .build();
    }

    private Behavior<Command> onHandleRequest(HandleRequest cmd) {
        computeWordStats(cmd.request).whenComplete((result, error) -> {
            Result out = result;
            if (error != null || result == null) {
                getContext().getLog().error("WordStatsActor failure", error);
                out = Results.internalServerError(Json.newObject().put("error", "Failed to compute word stats"))
                        .as("application/json");
            }
            parent.tell(new SearchParentActor.WordStatsCompleted(out, cmd.replyTo));
        });
        return Behaviors.stopped();
    }

    private CompletionStage<Result> computeWordStats(Http.Request request) {
        String q = request.getQueryString("q");
        if (q == null || q.isBlank()) {
            return CompletableFuture.completedFuture(
                    Results.badRequest(Json.newObject().put("error", "Missing required parameter 'q'"))
                            .as("application/json")
            );
        }

        String langParam = Optional.ofNullable(request.getQueryString("lang"))
                .orElse(Optional.ofNullable(request.getQueryString("language")).orElse("en"));
        String normalizedLang = SearchParentActor.normalizeLanguageParam(langParam);
        String language = normalizedLang == null ? "en" : normalizedLang;

        return newsService.searchEverything(q, "", "", "", language, "relevancy", "50")
                .thenApply(this::toResult);
    }

    private Result toResult(NewsResponse r) {
        if (r == null) {
            return Results.internalServerError(Json.newObject().put("error", "Failed to compute word stats"))
                    .as("application/json");
        }

        if (r.status != 200) {
            return Results.status(r.status, r.body).as("application/json");
        }

        try {
            JsonNode root = Json.parse(r.body);
            JsonNode arr = root.path("articles");

            java.util.Map<String, Long> freq = new java.util.HashMap<>();
            if (arr.isArray()) {
                java.util.Spliterator<JsonNode> spl = arr.spliterator();
                java.util.stream.StreamSupport.stream(spl, false)
                        .map(n -> n.path("description").asText(""))
                        .filter(s -> s != null && !s.isBlank())
                        .flatMap(s -> java.util.Arrays.stream(s.toLowerCase().split("[^\\p{L}0-9]+")))
                        .filter(w -> w != null && !w.isBlank())
                        .forEach(w -> freq.merge(w, 1L, Long::sum));
            }

            java.util.List<ObjectNode> items = freq.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Long>comparingByValue(java.util.Comparator.reverseOrder())
                            .thenComparing(java.util.Map.Entry.comparingByKey()))
                    .map(e -> {
                        ObjectNode o = Json.newObject();
                        o.put("word", e.getKey());
                        o.put("count", e.getValue());
                        return o;
                    }).collect(Collectors.toList());

            ObjectNode out = Json.newObject();
            out.put("status", "ok");
            out.set("words", Json.toJson(items));
            return Results.ok(out).as("application/json");
        } catch (Exception ex) {
            return Results.internalServerError(Json.newObject().put("error", "Failed to compute word stats"))
                    .as("application/json");
        }
    }
}

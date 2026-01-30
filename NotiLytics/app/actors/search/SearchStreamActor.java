package actors.search;

import actors.SentimentActor;
import actors.search.SearchStreamActor.RawFetchCompleted;
import actors.search.SearchStreamActor.SentimentAnalysisCompleted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import play.libs.Json;
import play.mvc.Results;
import services.NewsResponse;
import services.NewsService;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ------------------------------------------------------------
 * Child actor for Streaming Search (Modified for Phase 2)
 * Author:  @author Nirvana Borham
 * ------------------------------------------------------------
 */
public final class SearchStreamActor extends AbstractBehavior<SearchStreamActor.Message> {

    private static final ObjectMapper MAPPER = Json.mapper();

    public interface Message {}

    public static final class ExecuteFetch implements Message {
        public final boolean initial;

        public ExecuteFetch(boolean initial) {
            this.initial = initial;
        }
    }

    public static final class RawFetchCompleted implements Message {
        public final boolean initial;
        public final NewsResponse response;
        public final Throwable error;

        public RawFetchCompleted(boolean initial, NewsResponse response, Throwable error) {
            this.initial = initial;
            this.response = response;
            this.error = error;
        }
    }

    public static final class SentimentAnalysisCompleted implements Message {
        public final boolean initial;
        public final String enrichedJson;

        public SentimentAnalysisCompleted(boolean initial, String enrichedJson) {
            this.initial = initial;
            this.enrichedJson = enrichedJson;
        }
    }

    public static final class Stop implements Message {}

    private static final Duration STREAM_POLL_INTERVAL = Duration.ofSeconds(20);

    private final NewsService newsService;
    private final ActorRef<SentimentActor.Command> sentimentActor;
    private final SearchParentActor.SearchSpec spec;
    private final String sessionId;
    private final SourceQueueWithComplete<String> queue;
    private final TimerScheduler<Message> timers;
    private final Set<String> seenArticleKeys = new HashSet<>();
    private boolean closed;

    public static Behavior<Message> create(NewsService newsService,
                                           SearchParentActor.SearchSpec spec,
                                           String sessionId,
                                           SourceQueueWithComplete<String> queue) {
        return Behaviors.setup(ctx -> Behaviors.withTimers(timers ->
                new SearchStreamActor(ctx, timers, newsService, spec, sessionId, queue)));
    }

    private SearchStreamActor(ActorContext<Message> context,
                              TimerScheduler<Message> timers,
                              NewsService newsService,
                              SearchParentActor.SearchSpec spec,
                              String sessionId,
                              SourceQueueWithComplete<String> queue) {
        super(context);
        this.timers = timers;
        this.newsService = newsService;
        this.spec = spec;
        this.sessionId = sessionId;
        this.queue = queue;
        this.sentimentActor = context.spawn(
                Behaviors.supervise(SentimentActor.create())
                        .onFailure(SupervisorStrategy.restart()),
                "sentiment-actor-" + sessionId);

        timers.startTimerAtFixedRate("poll", new ExecuteFetch(false), STREAM_POLL_INTERVAL);
        getContext().getSelf().tell(new ExecuteFetch(true));

        ActorRef<Message> self = getContext().getSelf();
        queue.watchCompletion().whenComplete((Done done, Throwable err) -> self.tell(new Stop()));
    }

    @Override
    public Receive<Message> createReceive() {
        return newReceiveBuilder()
                .onMessage(ExecuteFetch.class, this::onExecuteFetch)
                .onMessage(RawFetchCompleted.class, this::onRawFetchCompleted)
                .onMessage(SentimentAnalysisCompleted.class, this::onSentimentAnalysisCompleted)
                .onMessage(Stop.class, this::onStop)
                .build();
    }

    private Behavior<Message> onExecuteFetch(ExecuteFetch command) {
        getContext().pipeToSelf(
                newsService.searchEverything(
                        spec.query,
                        spec.sources,
                        spec.country,
                        spec.category,
                        spec.language,
                        spec.sortBy,
                        String.valueOf(spec.pageSize)),
                (response, error) -> new RawFetchCompleted(command.initial, response, error)
        );
        return this;
    }

    private Behavior<Message> onRawFetchCompleted(RawFetchCompleted completed) {
        if (closed) {
            return this;
        }

        if (completed.error != null || completed.response == null) {
            return failStream("Failed to fetch streaming results");
        }

        if (completed.response.status >= 400) {
            return failStream("News API returned status " + completed.response.status);
        }

        getContext().ask(
                SentimentActor.SentimentResult.class,
                sentimentActor,
                Duration.ofSeconds(5),
                replyTo -> new SentimentActor.AnalyzeSentiment(completed.response.body, replyTo),
                (response, error) -> {
                    if (error != null || response == null) {
                        return new SentimentAnalysisCompleted(completed.initial, completed.response.body);
                    }
                    return new SentimentAnalysisCompleted(completed.initial, response.enrichedJson);
                });

        return this;
    }

    private Behavior<Message> onSentimentAnalysisCompleted(SentimentAnalysisCompleted completed) {
        if (closed) {
            return this;
        }

        try {
            JsonNode root = MAPPER.readTree(completed.enrichedJson);
            JsonNode arrNode = root.path("articles");
            ArrayNode articles = arrNode.isArray() ? (ArrayNode) arrNode : MAPPER.createArrayNode();

            if (completed.initial) {
                rememberInitial(articles);
                emitInitial(root, articles);
            } else {
                ArrayNode newOnes = Json.newArray();
                articles.forEach(node -> {
                    if (markIfNew(node)) {
                        newOnes.add(node);
                    }
                });
                if (newOnes.size() > 0) {
                    emitAppend(root, newOnes);
                }
            }
        } catch (Exception ex) {
            return failStream("Failed to parse streaming payload");
        }

        return this;
    }

    private Behavior<Message> failStream(String message) {
        if (!closed) {
            ObjectNode payload = Json.newObject().put("message", message);
            pushEvent("stream-error", payload);
            closed = true;
            queue.complete();
        }
        timers.cancelAll();
        return Behaviors.stopped();
    }

    private Behavior<Message> onStop(Stop stop) {
        timers.cancelAll();
        closed = true;
        return Behaviors.stopped();
    }

    private void pushEvent(String eventName, ObjectNode payload) {
        if (closed) {
            return;
        }
        payload.put("sessionId", sessionId);
        String serialized = SearchParentActor.toSseEvent(eventName, payload.toString());
        queue.offer(serialized);
    }

    private void emitInitial(JsonNode root, ArrayNode articles) {
        ObjectNode payload = Json.newObject();
        payload.put("overallSentiment", root.path("overallSentiment").asText(""));
        payload.put("resultCount", articles.size());
        payload.put("initial", true);
        payload.set("articles", articles);
        pushEvent("init", payload);
    }

    private void emitAppend(JsonNode root, ArrayNode articles) {
        ObjectNode payload = Json.newObject();
        payload.put("overallSentiment", root.path("overallSentiment").asText(""));
        payload.put("resultCount", articles.size());
        payload.set("articles", articles);
        pushEvent("append", payload);
    }

    private void rememberInitial(ArrayNode articles) {
        articles.forEach(node -> seenArticleKeys.add(articleKey(node)));
    }

    private boolean markIfNew(JsonNode node) {
        String key = articleKey(node);
        if (key.isBlank()) {
            return false;
        }
        return seenArticleKeys.add(key);
    }

    public static String articleKey(JsonNode node) {
        String url = node.path("url").asText("").trim();
        if (!url.isEmpty()) {
            return url;
        }
        String title = node.path("title").asText("").trim();
        String publishedAt = node.path("publishedAt").asText("").trim();
        String combined = Arrays.stream(new String[] { title, publishedAt })
                .filter(part -> !part.isBlank())
                .collect(java.util.stream.Collectors.joining("|"));
        if (!combined.isEmpty()) {
            return combined;
        }
        return Integer.toHexString(node.toString().hashCode());
    }
}

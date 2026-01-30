package actors.search;

import actors.ReadabilityActor;
import actors.SourcesActor;
import actors.UserSessionActor;
import actors.WordStatsActor;
import actors.search.SearchParentActor.SearchPreparation;
import actors.search.SearchParentActor.SearchSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import services.NewsService;
import services.ReadabilityService;
import services.SentimentService;
import services.SourceProfileService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Parent Pekko actor that orchestrates all search-related workflows. It owns child actors
 * that perform discrete tasks (streaming search, readability analysis, etc.) and exposes
 * a typed protocol consumed by {@link controllers.SearchController}.
 *  @author Nirvana Borham, Alex Sutherland, Mustafa Kaya, Khashayar Zardoui, zahra ebrahimizadehghahrood
 */
public final class SearchParentActor extends AbstractBehavior<SearchParentActor.Command> {

    private static final ObjectMapper MAPPER = Json.mapper();

    public interface Command {}

    public static final class SearchSpec {
        public final String query;
        public final String sources;
        public final String country;
        public final String category;
        public final String language;
        public final String sortBy;
        public final int pageSize;

        public SearchSpec(String query,
                          String sources,
                          String country,
                          String category,
                          String language,
                          String sortBy,
                          int pageSize) {
            this.query = query;
            this.sources = sources;
            this.country = country;
            this.category = category;
            this.language = language;
            this.sortBy = sortBy;
            this.pageSize = pageSize;
        }
    }

    public static final class SearchPreparation {
        public final SearchSpec spec;
        public final Result errorResult;

        private SearchPreparation(SearchSpec spec, Result errorResult) {
            this.spec = spec;
            this.errorResult = errorResult;
        }

        public static SearchPreparation success(SearchSpec spec) {
            return new SearchPreparation(spec, null);
        }

        public static SearchPreparation failure(Result error) {
            return new SearchPreparation(null, error);
        }
    }

    public static final class PerformSearch implements Command {
        public final Http.Request request;
        public final ActorRef<Result> replyTo;

        public PerformSearch(Http.Request request, ActorRef<Result> replyTo) {
            this.request = request;
            this.replyTo = replyTo;
        }
    }

    public static final class ListSources implements Command {
        public final Http.Request request;
        public final ActorRef<Result> replyTo;

        public ListSources(Http.Request request, ActorRef<Result> replyTo) {
            this.request = request;
            this.replyTo = replyTo;
        }
    }

    public static final class SourcesCompleted implements Command {
        public final Result result;
        public final ActorRef<Result> replyTo;

        public SourcesCompleted(Result result, ActorRef<Result> replyTo) {
            this.result = result;
            this.replyTo = replyTo;
        }
    }

    public static final class ReadabilityCompleted implements Command {
        public final Result result;
        public final ActorRef<Result> replyTo;

        public ReadabilityCompleted(Result result, ActorRef<Result> replyTo) {
            this.result = result;
            this.replyTo = replyTo;
        }
    }

    public static final class WordStatsCompleted implements Command {
        public final Result result;
        public final ActorRef<Result> replyTo;

        public WordStatsCompleted(Result result, ActorRef<Result> replyTo) {
            this.result = result;
            this.replyTo = replyTo;
        }
    }

    public static final class ComputeReadability implements Command {
        public final Http.Request request;
        public final ActorRef<Result> replyTo;

        public ComputeReadability(Http.Request request, ActorRef<Result> replyTo) {
            this.request = request;
            this.replyTo = replyTo;
        }
    }

    public static final class ComputeWordStats implements Command {
        public final Http.Request request;
        public final ActorRef<Result> replyTo;

        public ComputeWordStats(Http.Request request, ActorRef<Result> replyTo) {
            this.request = request;
            this.replyTo = replyTo;
        }
    }

    public static final class ResolveStreamSpec implements Command {
        public final Http.Request request;
        public final ActorRef<SearchPreparation> replyTo;

        public ResolveStreamSpec(Http.Request request, ActorRef<SearchPreparation> replyTo) {
            this.request = request;
            this.replyTo = replyTo;
        }
    }

    public static final class StartStream implements Command {
        public final SearchSpec spec;
        public final String sessionId;
        public final SourceQueueWithComplete<String> queue;

        public StartStream(SearchSpec spec, String sessionId, SourceQueueWithComplete<String> queue) {
            this.spec = spec;
            this.sessionId = sessionId;
            this.queue = Objects.requireNonNull(queue, "session queue must not be null");
        }
    }

    public static final class StartUserSession implements Command {
        public final SearchSpec spec;
        public final String sessionId;
        public final SourceQueueWithComplete<String> queue;

        public StartUserSession(SearchSpec spec, String sessionId, SourceQueueWithComplete<String> queue) {
            this.spec = spec;
            this.sessionId = sessionId;
            this.queue = queue;
        }
    }

    public static final class FetchSessionHistory implements Command {
        public final String sessionId;
        public final ActorRef<Result> replyTo;

        public FetchSessionHistory(String sessionId, ActorRef<Result> replyTo) {
            this.sessionId = sessionId;
            this.replyTo = replyTo;
        }
    }

    public static final class SessionClosed implements Command {
        public final String sessionId;

        public SessionClosed(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    public static final class SessionHistoryUpdated implements Command {
        public final String sessionId;
        public final List<UserSessionActor.HistoryEntry> history;

        public SessionHistoryUpdated(String sessionId, List<UserSessionActor.HistoryEntry> history) {
            this.sessionId = sessionId;
            this.history = history;
        }
    }

    private final NewsService newsService;
    private final SentimentService sentimentService;
    private final SourceProfileService sourceProfileService;
    private final ReadabilityService readabilityService;
    private final Map<String, ActorRef<UserSessionActor.Command>> sessionActors = new HashMap<>();
    private final Map<String, List<UserSessionActor.HistoryEntry>> sessionHistoryCache = new HashMap<>();

    public static Behavior<Command> create(NewsService newsService,
                                           SentimentService sentimentService,
                                           SourceProfileService sourceProfileService,
                                           ReadabilityService readabilityService) {
        return Behaviors.setup(ctx -> new SearchParentActor(ctx, newsService, sentimentService, sourceProfileService, readabilityService));
    }

    private SearchParentActor(ActorContext<Command> context,
                              NewsService newsService,
                              SentimentService sentimentService,
                              SourceProfileService sourceProfileService,
                              ReadabilityService readabilityService) {
        super(context);
        this.newsService = newsService;
        this.sentimentService = sentimentService;
        this.sourceProfileService = sourceProfileService;
        this.readabilityService = readabilityService;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(PerformSearch.class, this::onPerformSearch)
                .onMessage(ListSources.class, this::onListSources)
                .onMessage(SourcesCompleted.class, this::onSourcesCompleted)
                .onMessage(ComputeReadability.class, this::onComputeReadability)
                .onMessage(ReadabilityCompleted.class, this::onReadabilityCompleted)
                .onMessage(WordStatsCompleted.class, this::onWordStatsCompleted)
                .onMessage(ComputeWordStats.class, this::onComputeWordStats)
                .onMessage(ResolveStreamSpec.class, this::onResolveStreamSpec)
                .onMessage(StartUserSession.class, this::onStartUserSession)
                .onMessage(StartStream.class, this::onStartStream)
                .onMessage(FetchSessionHistory.class, this::onFetchSessionHistory)
                .onMessage(SessionClosed.class, this::onSessionClosed)
                .onMessage(SessionHistoryUpdated.class, this::onSessionHistoryUpdated)
                .build();
    }

    private Behavior<Command> onPerformSearch(PerformSearch command) {
        pipeResult(handleSearch(command.request), command.replyTo);
        return this;
    }

    private Behavior<Command> onListSources(ListSources command) {
        ActorRef<SourcesActor.Command> child =
                getContext().spawnAnonymous(SourcesActor.create(newsService, sourceProfileService, getContext().getSelf()));
        child.tell(new SourcesActor.HandleRequest(command.request, command.replyTo, getContext().getSelf()));
        return this;
    }

    private Behavior<Command> onSourcesCompleted(SourcesCompleted completed) {
        completed.replyTo.tell(completed.result);
        return this;
    }

    private Behavior<Command> onReadabilityCompleted(ReadabilityCompleted completed) {
        completed.replyTo.tell(completed.result);
        return this;
    }

    private Behavior<Command> onWordStatsCompleted(WordStatsCompleted completed) {
        completed.replyTo.tell(completed.result);
        return this;
    }

    private Behavior<Command> onComputeReadability(ComputeReadability command) {
        ActorRef<ReadabilityActor.Command> child = getContext().spawnAnonymous(
                ReadabilityActor.create(newsService, readabilityService, getContext().getSelf()));
        child.tell(new ReadabilityActor.HandleRequest(command.request, command.replyTo, getContext().getSelf()));
        return this;
    }

    private Behavior<Command> onComputeWordStats(ComputeWordStats command) {
        ActorRef<WordStatsActor.Command> actor = getContext().spawnAnonymous(
                WordStatsActor.create(newsService, getContext().getSelf()));
        actor.tell(new WordStatsActor.HandleRequest(command.request, command.replyTo));
        return this;
    }

    private Behavior<Command> onResolveStreamSpec(ResolveStreamSpec command) {
        prepareSearchSpec(command.request, 10).whenComplete((prep, error) -> {
            if (error != null) {
                Result err = Results.internalServerError(Json.newObject().put("error", "Unable to start streaming search")).as("application/json");
                command.replyTo.tell(SearchPreparation.failure(err));
            } else {
                command.replyTo.tell(prep);
            }
        });
        return this;
    }

    private Behavior<Command> onStartStream(StartStream command) {
        Behavior<SearchStreamActor.Message> streamBehavior =
                Behaviors.supervise(
                                SearchStreamActor.create(newsService, command.spec, command.sessionId, command.queue))
                        .onFailure(SupervisorStrategy.restartWithBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2));
        getContext().spawnAnonymous(streamBehavior);
        return this;
    }

    private Behavior<Command> onStartUserSession(StartUserSession command) {
        ActorRef<UserSessionActor.Command> existing = sessionActors.remove(command.sessionId);
        if (existing != null) {
            existing.tell(new UserSessionActor.Stop());
        }

        ActorRef<UserSessionActor.Command> child = getContext().spawnAnonymous(UserSessionActor.create(
                getContext().getSelf(),
                command.spec,
                command.sessionId,
                command.queue
        ));
        getContext().watchWith(child, new SessionClosed(command.sessionId));
        sessionActors.put(command.sessionId, child);
        return this;
    }

    private Behavior<Command> onFetchSessionHistory(FetchSessionHistory command) {
        ActorRef<UserSessionActor.Command> session = sessionActors.get(command.sessionId);
        if (session == null) {
            List<UserSessionActor.HistoryEntry> cached = sessionHistoryCache.get(command.sessionId);
            if (cached == null) {
                ObjectNode err = Json.newObject()
                        .put("error", "Unknown sessionId")
                        .put("sessionId", command.sessionId);
                command.replyTo.tell(Results.status(404, err).as("application/json"));
                return this;
            }
            command.replyTo.tell(historyResult(command.sessionId, cached));
            return this;
        }

        Duration timeout = Duration.ofSeconds(5);
        CompletionStage<UserSessionActor.SearchHistorySnapshot> historyFuture = AskPattern.ask(
                session,
                replyTo -> new UserSessionActor.GetHistory(replyTo),
                timeout,
                getContext().getSystem().scheduler());

        historyFuture.whenComplete((snapshot, error) -> {
            if (error != null || snapshot == null) {
                ObjectNode err = Json.newObject().put("error", "Failed to load session history");
                command.replyTo.tell(Results.internalServerError(err).as("application/json"));
            } else {
                command.replyTo.tell(historyResult(snapshot.sessionId, snapshot.history));
                sessionHistoryCache.put(snapshot.sessionId, snapshot.history);
            }
        });
        return this;
    }

    private Behavior<Command> onSessionClosed(SessionClosed closed) {
        sessionActors.remove(closed.sessionId);
        return this;
    }

    private Behavior<Command> onSessionHistoryUpdated(SessionHistoryUpdated updated) {
        sessionHistoryCache.put(updated.sessionId, updated.history);
        return this;
    }

    private void pipeResult(CompletionStage<Result> stage, ActorRef<Result> replyTo) {
        stage.whenComplete((result, error) -> {
            if (error != null) {
                getContext().getLog().error("SearchParentActor failure", error);
                Result fallback = Results.internalServerError(Json.newObject().put("error", "Search processing failed"))
                        .as("application/json");
                replyTo.tell(fallback);
            } else {
                replyTo.tell(result);
            }
        });
    }

    private CompletionStage<Result> handleSearch(Http.Request request) {
        return prepareSearchSpec(request, 10).thenCompose(prep -> {
            if (prep.errorResult != null) {
                return CompletableFuture.completedFuture(prep.errorResult);
            }

            SearchSpec spec = prep.spec;
            return sentimentService.searchEverythingWithSentiment(
                            spec.query,
                            spec.sources,
                            spec.country,
                            spec.category,
                            spec.language,
                            spec.sortBy,
                            String.valueOf(spec.pageSize))
                    .thenApply(r -> Results.status(r.status, r.body).as("application/json"));
        });
    }

    private CompletionStage<SearchPreparation> prepareSearchSpec(Http.Request request, int pageSz) {
        String query = Optional.ofNullable(request.getQueryString("q"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(null);
        String sourcesCsv = joinCsvParams(request, "sources");
        String normalizedSources = sourcesCsv.isBlank() ? null : sourcesCsv;
        String country = request.getQueryString("country");
        String category = request.getQueryString("category");
        String language = normalizeLanguageParam(
                Optional.ofNullable(request.getQueryString("language"))
                        .orElse(request.getQueryString("lang"))
        );
        String sortBy = Optional.ofNullable(request.getQueryString("sortBy")).orElse("relevancy");

        boolean noQ = (query == null || query.isBlank());
        boolean noSources = (normalizedSources == null || normalizedSources.isBlank());
        boolean noFilters = (isBlank(country) && isBlank(category) && language == null);

        if (noQ && noSources) {
            if (noFilters) {
                String msg = "Add keywords, select specific sources, or choose at least one filter (country/category/language).";
                Result err = Results.badRequest(Json.newObject().put("error", msg)).as("application/json");
                return CompletableFuture.completedFuture(SearchPreparation.failure(err));
            }

            String listLanguage = language == null ? "" : language;
            String listCategory = coalesce(category, "");
            String listCountry = coalesce(country, "");

            return newsService.listSources(listLanguage, listCategory, listCountry)
                    .thenApply(resp -> {
                        if (resp.status >= 400) {
                            Result error = Results.status(resp.status, resp.body).as("application/json");
                            return SearchPreparation.failure(error);
                        }

                        List<String> ids = extractSourceIds(resp.body);
                        if (ids.isEmpty()) {
                            ObjectNode err = Json.newObject()
                                    .put("error", "No sources found for the selected filters. Try adding keywords or broaden filters.");
                            return SearchPreparation.failure(Results.status(404, err).as("application/json"));
                        }

                        String expandedSources = String.join(",", ids);
                        SearchSpec spec = new SearchSpec(null, expandedSources, country, category, language, sortBy, pageSz);
                        return SearchPreparation.success(spec);
                    });
        }

        SearchSpec spec = new SearchSpec(query, normalizedSources, country, category, language, sortBy, pageSz);
        return CompletableFuture.completedFuture(SearchPreparation.success(spec));
    }

    public static ObjectNode historyEntryToJson(UserSessionActor.HistoryEntry entry) {
        ObjectNode node = Json.newObject();
        node.put("query", coalesce(entry.query, ""));
        node.put("sources", coalesce(entry.sources, ""));
        node.put("country", coalesce(entry.country, ""));
        node.put("category", coalesce(entry.category, ""));
        node.put("language", coalesce(entry.language, ""));
        node.put("sortBy", coalesce(entry.sortBy, ""));
        node.put("timestamp", entry.timestamp);
        return node;
    }

    private Result historyResult(String sessionId, List<UserSessionActor.HistoryEntry> entries) {
        ObjectNode body = Json.newObject();
        body.put("sessionId", sessionId);
        ArrayNode arr = Json.newArray();
        entries.forEach(entry -> arr.add(historyEntryToJson(entry)));
        body.set("history", arr);
        return Results.ok(body).as("application/json");
    }

    public static String coalesce(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }

    public static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    public static String joinCsvParams(Http.Request request, String key) {
        String[] values = request.queryString().getOrDefault(key, new String[0]);
        return Arrays.stream(values)
                .map(v -> v == null ? "" : v.trim())
                .filter(v -> !v.isEmpty())
                .collect(Collectors.joining(","));
    }

    public static String normalizeLanguageParam(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("all")) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static List<String> extractSourceIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode arr = root.path("sources");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String id = node.path("id").asText("").trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                        if (ids.size() >= 20) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            ids.clear();
        }
        return ids;
    }

    public static List<String> extractDescriptions(String body) {
        List<String> result = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode arr = root.path("articles");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    JsonNode descNode = n.get("description");
                    String d = (descNode == null || descNode.isNull())
                            ? ""
                            : descNode.asText("");
                    result.add(d);
                }
            }
        } catch (Exception ignore) {
            // Intentionally swallow parsing issues; callers treat missing data as empty.
        }
        return result;
    }

    public static String toSseEvent(String eventName, String data) {
        return "event: " + eventName + "\n" + "data: " + data + "\n\n";
    }
}

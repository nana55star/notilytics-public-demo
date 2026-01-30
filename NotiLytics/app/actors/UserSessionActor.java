package actors;

import actors.UserSessionActor.Stop;
import actors.search.SearchParentActor;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import play.libs.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * ------------------------------------------------------------
 * Child actor for per-WebSocket session (stores history per user)
 * Author(s): * @author Alex Sutherland
 * ------------------------------------------------------------
 */
public final class UserSessionActor extends AbstractBehavior<UserSessionActor.Command> {

    public interface Command {}

    public static final class Stop implements Command {}

    public static final class RecordSearch implements Command {
        public final SearchParentActor.SearchSpec spec;

        public RecordSearch(SearchParentActor.SearchSpec spec) {
            this.spec = spec;
        }
    }

    public static final class GetHistory implements Command {
        final ActorRef<SearchHistorySnapshot> replyTo;

        public GetHistory(ActorRef<SearchHistorySnapshot> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static final class SearchHistorySnapshot {
        public final String sessionId;
        public final List<HistoryEntry> history;

        SearchHistorySnapshot(String sessionId, List<HistoryEntry> history) {
            this.sessionId = sessionId;
            this.history = history;
        }
    }

    public static final class HistoryEntry {
        public final String query;
        public final String sources;
        public final String country;
        public final String category;
        public final String language;
        public final String sortBy;
        public final long timestamp;

        public HistoryEntry(String query,
                            String sources,
                            String country,
                            String category,
                            String language,
                            String sortBy,
                            long timestamp) {
            this.query = query;
            this.sources = sources;
            this.country = country;
            this.category = category;
            this.language = language;
            this.sortBy = sortBy;
            this.timestamp = timestamp;
        }

        public static HistoryEntry fromSpec(SearchParentActor.SearchSpec spec) {
            if (spec == null) {
                return new HistoryEntry(null, null, null, null, null, null, System.currentTimeMillis());
            }
            return new HistoryEntry(
                    spec.query,
                    spec.sources,
                    spec.country,
                    spec.category,
                    spec.language,
                    spec.sortBy,
                    System.currentTimeMillis());
        }
    }

    private final ActorRef<SearchParentActor.Command> parent;
    private final String sessionId;
    private final SourceQueueWithComplete<String> queue;
    private final List<HistoryEntry> history = new ArrayList<>();

    private UserSessionActor(ActorContext<Command> context,
                             ActorRef<SearchParentActor.Command> parent,
                             SearchParentActor.SearchSpec spec,
                             String sessionId,
                             SourceQueueWithComplete<String> queue) {
        super(context);
        this.parent = parent;
        this.sessionId = (sessionId == null || sessionId.isBlank())
                ? "sess-" + System.currentTimeMillis()
                : sessionId;
        this.queue = queue;
        parent.tell(new SearchParentActor.StartStream(spec, this.sessionId, queue));
        if (recordSearch(spec)) {
            publishHistorySnapshot();
        }

        queue.watchCompletion().whenComplete((Done done, Throwable err) ->
                getContext().getSelf().tell(new Stop()));
    }

    public static Behavior<Command> create(ActorRef<SearchParentActor.Command> parent,
                                           SearchParentActor.SearchSpec spec,
                                           String sessionId,
                                           SourceQueueWithComplete<String> queue) {
        return Behaviors.setup(ctx -> new UserSessionActor(ctx, parent, spec, sessionId, queue));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Stop.class, msg -> onStop())
                .onMessage(RecordSearch.class, this::onRecordSearch)
                .onMessage(GetHistory.class, this::onGetHistory)
                .build();
    }

    private Behavior<Command> onRecordSearch(RecordSearch cmd) {
        if (recordSearch(cmd.spec)) {
            publishHistorySnapshot();
        }
        return this;
    }

    private Behavior<Command> onGetHistory(GetHistory cmd) {
        cmd.replyTo.tell(new SearchHistorySnapshot(sessionId, List.copyOf(history)));
        return this;
    }

    private Behavior<Command> onStop() {
        return Behaviors.stopped();
    }

    private boolean recordSearch(SearchParentActor.SearchSpec spec) {
        if (spec == null) {
            return false;
        }
        history.add(HistoryEntry.fromSpec(spec));
        if (history.size() > 10) {
            history.remove(0);
        }
        return true;
    }

    private void publishHistorySnapshot() {
        ArrayNode arr = Json.newArray();
        history.forEach(entry -> arr.add(SearchParentActor.historyEntryToJson(entry)));
        ObjectNode payload = Json.newObject();
        payload.put("sessionId", sessionId);
        payload.set("history", arr);
        queue.offer(SearchParentActor.toSseEvent("history", payload.toString()));
        parent.tell(new SearchParentActor.SessionHistoryUpdated(sessionId, List.copyOf(history)));
    }
}

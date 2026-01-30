package controllers;

import actors.search.SearchParentActor;
import actors.UserSessionActor;
import org.apache.pekko.Done;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.stream.QueueOfferResult;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

public class UserSessionActorTest {

    private static final ActorTestKit testKit = ActorTestKit.create();

    private static final class FakeQueue implements SourceQueueWithComplete<String> {
        final List<String> events = new ArrayList<>();
        private final CompletableFuture<Done> completion = new CompletableFuture<>();

        @Override
        public CompletableFuture<QueueOfferResult> offer(String elem) {
            events.add(elem);
            return CompletableFuture.completedFuture(QueueOfferResult.enqueued());
        }

        @Override
        public void complete() {
            completion.complete(Done.getInstance());
        }

        @Override
        public void fail(Throwable ex) {
            completion.completeExceptionally(ex);
        }

        @Override
        public CompletionStage<Done> watchCompletion() {
            return completion;
        }
    }

    @AfterAll
    static void shutdown() {
        testKit.shutdownTestKit();
    }

    private static SearchParentActor.SearchSpec spec(String query) {
        return new SearchParentActor.SearchSpec(
                query,
                null,
                "us",
                "technology",
                "en",
                "relevancy",
                10);
    }

    @Test
    void sessionActorStoresInitialHistory() {
        FakeQueue queue = new FakeQueue();
        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        SearchParentActor.SearchSpec spec = new SearchParentActor.SearchSpec(
                "ai breakthroughs",
                "the-verge",
                "us",
                "technology",
                "en",
                "relevancy",
                10);

        ActorRef<UserSessionActor.Command> actor =
                testKit.spawn(UserSessionActor.create(
                        parentProbe.getRef(),
                        spec("ai breakthroughs"),
                        "sess-history",
                        queue));

        SearchParentActor.StartStream start =
                parentProbe.expectMessageClass(SearchParentActor.StartStream.class);
        assertEquals("sess-history", start.sessionId);
        SearchParentActor.SessionHistoryUpdated initialUpdate =
                parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);
        assertEquals("sess-history", initialUpdate.sessionId);
        assertEquals(1, initialUpdate.history.size());

        TestProbe<UserSessionActor.SearchHistorySnapshot> historyProbe =
                testKit.createTestProbe(UserSessionActor.SearchHistorySnapshot.class);
        actor.tell(new UserSessionActor.GetHistory(historyProbe.getRef()));
        UserSessionActor.SearchHistorySnapshot snapshot = historyProbe.receiveMessage();

        assertEquals(1, snapshot.history.size());
        assertEquals("ai breakthroughs", snapshot.history.get(0).query);
        assertTrue(queue.events.stream().anyMatch(event -> event.contains("event: history")));
    }

    @Test
    void recordSearchAppendsHistory() {
        FakeQueue queue = new FakeQueue();
        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<UserSessionActor.Command> actor =
                testKit.spawn(UserSessionActor.create(
                        parentProbe.getRef(),
                        spec("climate"),
                        "sess-append",
                        queue));

        parentProbe.expectMessageClass(SearchParentActor.StartStream.class);
        parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);

        SearchParentActor.SearchSpec followUp = new SearchParentActor.SearchSpec(
                "economy",
                "bbc-news",
                "gb",
                "business",
                "en",
                "publishedAt",
                10);

        actor.tell(new UserSessionActor.RecordSearch(followUp));
        SearchParentActor.SessionHistoryUpdated secondUpdate =
                parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);
        assertEquals(2, secondUpdate.history.size());

        TestProbe<UserSessionActor.SearchHistorySnapshot> historyProbe =
                testKit.createTestProbe(UserSessionActor.SearchHistorySnapshot.class);
        actor.tell(new UserSessionActor.GetHistory(historyProbe.getRef()));
        UserSessionActor.SearchHistorySnapshot snapshot = historyProbe.receiveMessage();

        assertEquals(2, snapshot.history.size());
        assertEquals("economy", snapshot.history.get(1).query);
        long historyEvents = queue.events.stream().filter(event -> event.contains("event: history")).count();
        assertTrue(historyEvents >= 2, "expected history SSE to be emitted for each change");
    }

    @Test
    void recordSearchIgnoresNullSpecs() {
        FakeQueue queue = new FakeQueue();
        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<UserSessionActor.Command> actor =
                testKit.spawn(UserSessionActor.create(
                        parentProbe.getRef(),
                        spec("init"),
                        "sess-null",
                        queue));

        parentProbe.expectMessageClass(SearchParentActor.StartStream.class);
        parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);

        actor.tell(new UserSessionActor.RecordSearch(null));
        parentProbe.expectNoMessage(Duration.ofMillis(200));

        TestProbe<UserSessionActor.SearchHistorySnapshot> historyProbe =
                testKit.createTestProbe(UserSessionActor.SearchHistorySnapshot.class);
        actor.tell(new UserSessionActor.GetHistory(historyProbe.getRef()));
        UserSessionActor.SearchHistorySnapshot snapshot = historyProbe.receiveMessage();
        assertEquals(1, snapshot.history.size());
    }

    @Test
    void historyIsTrimmedToTenEntries() {
        FakeQueue queue = new FakeQueue();
        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<UserSessionActor.Command> actor =
                testKit.spawn(UserSessionActor.create(
                        parentProbe.getRef(),
                        spec("seed"),
                        "sess-cap",
                        queue));

        parentProbe.expectMessageClass(SearchParentActor.StartStream.class);
        parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);

        for (int i = 0; i < 12; i++) {
            actor.tell(new UserSessionActor.RecordSearch(spec("q" + i)));
            parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);
        }

        TestProbe<UserSessionActor.SearchHistorySnapshot> historyProbe =
                testKit.createTestProbe(UserSessionActor.SearchHistorySnapshot.class);
        actor.tell(new UserSessionActor.GetHistory(historyProbe.getRef()));
        UserSessionActor.SearchHistorySnapshot snapshot = historyProbe.receiveMessage();

        assertEquals(10, snapshot.history.size());
        assertEquals("q2", snapshot.history.get(0).query);
        assertEquals("q11", snapshot.history.get(snapshot.history.size() - 1).query);
    }

    @Test
    void stopMessageTerminatesActor() {
        FakeQueue queue = new FakeQueue();
        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);
        ActorRef<UserSessionActor.Command> actor =
                testKit.spawn(UserSessionActor.create(
                        parentProbe.getRef(),
                        spec("stop"),
                        "sess-stop",
                        queue));

        parentProbe.expectMessageClass(SearchParentActor.StartStream.class);
        parentProbe.expectMessageClass(SearchParentActor.SessionHistoryUpdated.class);

        actor.tell(new UserSessionActor.Stop());
        parentProbe.expectNoMessage(Duration.ofMillis(200));
    }

    @Test
    void historyEntryFromNullSpecProducesPlaceholder() {
        UserSessionActor.HistoryEntry entry =
                UserSessionActor.HistoryEntry.fromSpec(null);
        assertNull(entry.query);
        assertNull(entry.sources);
        assertTrue(entry.timestamp > 0);
    }
}

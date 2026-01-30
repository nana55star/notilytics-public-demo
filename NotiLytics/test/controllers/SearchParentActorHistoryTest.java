package controllers;

import actors.search.SearchParentActor;
import actors.UserSessionActor;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.util.ByteString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import play.mvc.Result;
import services.NewsService;
import services.ReadabilityService;
import services.SentimentService;
import services.SourceProfileService;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class SearchParentActorHistoryTest {

    private static final ActorTestKit testKit = ActorTestKit.create();

    @AfterAll
    static void shutdown() {
        testKit.shutdownTestKit();
    }

    private ActorRef<SearchParentActor.Command> spawnParent() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService sources = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);
        return testKit.spawn(SearchParentActor.create(news, sentiment, sources, readability));
    }

    @Test
    void fetchHistoryUsesCache() throws Exception {
        ActorRef<SearchParentActor.Command> parent = spawnParent();
        List<UserSessionActor.HistoryEntry> entries = List.of(
                new UserSessionActor.HistoryEntry(
                        "ai", "cnn", "us", "technology", "en", "relevancy", System.currentTimeMillis())
        );

        parent.tell(new SearchParentActor.SessionHistoryUpdated("cached-session", entries));

        TestProbe<Result> probe = testKit.createTestProbe(Result.class);
        parent.tell(new SearchParentActor.FetchSessionHistory("cached-session", probe.getRef()));
        Result result = probe.receiveMessage();
        assertEquals(200, result.status());

        Materializer mat = SystemMaterializer.get(testKit.system()).materializer();
        ByteString data = result.body().consumeData(mat).toCompletableFuture().get(2, TimeUnit.SECONDS);
        String json = data.utf8String();
        assertTrue(json.contains("cached-session"));
        assertTrue(json.contains("\"history\""));
    }

    @Test
    void fetchHistoryMissingSessionReturns404() {
        ActorRef<SearchParentActor.Command> parent = spawnParent();
        TestProbe<Result> probe = testKit.createTestProbe(Result.class);

        parent.tell(new SearchParentActor.FetchSessionHistory("missing", probe.getRef()));
        Result result = probe.receiveMessage();
        assertEquals(404, result.status());
    }

    @Test
    void fetchHistoryUnknownSession_noCache_returns404() {
        ActorRef<SearchParentActor.Command> parent = spawnParent();
        TestProbe<Result> probe = testKit.createTestProbe(Result.class);
        parent.tell(new SearchParentActor.FetchSessionHistory("unknown", probe.getRef()));
        Result result = probe.receiveMessage();
        assertEquals(404, result.status());
    }

    @Test
    void sessionHistoryUpdated_thenFetchServesFromCache() throws Exception {
        ActorRef<SearchParentActor.Command> parent = spawnParent();
        List<UserSessionActor.HistoryEntry> entries = List.of(
                new UserSessionActor.HistoryEntry(
                        "q-one", "bbc", "gb", "business", "en", "popularity", System.currentTimeMillis())
        );

        parent.tell(new SearchParentActor.SessionHistoryUpdated("cached-session", entries));

        TestProbe<Result> probe = testKit.createTestProbe(Result.class);
        parent.tell(new SearchParentActor.FetchSessionHistory("cached-session", probe.getRef()));
        Result result = probe.receiveMessage();
        assertEquals(200, result.status());

        Materializer mat = SystemMaterializer.get(testKit.system()).materializer();
        String body = result.body().consumeData(mat).toCompletableFuture().get(2, TimeUnit.SECONDS).utf8String();
        assertTrue(body.contains("\"sessionId\":\"cached-session\""));
        assertTrue(body.contains("\"history\""));

        // exercise SessionClosed branch followed by another fetch (should still use cached snapshot)
        parent.tell(new SearchParentActor.SessionClosed("cached-session"));
        parent.tell(new SearchParentActor.FetchSessionHistory("cached-session", probe.getRef()));
        Result cached = probe.receiveMessage();
        assertEquals(200, cached.status());
    }
}

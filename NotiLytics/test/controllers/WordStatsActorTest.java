package controllers;

import actors.search.SearchParentActor;
import actors.WordStatsActor;
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJunitResource;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import play.test.Helpers;
import services.NewsResponse;
import services.NewsService;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pekko TestKit coverage for the SearchController Word Stats actor.
 *
 * Verifies happy-path aggregation, validation failures, upstream errors,
 * and JSON parse failures
 *
 * @author Khashayar Zardoui
 */
public class WordStatsActorTest {

    @ClassRule
    public static final TestKitJunitResource testKit = new TestKitJunitResource();

    @Test
    public void happyPath_countsWords() {
        NewsService news = mock(NewsService.class);
        String body = "{ \"status\":\"ok\", \"articles\":[" +
                "{\"description\":\"Hello world hello\"}" +
                "]}";
        when(news.searchEverything(eq("hello"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<WordStatsActor.Command> actor =
                testKit.spawn(WordStatsActor.create(news, parentProbe.ref()));

        Http.Request request = Helpers.fakeRequest("GET", "/api/wordstats?q=hello").build();
        ActorRef<Result> replyTo = testKit.createTestProbe(Result.class).ref();

        actor.tell(new WordStatsActor.HandleRequest(request, replyTo));

        SearchParentActor.WordStatsCompleted msg =
                parentProbe.expectMessageClass(SearchParentActor.WordStatsCompleted.class);

        assertEquals(Results.ok().status(), msg.result.status());
        String json = Helpers.contentAsString(msg.result);
        assertTrue(json.contains("\"word\":\"hello\""));
        assertTrue(json.contains("\"count\":2"));
    }

    @Test
    public void missingQuery_returns400() {
        NewsService news = mock(NewsService.class);

        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<WordStatsActor.Command> actor =
                testKit.spawn(WordStatsActor.create(news, parentProbe.ref()));

        Http.Request request = Helpers.fakeRequest("GET", "/api/wordstats").build();
        ActorRef<Result> replyTo = testKit.createTestProbe(Result.class).ref();

        actor.tell(new WordStatsActor.HandleRequest(request, replyTo));

        SearchParentActor.WordStatsCompleted msg =
                parentProbe.expectMessageClass(SearchParentActor.WordStatsCompleted.class);

        assertEquals(400, msg.result.status());
        assertTrue(Helpers.contentAsString(msg.result).contains("Missing required parameter 'q'"));
        verify(news, never()).searchEverything(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void upstreamErrorIsPropagated() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(500, "{\"error\":\"boom\"}")));

        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<WordStatsActor.Command> actor =
                testKit.spawn(WordStatsActor.create(news, parentProbe.ref()));

        Http.Request request = Helpers.fakeRequest("GET", "/api/wordstats?q=ai").build();
        ActorRef<Result> replyTo = testKit.createTestProbe(Result.class).ref();

        actor.tell(new WordStatsActor.HandleRequest(request, replyTo));

        SearchParentActor.WordStatsCompleted msg =
                parentProbe.expectMessageClass(SearchParentActor.WordStatsCompleted.class);

        assertEquals(500, msg.result.status());
    }

    @Test
    public void parseError_returns500() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{")));

        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<WordStatsActor.Command> actor =
                testKit.spawn(WordStatsActor.create(news, parentProbe.ref()));

        Http.Request request = Helpers.fakeRequest("GET", "/api/wordstats?q=oopsy").build();
        ActorRef<Result> replyTo = testKit.createTestProbe(Result.class).ref();

        actor.tell(new WordStatsActor.HandleRequest(request, replyTo));

        SearchParentActor.WordStatsCompleted msg =
                parentProbe.expectMessageClass(SearchParentActor.WordStatsCompleted.class);

        assertEquals(500, msg.result.status());
        assertTrue(Helpers.contentAsString(msg.result).contains("Failed to compute word stats"));
    }

    @Test
    public void forwardsLanguageParameter() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq("bonjour"), eq(""), eq(""), eq(""), eq("fr"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200,
                        "{\"status\":\"ok\",\"articles\":[]}")));

        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<WordStatsActor.Command> actor =
                testKit.spawn(WordStatsActor.create(news, parentProbe.ref()));

        Http.Request request = Helpers.fakeRequest("GET", "/api/wordstats?q=bonjour&lang=fr").build();
        ActorRef<Result> replyTo = testKit.createTestProbe(Result.class).ref();

        actor.tell(new WordStatsActor.HandleRequest(request, replyTo));

        parentProbe.expectMessageClass(SearchParentActor.WordStatsCompleted.class);
        verify(news).searchEverything(eq("bonjour"), eq(""), eq(""), eq(""), eq("fr"), eq("relevancy"), eq("50"));
    }

    @Test
    public void nullNewsResponse_returnsInternalServerError() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null)); // simulate failure returning null

        TestProbe<SearchParentActor.Command> parentProbe =
                testKit.createTestProbe(SearchParentActor.Command.class);

        ActorRef<WordStatsActor.Command> actor =
                testKit.spawn(WordStatsActor.create(news, parentProbe.ref()));

        Http.Request request = Helpers.fakeRequest("GET", "/api/wordstats?q=ai").build();
        ActorRef<Result> replyTo = testKit.createTestProbe(Result.class).ref();

        actor.tell(new WordStatsActor.HandleRequest(request, replyTo));

        SearchParentActor.WordStatsCompleted completed =
                parentProbe.expectMessageClass(SearchParentActor.WordStatsCompleted.class);
        assertEquals(500, completed.result.status());
        assertTrue(Helpers.contentAsString(completed.result).contains("Failed to compute word stats"));
    }
}

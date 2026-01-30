package controllers;

import actors.search.SearchParentActor;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import services.NewsResponse;
import services.NewsService;
import services.ReadabilityService;
import services.SentimentService;
import services.SourceProfileService;
import models.ReadabilityMetrics;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ReadabilityActorTest {

    private static final ActorTestKit testKit = ActorTestKit.create();

    @AfterAll
    static void shutdownKit() {
        testKit.shutdownTestKit();
    }

    @Test
    void computeReadability_happyPathReturnsOkPayload() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        String body = "{\"status\":\"ok\",\"articles\": ["
                + "{\"description\":\"Simple text.\"} ]}";
        when(news.searchEverything(eq("climate"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        List<ReadabilityMetrics> items = List.of(new ReadabilityMetrics(90.0, 4.0, 12, 1, 15));
        ReadabilityService.Result bundle = new ReadabilityService.Result(items, 90.0, 4.0);
        when(readability.bundleForArticles(anyList())).thenReturn(bundle);

        ActorRef<SearchParentActor.Command> parent = testKit.spawn(
                SearchParentActor.create(news, sentiment, profiles, readability)
        );

        var probe = testKit.createTestProbe(Result.class);
        Http.Request request = Helpers.fakeRequest("GET", "/api/readability?query=climate").build();
        parent.tell(new SearchParentActor.ComputeReadability(request, probe.ref()));

        Result result = probe.receiveMessage();
        assertEquals(200, result.status());

        JsonNode json = Json.parse(Helpers.contentAsString(result));
        assertEquals("climate", json.path("query").asText());
        assertEquals(90.0, json.path("averageReadingEase").asDouble());
        assertEquals(4.0, json.path("averageGradeLevel").asDouble());
        assertEquals(1, json.path("items").size());
    }

    @Test
    void computeReadability_failureFromNewsServiceReturns500() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        CompletableFuture<NewsResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        when(news.searchEverything(eq("climate"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(failed);

        ActorRef<SearchParentActor.Command> parent = testKit.spawn(
                SearchParentActor.create(news, sentiment, profiles, readability)
        );

        var probe = testKit.createTestProbe(Result.class);
        Http.Request request = Helpers.fakeRequest("GET", "/api/readability?query=climate").build();
        parent.tell(new SearchParentActor.ComputeReadability(request, probe.ref()));

        Result result = probe.receiveMessage();
        assertEquals(500, result.status());
        JsonNode json = Json.parse(Helpers.contentAsString(result));
        assertEquals("Failed to compute readability", json.path("error").asText());
    }

    @Test
    void computeReadability_propagatesUpstreamStatusForErrors() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        when(news.searchEverything(eq("climate"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(401, "{\"error\":\"unauthorized\"}")));

        ActorRef<SearchParentActor.Command> parent = testKit.spawn(
                SearchParentActor.create(news, sentiment, profiles, readability)
        );

        var probe = testKit.createTestProbe(Result.class);
        Http.Request request = Helpers.fakeRequest("GET", "/api/readability?query=climate").build();
        parent.tell(new SearchParentActor.ComputeReadability(request, probe.ref()));

        Result result = probe.receiveMessage();
        assertEquals(401, result.status());
        JsonNode json = Json.parse(Helpers.contentAsString(result));
        assertEquals("Failed to fetch articles", json.path("error").asText());
    }
}

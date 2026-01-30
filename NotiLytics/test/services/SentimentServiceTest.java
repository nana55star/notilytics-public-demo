package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SentimentService}.
 * Verifies enrichment on 200 and pass-through on non-200.
 */
public class SentimentServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void searchEverythingWithSentiment_status200_enrichesBody() throws Exception {
        // Arrange: NewsService mock returns a minimal NewsAPI-like payload with 1 article.
        NewsService news = mock(NewsService.class);

        String body = "{\n" +
                "  \"status\": \"ok\",\n" +
                "  \"totalResults\": 1,\n" +
                "  \"articles\": [ { \"title\":\"t0\", \"description\":\"happy happy happy sad\" } ]\n" +
                "}";

        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        SentimentService svc = new SentimentService(news);

        // Act
        CompletionStage<NewsResponse> stage =
                svc.searchEverythingWithSentiment("q", "src", "ca", "business", "en", "relevancy", "30");
        NewsResponse out = stage.toCompletableFuture().join();

        // Assert: still 200; body is enriched (per-article + overall)
        assertEquals(200, out.status);
        JsonNode json = MAPPER.readTree(out.body);
        assertEquals("ok", json.get("status").asText());
        assertEquals(":-)", json.get("articles").get(0).get("sentiment").asText());
        assertEquals(":-)", json.get("overallSentiment").asText());
    }

    @Test
    void searchEverythingWithSentiment_non200_passesBodyUnchanged() {
        // Arrange
        NewsService news = mock(NewsService.class);
        String rateLimited = "{\"status\":\"error\",\"code\":\"rateLimited\"}";
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(429, rateLimited)));

        SentimentService svc = new SentimentService(news);

        // Act
        NewsResponse out = svc.searchEverythingWithSentiment("", "", "", "", "", "", "")
                .toCompletableFuture().join();

        // Assert
        assertEquals(429, out.status);
        assertEquals(rateLimited, out.body);
    }

    /**
     * Test with null pageSize - should default to "20".
     */
    @Test
    void searchEverythingWithSentiment_nullPageSize_defaultsTo20() {
        // Arrange
        NewsService news = mock(NewsService.class);
        String responseBody = "{\"status\":\"ok\",\"articles\":[]}";
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), eq("20")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, responseBody)));

        SentimentService svc = new SentimentService(news);

        // Act
        svc.searchEverythingWithSentiment("AI", "", "", "", "en", "relevancy", null)
                .toCompletableFuture().join();

        // Assert - verify it called with "20"
        verify(news).searchEverything(eq("AI"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("20"));
    }

    /**
     * Test with blank pageSize - should default to "20".
     */
    @Test
    void searchEverythingWithSentiment_blankPageSize_defaultsTo20() {
        // Arrange
        NewsService news = mock(NewsService.class);
        String responseBody = "{\"status\":\"ok\",\"articles\":[]}";
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), eq("20")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, responseBody)));

        SentimentService svc = new SentimentService(news);

        // Act
        svc.searchEverythingWithSentiment("AI", "", "", "", "en", "relevancy", "  ")
                .toCompletableFuture().join();

        // Assert - verify it called with "20"
        verify(news).searchEverything(eq("AI"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("20"));
    }
}

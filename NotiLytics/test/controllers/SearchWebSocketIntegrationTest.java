package controllers;

import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import play.Application;
import play.inject.Bindings;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Result;
import play.test.Helpers;
import play.test.TestServer;
import services.NewsResponse;
import services.NewsService;
import services.SentimentService;
import services.SourceProfileService;
import services.ReadabilityService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class SearchWebSocketIntegrationTest {

    @Test
    void wsStream_pushesInitEvent() throws Exception {
        NewsService newsMock = Mockito.mock(NewsService.class);
        SentimentService sentimentMock = Mockito.mock(SentimentService.class);
        SourceProfileService sourceProfiles = Mockito.mock(SourceProfileService.class);
        ReadabilityService readability = new ReadabilityService();

        String body = "{\"status\":\"ok\",\"articles\":[{\"title\":\"Hello WS\",\"url\":\"http://example.com/ws\"}]}";
        when(sentimentMock.searchEverythingWithSentiment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));
        when(newsMock.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application app = new GuiceApplicationBuilder()
                .overrides(
                        Bindings.bind(NewsService.class).toInstance(newsMock),
                        Bindings.bind(SentimentService.class).toInstance(sentimentMock),
                        Bindings.bind(SourceProfileService.class).toInstance(sourceProfiles),
                        Bindings.bind(ReadabilityService.class).toInstance(readability)
                )
                .build();

        TestServer server = Helpers.testServer(0, app);
        Helpers.running(server, () -> {
            int port = server.getRunningHttpPort().orElseThrow();
            URI uri = URI.create("ws://localhost:" + port + "/ws/stream?q=ws-test&sessionId=it-ws");

            LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            HttpClient httpClient = HttpClient.newHttpClient();
            java.util.concurrent.CompletableFuture<WebSocket> socketFuture = httpClient.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    WebSocket.Listener.super.onOpen(webSocket);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    messages.offer(data.toString());
                    if (!done.isDone()) done.complete(null);
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    done.completeExceptionally(error);
                }
            });

            try {
                done.get(8, TimeUnit.SECONDS);
                String first = null;
                for (int i = 0; i < 3 && first == null; i++) {
                    String candidate = messages.poll(3, TimeUnit.SECONDS);
                    if (candidate != null && candidate.contains("init")) {
                        first = candidate;
                    }
                }
                assertNotNull(first, "expected at least one init WS message");
                assertTrue(first.contains("Hello WS"), "payload should contain article title");

                HttpRequest historyRequest = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/session-history?sessionId=it-ws"))
                        .GET()
                        .build();
                HttpResponse<String> historyResponse = httpClient.send(historyRequest, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, historyResponse.statusCode());
                assertTrue(historyResponse.body().contains("\"history\""));
                assertTrue(historyResponse.body().contains("ws-test"));
            } catch (Exception ex) {
                fail("WebSocket stream did not deliver init event: " + ex.getMessage());
            } finally {
                socketFuture.thenCompose(ws -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"))
                        .toCompletableFuture()
                        .join();
            }
        });
    }
}

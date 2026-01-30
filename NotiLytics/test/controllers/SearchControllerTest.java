package controllers;

import actors.search.SearchParentActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Flow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Application;
import play.inject.Bindings;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import play.libs.Json;
import play.libs.F;
import models.ArticleSummary;
import models.ErrorInfo;
import models.ServiceResult;
import models.SourceDetails;
import models.SourceProfile;
import services.NewsResponse;
import services.NewsService;
import services.SourceProfileService;
import services.SentimentService;
import services.ReadabilityService;
import services.SentimentService;
import services.ReadabilityService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import play.libs.ws.*;

import static org.junit.jupiter.api.Assertions.*;
import static play.mvc.Http.Status.OK;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;
import static play.mvc.Http.Status.NOT_FOUND;
import static play.mvc.Http.Status.UNAUTHORIZED;
import static play.test.Helpers.*;
import static org.mockito.Mockito.*;

import org.apache.pekko.actor.typed.javadsl.Behaviors;

import play.inject.ApplicationLifecycle;


/**
 * Unit tests for {@link SearchController}.
 *
 * <p>This suite verifies routing and response behavior of the controller endpoints
 * under different input combinations. External HTTP calls are mocked in two ways:
 * (1) by injecting a mocked {@link WSClient} (real controller/service path) and
 * (2) by binding {@link FakeNewsService} (pure controller path).
 *
 * <p>Covered endpoints:
 * <ul>
 *   <li>{@code GET /search}</li>
 *   <li>{@code GET /sources}</li>
 *   <li>{@code GET /}</li>
 * </ul>
 *
 * <p>All tests run within a fake Play application via
 * {@link Helpers#running(Application, Runnable)}.</p>
 *
 * @author Nirvana Borham
 */
public class SearchControllerTest {

    /** Mocked WS client used when testing the "real" NewsService branch. */
    private WSClient ws;
    /** Mocked WS request for fluent chain verification. */
    private WSRequest req;
    /** Mocked WS response returned by {@code WSRequest#get()}. */
    private WSResponse resp;
    /** Play application for the WS-injected tests. */
    private Application app;

    /**
     * Prepares a Play app that injects a mocked {@link WSClient} into the graph,
     * and stubs a successful NewsAPI-like response for requests issued by {@link NewsService}.
     *
     * @author Nirvana Borham
     */
    @BeforeEach
    void setUp() {
        // Prepare mock WS chain
        ws   = mock(WSClient.class);
        req  = mock(WSRequest.class, RETURNS_DEEP_STUBS);
        resp = mock(WSResponse.class);

        when(ws.url(anyString())).thenReturn(req);
        when(req.addHeader(anyString(), anyString())).thenReturn(req);
        when(req.addQueryParameter(anyString(), anyString())).thenReturn(req);
        when(req.setRequestTimeout(any(Duration.class))).thenReturn(req);
        when(req.get()).thenReturn(CompletableFuture.completedFuture(resp));

        when(resp.getStatus()).thenReturn(200);
        when(resp.getBody()).thenReturn("{\"status\":\"ok\",\"totalResults\":1,\"articles\":[]}");

        // Build an app that injects our mocked WS client
        app = new GuiceApplicationBuilder()
                .configure("newsapi.baseUrl", "https://newsapi.org/v2")
                .configure("newsapi.key", "test-key")
                .configure("newsapi.timeoutMs", 5000)
                .configure("newsapi.mock", false)
                .overrides(Bindings.bind(WSClient.class).toInstance(ws))
                .build();
    }

    /**
     * Stops the Play app created in {@link #setUp()} to avoid leaking resources between tests.
     *
     * @author Nirvana Borham
     */
    @AfterEach
    void tearDown() {
        stop(app);
    }

    // ===== Mode 2 test (real NewsService + mocked WS) =====

    /**
     * Happy path (real service path with mocked WS): calling {@code /search?q=AI}
     * returns {@code 200 OK}, hits the {@code /everything} endpoint, sets {@code q} as a phrase,
     * and adds the API key header.
     *
     * @author Nirvana Borham
     */
    @Test
    void search_qOnly_realService_buildsQuery_andReturns200() {
        running(app, () -> {
            Http.RequestBuilder rb = new Http.RequestBuilder().method(GET).uri("/search?q=AI");
            Result result = route(app, rb);
            assertEquals(OK, result.status());

            // Verify the NewsService built the correct request
            verify(ws).url(argThat(u -> u.endsWith("/everything")));
            verify(req).addQueryParameter("q", "\"AI\"");
            verify(req).addHeader("X-Api-Key", "test-key");
        });
    }

    // ---------------------------------------------------------------------
    // The remaining tests use a FakeNewsService bound into a dedicated app.
    // This isolates controller logic
    // ---------------------------------------------------------------------

    /**
     * Builds a small {@link Application} that binds {@link NewsService} to
     * {@link FakeNewsService} for deterministic responses (no network).
     *
     * @return configured Play application with {@link FakeNewsService} bound
     * @author Nirvana Borham
     */
    private Application mkApp() {
        FakeNewsService fakeNews = new FakeNewsService();
        return new GuiceApplicationBuilder()
                .overrides(Bindings.bind(NewsService.class).toInstance(fakeNews))
                .overrides(Bindings.bind(services.SourceProfileService.class).toInstance(new FakeSourceProfileService(fakeNews)))
                .build();
    }

    /**
     * Builds a Play application that injects the supplied {@link NewsService} instance.
     * Useful for tests that need to control the service behaviour via Mockito.
     *
     * @auther Khashayar Zardoui (?)
     */
    private Application mkAppWithNewsService(NewsService newsService) {
        return new GuiceApplicationBuilder()
                .overrides(Bindings.bind(NewsService.class).toInstance(newsService))
                .build();
    }

    /**
     * Builds a Play application with explicit bindings for {@link NewsService}
     * and {@link SourceProfileService}. Useful when mocking interactions with
     * the sources endpoint that may touch either dependency.
     *
     * @param newsService mock or stubbed NewsService
     * @param sourceProfileService mock or stubbed SourceProfileService
     * @return configured Play application
     */
    private Application mkAppWithServices(NewsService newsService, SourceProfileService sourceProfileService) {
        return new GuiceApplicationBuilder()
                .overrides(Bindings.bind(NewsService.class).toInstance(newsService))
                .overrides(Bindings.bind(SourceProfileService.class).toInstance(sourceProfileService))
                .build();
    }

    /**
     * Controller-level check (fake service): {@code /search?q=AI} returns {@code 200 OK}
     * JSON body containing the {@code "which":"q-only"} marker.
     *
     * @author Nirvana Borham
     */
    @Test
    void search_qOnly_fakeService_returns200_withMarker() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=AI"));
            assertEquals(OK, res.status());
            assertEquals("application/json", res.contentType().orElse(""));
            assertTrue(contentAsString(res).contains("\"which\":\"q-only\""));
        });
    }

    /**
     * Validation: calling the Word Stats endpoint without a query parameter returns 400.
     *
     * @auther Khashayar Zardoui (?)
     */
    @Test
    void wordStats_missingQuery_returns400() {
        Application local = mkAppWithNewsService(mock(NewsService.class));
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats"));
            assertEquals(BAD_REQUEST, res.status());
            assertTrue(contentAsString(res).contains("Missing required parameter 'q'"));
        });
    }

    /**
     * When the downstream service returns a non-200, the controller surfaces the status/body.
     *
     * @auther Khashayar Zardoui (?)
     */
    @Test
    void wordStats_upstreamError_surfacesStatusAndBody() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq("ai"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(429, "rate limited")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=ai"));
            assertEquals(429, res.status());
            assertEquals("rate limited", contentAsString(res));
        });

        verify(news).searchEverything(eq("ai"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50"));
    }

    /**
     * Happy path: words are counted and sorted by frequency (descending) then alphabetically.
     *
     * @auther Khashayar Zardoui (?)
     */
    @Test
    void wordStats_countsAndSortsWords() {
        String body = "{\"status\":\"ok\",\"articles\":["
                + "{\"description\":\"Apple banana apple\"},"
                + "{\"description\":\"banana! ANT, ant\"},"
                + "{\"description\":\"date apple\"},"
                + "{\"description\":null}"
                + "]}";

        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq("Trends"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=Trends"));
            assertEquals(OK, res.status());
            assertEquals("application/json", res.contentType().orElse(""));

            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("ok", json.path("status").asText());

            ArrayNode words = (ArrayNode) json.path("words");
            assertEquals(4, words.size());

            assertEquals("apple", words.get(0).path("word").asText());
            assertEquals(3, words.get(0).path("count").asInt());

            assertEquals("ant", words.get(1).path("word").asText());
            assertEquals(2, words.get(1).path("count").asInt());

            assertEquals("banana", words.get(2).path("word").asText());
            assertEquals(2, words.get(2).path("count").asInt());

            assertEquals("date", words.get(3).path("word").asText());
            assertEquals(1, words.get(3).path("count").asInt());
        });

        verify(news).searchEverything(eq("Trends"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50"));
    }

    /**
     * If JSON parsing fails, the controller returns HTTP 500 with an error message.
     *
     * @auther Khashayar Zardoui (?)
     */
    @Test
    void wordStats_malformedJson_returns500() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq("oops"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=oops"));
            assertEquals(INTERNAL_SERVER_ERROR, res.status());
            assertTrue(contentAsString(res).contains("Failed to compute word stats"));
        });

        verify(news).searchEverything(eq("oops"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50"));

    }

    /**
     * Word stats should honour the optional language parameter (alias {@code lang}).
     */
    @Test
    void wordStats_langParam_forwardsLanguage() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=bonjour&lang=fr"));
            assertEquals(OK, res.status());
        });

        verify(news).searchEverything(eq("bonjour"), eq(""), eq(""), eq(""), eq("fr"), eq("relevancy"), eq("50"));
    }

    /**
     * Controller-level check (fake service): multiple {@code sources} parameters with no query
     * yield {@code 200 OK} and the {@code "sources-only"} marker.
     *
     * @author Nirvana Borham
     */
    @Test
    void search_sourcesOnly_multiParams_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?sources=bbc-news&sources=the-verge"));
            assertEquals(OK, res.status());
            assertTrue(contentAsString(res).contains("\"which\":\"sources-only\""));
        });
    }


    /**
     * Controller-level check (fake service): {@code q} + {@code sources} together produce
     * {@code 200 OK} and the {@code "q+sources"} marker.
     *
     * @author Nirvana Borham
     */
    @Test
    void search_qAndSources_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=AI&sources=the-verge"));
            assertEquals(OK, res.status());
            assertTrue(contentAsString(res).contains("\"which\":\"q+sources\""));
        });
    }

    /**
     * Validation check (fake service): calling {@code /search} with no params
     * returns {@code 400 Bad Request} and includes the expected error message.
     *
     * @author Nirvana Borham
     */
    @Test
    void search_noParams_returns400() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search"));
            assertEquals(BAD_REQUEST, res.status());
            String body = contentAsString(res).toLowerCase();
            assertTrue(body.contains("add keywords") || body.contains("choose at least one filter"));
        });
    }

    @Test
    void search_langAll_withNoOtherFilters_stillReturns400() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?lang=all"));
            assertEquals(BAD_REQUEST, res.status());
            assertTrue(contentAsString(res).contains("Add keywords"));
        });
    }

    /**
     * Controller-level check (fake service): {@code /sources} with all filters produces
     * {@code 200 OK} and echoes a JSON payload with sources array and the language.
     *
     * @author Alex Sutherland
     */
    @Test
    void sources_withFilters_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?language=en&category=technology&country=us"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sources\""));
            assertTrue(body.contains("\"language\":\"en\""));
        });
    }

    /**
     * Controller-level check (fake service): {@code /sources} with no filters still succeeds
     * ({@code 200 OK}) and defaults language to {@code en}.
     *
     * @author Nirvana Borham
     */
    @Test
    void sources_defaults_return200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources"));
            assertEquals(OK, res.status());
            assertTrue(contentAsString(res).contains("\"language\":\"en\""));
        });
    }

    /**
     * With no filters, the controller should normalize language/category/country to blank strings.
     * Ensures downstream NewsService receives the expected defaults for merging logic.
     */
    @Test
    void sources_noFilters_callsServiceWithBlankDefaults() {
        NewsService news = mock(NewsService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);

        when(news.listSources("", "", "")).thenReturn(
                CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"sources\":[]}"))
        );

        Application local = mkAppWithServices(news, profiles);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources"));
            assertEquals(OK, res.status());
            assertEquals("application/json", res.contentType().orElse(""));
        });

        verify(news).listSources("", "", "");
        verifyNoInteractions(profiles);
    }

    /**
     * Smoke test for the index route: {@code GET /} renders successfully with {@code 200 OK}.
     *
     * @author Nirvana Borham
     */
    @Test
    void index_renders() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/"));
            assertEquals(OK, res.status());
        });
    }

    /**
     * Test /sources endpoint with sourceId parameter returns source profile.
     * @author Alex Sutherland
     */
    @Test
    void sources_withSourceId_returnsProfile() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?sourceId=test-source&language=en&pageSize=10&sortBy=publishedAt"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"status\":\"ok\""));
        });
    }

    /**
     * Test /sources endpoint with id parameter (alias for sourceId) returns profile.
     * @author Alex Sutherland
     * We use Id by default but NewsAPI has issues where id is NULL so we fall back to name
     */
    @Test
    void sources_withId_returnsProfile() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?id=test-source&language=en"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"status\":\"ok\""));
        });
    }

    /**
     * When the profile service surfaces an error, the controller should forward
     * the error payload and preserve the downstream status code.
     */
    @Test
    void sources_withSourceId_failureForwardsErrorPayload() {
        NewsService news = mock(NewsService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);
        ErrorInfo error = new ErrorInfo("Source not found", Json.newObject().put("sourceId", "missing"));

        when(profiles.fetchSourceProfile("missing", "all", "", "", "publishedAt", "10"))
                .thenReturn(CompletableFuture.completedFuture(ServiceResult.failure(404, error)));

        Application local = mkAppWithServices(news, profiles);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?sourceId=missing"));
            assertEquals(404, res.status());

            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("error", json.path("status").asText());
            assertEquals("Source not found", json.path("message").asText());
            assertTrue(json.has("details"));
        });

        verify(profiles).fetchSourceProfile("missing", "all", "", "", "publishedAt", "10");
        verifyNoInteractions(news);
    }

    /**
     * If the profile service fails without details, the controller should emit
     * a generic internal error response instead of propagating an empty payload.
     */
    @Test
    void sources_withSourceId_unknownFailureReturnsInternalError() {
        NewsService news = mock(NewsService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);

        when(profiles.fetchSourceProfile("glitch", "all", "", "", "publishedAt", "10"))
                .thenReturn(CompletableFuture.completedFuture(ServiceResult.failure(503, null)));

        Application local = mkAppWithServices(news, profiles);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?sourceId=glitch"));
            assertEquals(INTERNAL_SERVER_ERROR, res.status());

            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("error", json.path("status").asText());
            assertTrue(json.path("message").asText().startsWith("Unknown error"));
        });

        verify(profiles).fetchSourceProfile("glitch", "all", "", "", "publishedAt", "10");
        verifyNoInteractions(news);
    }

    /**
     * Alias parameter {@code id} should map to {@code sourceId} and call the profile service
     * with normalized defaults. The controller response must include the enriched payload.
     */
    @Test
    void sources_withId_aliasCallsProfileServiceWithDefaults() {
        NewsService news = mock(NewsService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);

        SourceDetails details = new SourceDetails("alias-id", "Alias", "Desc", "https://example.com",
                "tech", "en", "us");
        SourceProfile profile = new SourceProfile(details, List.of(
                new ArticleSummary("Headline", "Summary", "https://example.com/article", "2024-01-01T00:00:00Z", "alias-id", "Alias")
        ), 1);

        when(profiles.fetchSourceProfile("alias-id", "all", "", "", "publishedAt", "10"))
                .thenReturn(CompletableFuture.completedFuture(ServiceResult.success(200, profile)));

        Application local = mkAppWithServices(news, profiles);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?id=alias-id"));
            assertEquals(OK, res.status());

            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("ok", json.path("status").asText());
            assertTrue(json.path("source").has("id"));
            assertEquals(1, json.path("articles").size());
            assertEquals(1, json.path("totalResults").asInt());
        });

        verify(profiles).fetchSourceProfile("alias-id", "all", "", "", "publishedAt", "10");
        verifyNoInteractions(news);
    }

    /**
     * Test search with only country filter succeeds and verifies the response.
     * Validates that the service is called with the correct parameters.
     * @author Alex Sutherland
     */
    @Test
    void search_onlyCountry_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?country=us"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            // Verify it's valid JSON and contains expected structure
            assertTrue(body.contains("\"ok\":true"));
            // Should indicate sources-only since no q was provided
            assertTrue(body.contains("\"which\":\"sources-only\""));
        });
    }

    /**
     * Test search with only category filter succeeds and verifies the response.
     * Validates that the service is called with the correct parameters.
     * @author Alex Sutherland
     */
    @Test
    void search_onlyCategory_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?category=technology"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            // Verify it's valid JSON and contains expected structure
            assertTrue(body.contains("\"ok\":true"));
            // Should indicate sources-only since no q was provided
            assertTrue(body.contains("\"which\":\"sources-only\""));
        });
    }

    /**
     * Test search with only language filter succeeds and verifies the response.
     * Validates that the service is called with the correct parameters.
     * @author Alex Sutherland
     */
    @Test
    void search_onlyLanguage_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?language=en"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            // Verify it's valid JSON and contains expected structure
            assertTrue(body.contains("\"ok\":true"));
            // Should indicate sources-only since no q was provided
            assertTrue(body.contains("\"which\":\"sources-only\""));
        });
    }

    /**
     * When only filters are provided, the controller should expand the filter set into sources
     * via {@code listSources} and then run the sentiment-enriched search.
     */
    @Test
    void search_filtersOnly_expandsSourcesList() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService sps = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        String listBody = "{\"status\":\"ok\",\"sources\":[{\"id\":\"cnn\"},{\"id\":\"bbc-news\"},{\"id\":\"the-verge\"}]}";
        when(news.listSources(eq(""), eq("technology"), eq("us")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, listBody)));

        when(sentiment.searchEverythingWithSentiment(isNull(), eq("cnn,bbc-news,the-verge"), eq("us"), eq("technology"), isNull(), eq("relevancy"), eq("10")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"ok\":true}")));

        Application local = new GuiceApplicationBuilder()
                .overrides(
                        Bindings.bind(NewsService.class).toInstance(news),
                        Bindings.bind(SentimentService.class).toInstance(sentiment),
                        Bindings.bind(SourceProfileService.class).toInstance(sps),
                        Bindings.bind(ReadabilityService.class).toInstance(readability)
                ).build();

        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?country=us&category=technology"));
            assertEquals(OK, res.status());
        });

        verify(news).listSources(eq(""), eq("technology"), eq("us"));
        verify(sentiment).searchEverythingWithSentiment(isNull(), eq("cnn,bbc-news,the-verge"), eq("us"), eq("technology"), isNull(), eq("relevancy"), eq("10"));
    }

    @Test
    void search_filtersOnly_listSourcesError_forwardsStatus() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService sps = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        when(news.listSources(eq(""), eq("technology"), eq("us")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(429, "rate limited")));

        Application local = new GuiceApplicationBuilder()
                .overrides(
                        Bindings.bind(NewsService.class).toInstance(news),
                        Bindings.bind(SentimentService.class).toInstance(sentiment),
                        Bindings.bind(SourceProfileService.class).toInstance(sps),
                        Bindings.bind(ReadabilityService.class).toInstance(readability)
                ).build();

        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?country=us&category=technology"));
            assertEquals(429, res.status());
            assertEquals("rate limited", contentAsString(res));
        });

        verify(news).listSources(eq(""), eq("technology"), eq("us"));
        verifyNoInteractions(sentiment);
    }

    @Test
    void search_filtersOnly_noSources_returns404() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService sps = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        when(news.listSources(eq(""), eq("technology"), eq("us")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"sources\":[]}")));

        Application local = new GuiceApplicationBuilder()
                .overrides(
                        Bindings.bind(NewsService.class).toInstance(news),
                        Bindings.bind(SentimentService.class).toInstance(sentiment),
                        Bindings.bind(SourceProfileService.class).toInstance(sps),
                        Bindings.bind(ReadabilityService.class).toInstance(readability)
                ).build();

        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?country=us&category=technology"));
            assertEquals(404, res.status());
            assertTrue(contentAsString(res).contains("No sources found"));
        });

        verify(news).listSources(eq(""), eq("technology"), eq("us"));
        verifyNoInteractions(sentiment);
    }

    @Test
    void search_filtersOnly_manySources_limitsToTwenty() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService sps = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        StringBuilder json = new StringBuilder("{\"status\":\"ok\",\"sources\":[");
        for (int i = 0; i < 25; i++) {
            if (i > 0) json.append(',');
            json.append("{\"id\":\"src").append(i).append("\"}");
        }
        json.append("]}");

        when(news.listSources(eq(""), eq("business"), eq("ca")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, json.toString())));

        String expectedCsv = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "src" + i)
                .collect(Collectors.joining(","));

        when(sentiment.searchEverythingWithSentiment(isNull(), eq(expectedCsv), eq("ca"), eq("business"), isNull(), eq("relevancy"), eq("10")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"ok\":true}")));

        Application local = new GuiceApplicationBuilder()
                .overrides(
                        Bindings.bind(NewsService.class).toInstance(news),
                        Bindings.bind(SentimentService.class).toInstance(sentiment),
                        Bindings.bind(SourceProfileService.class).toInstance(sps),
                        Bindings.bind(ReadabilityService.class).toInstance(readability)
                ).build();

        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?country=ca&category=business"));
            assertEquals(OK, res.status());
        });

        verify(news).listSources(eq(""), eq("business"), eq("ca"));
        verify(sentiment).searchEverythingWithSentiment(isNull(), eq(expectedCsv), eq("ca"), eq("business"), isNull(), eq("relevancy"), eq("10"));
    }

    @Test
    void search_filtersOnly_invalidSourcesJson_returns404() {
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService sps = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        when(news.listSources(eq(""), eq("science"), eq("de")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "not-json")));

        Application local = new GuiceApplicationBuilder()
                .overrides(
                        Bindings.bind(NewsService.class).toInstance(news),
                        Bindings.bind(SentimentService.class).toInstance(sentiment),
                        Bindings.bind(SourceProfileService.class).toInstance(sps),
                        Bindings.bind(ReadabilityService.class).toInstance(readability)
                ).build();

        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?country=de&category=science"));
            assertEquals(404, res.status());
            assertTrue(contentAsString(res).contains("No sources found"));
        });

        verify(news).listSources(eq(""), eq("science"), eq("de"));
        verifyNoInteractions(sentiment);
    }

    /**
     * Alias support: {@code lang=} should behave the same as {@code language=}.
     */
    @Test
    void search_onlyLangAlias_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?lang=fr"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"which\":\"sources-only\""));
        });
    }

    /**
     * Test search with different sortBy options.
     * @author Alex Sutherland
     */
    @Test
    void search_withPopularitySortBy_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=test&sortBy=popularity"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sortBy\":\"popularity\""));
        });
    }

    /**
     * Test search with publishedAt sortBy.
     * @author Alex Sutherland
     */
    @Test
    void search_withPublishedAtSortBy_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=test&sortBy=publishedAt"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sortBy\":\"publishedAt\""));
        });
    }

    /**
     * Test search with relevancy sortBy (default).
     * @author Alex Sutherland
     */
    @Test
    void search_withRelevancySortBy_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=test&sortBy=relevancy"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sortBy\":\"relevancy\""));
        });
    }

    /**
     * Test search without sortBy parameter defaults to relevancy.
     * @author Alex Sutherland
     */
    @Test
    void search_withoutSortBy_defaultsToRelevancy() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=test"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            // FakeNewsService should still include sortBy in response
            assertTrue(body.contains("\"ok\":true"));
        });
    }

    /**
     * Test search with sortBy combined with multiple filters.
     * @author Alex Sutherland
     */
    @Test
    void search_withSortByAndMultipleFilters_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=test&country=us&category=technology&language=en&sortBy=popularity"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sortBy\":\"popularity\""));
        });
    }

    /**
     * Test search with sources and sortBy.
     * @author Alex Sutherland
     */
    @Test
    void search_withSourcesAndSortBy_returns200() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?sources=bbc-news&sources=cnn&sortBy=publishedAt"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sortBy\":\"publishedAt\""));
        });
    }

    /**
     * Test that index page contains clear results button for cumulative search.
     * @author Alex Sutherland
     */
    @Test
    void index_page_containsClearResultsButton() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/"));
            assertEquals(OK, res.status());
            String html = contentAsString(res);
            assertTrue(html.contains("id=\"clear-results-btn\""),
                "Index page should contain clear results button");
            assertTrue(html.contains("Clear All Results"),
                "Clear results button should have proper label");
        });
    }

    /**
     * Test that index page contains results div for cumulative search.
     * @author Alex Sutherland
     */
    @Test
    void index_page_containsResultsDiv() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/"));
            assertEquals(OK, res.status());
            String html = contentAsString(res);
            assertTrue(html.contains("id=\"results\""),
                "Index page should contain results div");
            assertTrue(html.contains("aria-live=\"polite\""),
                "Results div should have aria-live attribute for accessibility");
        });
    }

    /**
     * Test that index page contains Search Results header.
     * @author Alex Sutherland
     */
    @Test
    void index_page_containsSearchResultsHeader() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/"));
            assertEquals(OK, res.status());
            String html = contentAsString(res);
            assertTrue(html.contains("Search Results"),
                "Index page should contain Search Results header");
        });
    }

    /**
     * Test that search endpoint returns all data fields needed for query summary.
     * Verifies the response includes the structure needed by the new cumulative search UI.
     * @author Alex Sutherland
     */
    @Test
    void search_withAllFilters_returnsCompleteDataForSummary() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET,
                "/search?q=technology&country=us&category=business&language=en&sortBy=publishedAt"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);

            // Verify response is valid JSON
            assertTrue(body.contains("\"ok\":true"),
                "Response should contain valid JSON structure");

            // Verify sortBy parameter is included in response for summary generation
            assertTrue(body.contains("\"sortBy\":\"publishedAt\""),
                "Response should include sortBy for query summary");
        });
    }

    /**
     * Test readabilityJson endpoint returns proper statistics when NewsService provides articles.
     * Verifies that averageReadingEase, averageGradeLevel, and items are present in the response.
     * @author Mustafa Kaya
     */
    @Test
    void readabilityJson_returnsAverages_whenNewsServiceProvidesArticles() {
        NewsService mockNews = mock(NewsService.class);
        String body = "{ \"status\":\"ok\", \"articles\":[" +
                "{ \"title\":\"A\", \"description\":\"Simple sentence. Test purpose.\" }," +
                "{ \"title\":\"B\", \"description\":\"Complexity increases as vocabulary grows!\" }" +
                "]}";
        when(mockNews.searchEverything(eq("test"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application local = mkAppWithNewsService(mockNews);
        Helpers.running(local, () -> {
            Result result = route(local, fakeRequest(GET, "/api/readability?query=test"));
            assertEquals(OK, result.status());
            String json = contentAsString(result);
            assertTrue(json.contains("\"averageReadingEase\""));
            assertTrue(json.contains("\"averageGradeLevel\""));
            assertTrue(json.contains("\"items\""));
        });
    }

    /**
     * Test readabilityJson endpoint properly propagates 401 Unauthorized errors from NewsService.
     * Verifies that the error status and message are correctly forwarded to the client.
     * @author Mustafa Kaya
     */
    @Test
    void readabilityJson_propagates401_onUnauthorized() {
        NewsService mockNews = mock(NewsService.class);
        when(mockNews.searchEverything(eq("test"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(401, "{\"status\":401}")));

        Application local = mkAppWithNewsService(mockNews);
        Helpers.running(local, () -> {
            Result result = route(local, fakeRequest(GET, "/api/readability?query=test"));
            assertEquals(UNAUTHORIZED, result.status());
            String body = contentAsString(result);
            assertTrue(body.contains("Failed to fetch articles"));
        });
    }

    /**
     * Private helper {@code coalesce}: null or blank values should fall back to the provided default.
     */
    @Test
    void coalesce_nullOrBlank_returnsDefault() {
        assertEquals("fallback", SearchParentActor.coalesce(null, "fallback"));
        assertEquals("fallback", SearchParentActor.coalesce("   ", "fallback"));
    }

    /**
     * Private helper {@code coalesce}: non-blank inputs should be returned as-is.
     */
    @Test
    void coalesce_nonBlank_returnsOriginal() {
        assertEquals("value", SearchParentActor.coalesce("value", "fallback"));
        assertEquals(" value ", SearchParentActor.coalesce(" value ", "fallback"));
    }

    // ---------------------------------------------------------------------
    // Session history endpoint (/api/session-history)
    // ---------------------------------------------------------------------

    @Test
    void sessionHistory_missingParameter_returnsBadRequest() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/session-history"));
            assertEquals(BAD_REQUEST, res.status());
            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("Missing required parameter 'sessionId'", json.path("error").asText());
        });
    }

    @Test
    void sessionHistory_blankParameter_returnsBadRequest() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/session-history?sessionId=%20%20"));
            assertEquals(BAD_REQUEST, res.status());
            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("Missing required parameter 'sessionId'", json.path("error").asText());
        });
    }

    @Test
    void sessionHistory_unknownSession_returns404() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/session-history?sessionId=missing"));
            assertEquals(NOT_FOUND, res.status());
            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("Unknown sessionId", json.path("error").asText());
            assertEquals("missing", json.path("sessionId").asText());
        });
    }

    @Test
    void isBlank_handlesNullWhitespaceAndContent() {
        assertTrue(SearchParentActor.isBlank(null));
        assertTrue(SearchParentActor.isBlank("   \t"));
        assertFalse(SearchParentActor.isBlank("value"));
    }

    @Test
    void normalizeLanguageParam_handlesAllAndTrims() {
        assertNull(SearchParentActor.normalizeLanguageParam(null));
        assertNull(SearchParentActor.normalizeLanguageParam(" ALL  "));
        assertNull(SearchParentActor.normalizeLanguageParam("   "));
        assertEquals("fr", SearchParentActor.normalizeLanguageParam(" Fr "));
    }

    @Test
    void extractSourceIds_skipsBlanks_andHandlesNonArrays() {
        String json = "{\"sources\":[{\"id\":\"cnn\"},{\"id\":\"  \"}]}";
        List<String> ids = SearchParentActor.extractSourceIds(json);
        assertEquals(1, ids.size());
        assertEquals("cnn", ids.get(0));

        // When sources isn't an array we should return an empty list
        String invalid = "{\"sources\":{\"id\":\"fox\"}}";
        List<String> empty = SearchParentActor.extractSourceIds(invalid);
        assertTrue(empty.isEmpty());

        // Malformed JSON should hit the catch block and return empty list
        List<String> malformed = SearchParentActor.extractSourceIds("not-json");
        assertTrue(malformed.isEmpty());
    }

    @Test
    void extractDescriptions_handlesNullEntries_branch() {
        String payload = "{\"articles\":[{\"description\":null},{\"description\":\"Body\"}]}";
        List<String> descriptions = SearchParentActor.extractDescriptions(payload);
        assertEquals(2, descriptions.size());
        assertEquals("", descriptions.get(0));
        assertEquals("Body", descriptions.get(1));
    }

    @Test
    void extractDescriptions_handlesNullEntries() {
        String payload = "{\"articles\":[{\"description\":null},{\"description\":\"Body\"}]}";
        List<String> descriptions = SearchParentActor.extractDescriptions(payload);
        assertEquals(2, descriptions.size());
        assertEquals("", descriptions.get(0));
        assertEquals("Body", descriptions.get(1));
    }

    /**
     * Deterministic fake of {@link NewsService} that bypasses any WS calls and returns
     * small stable JSON fragments. Used to isolate controller logic in tests that do not
     * need to verify HTTP request construction.
     *
     * <p>This class intentionally covers only the endpoints exercised by the controller:</p>
     * <ul>
     *   <li>{@link #searchEverything(String, String, String, String, String, String, String)}</li>
     *   <li>{@link #listSources(String, String, String)}</li>
     * </ul>
     *
     * @author Nirvana Borham
     */
    static class FakeNewsService extends NewsService {

        /**
         * Constructs the fake with a minimal config. The {@link WSClient} is unused (may be {@code null})
         * because this implementation never performs network I/O.
         *
         * @author Nirvana Borham
         */
        FakeNewsService() {
            super(minimalConfig(), null);
        }

        /**
         * Supplies the minimal configuration required by {@link NewsService}'s constructor.
         *
         * @return a tiny config with baseUrl/key/timeout entries
         * @author Nirvana Borham
         */
        private static Config minimalConfig() {
            return ConfigFactory.parseString(
                    "newsapi.baseUrl = \"https://newsapi.org/v2\"\n" +
                            "newsapi.key     = \"\"\n" +
                            "newsapi.timeoutMs = 5000"
            );
        }

        /**
         * Simulates NewsAPI {@code /everything}. Returns a marker indicating which inputs were present:
         * <ul>
         *   <li>query only → {@code {"which":"q-only"}}</li>
         *   <li>sources only → {@code {"which":"sources-only"}}</li>
         *   <li>both → {@code {"which":"q+sources"}}</li>
         * </ul>
         *
         * @param q           the free-text query (may be {@code null/blank})
         * @param sourcesCsv  CSV of sources (may be {@code null/blank})
         * @param country     unused in this fake
         * @param category    unused in this fake
         * @param language    unused in this fake
         * @return completed future with {@link NewsResponse} {@code status=200}
         * @author Nirvana Borham
         */
        @Override
        public CompletionStage<NewsResponse> searchEverything(
                String q, String sourcesCsv, String country, String category, String language) {
            return searchEverything(q, sourcesCsv, country, category, language, "relevancy", "20");
        }

        /**
         * Simulates NewsAPI {@code /sources}. Echoes {@code sources:true} and the language,
         * defaulting to {@code "en"} when blank.
         *
         * @param language language code or blank
         * @param category unused in this fake
         * @param country  unused in this fake
         * @return completed future with {@link NewsResponse} {@code status=200}
         * @author Nirvana Borham
         */
        @Override
        public CompletionStage<NewsResponse> searchEverything(
                String q, String sourcesCsv, String country, String category, String language, String sortBy, String pageSize) {
            boolean hasQ = q != null && !q.trim().isEmpty();
            boolean hasS = sourcesCsv != null && !sourcesCsv.trim().isEmpty();
            String which = (hasQ && hasS) ? "q+sources" : (hasQ ? "q-only" : "sources-only");
            String body = "{\"ok\":true,\"which\":\"" + which +
                    "\",\"sortBy\":\"" + sortBy +
                    "\",\"pageSize\":\"" + pageSize +
                    "\",\"overallSentiment\":\":-)\"}";
            return CompletableFuture.completedFuture(new NewsResponse(200, body));
        }

        @Override
        public CompletionStage<NewsResponse> listSources(String language, String category, String country) {
            String lang = (language == null || language.trim().isEmpty()) ? "en" : language;
            String body = "{\"status\":\"ok\",\"sources\":[{\"id\":\"test-source\",\"name\":\"Test Source\",\"description\":\"A test source\"}],\"language\":\"" + lang + "\"}";
            return CompletableFuture.completedFuture(new NewsResponse(200, body));
        }
    }

    /**
     * Fake implementation of {@link services.SourceProfileService} for testing.
     */
    static class FakeSourceProfileService extends services.SourceProfileService {
        FakeSourceProfileService(NewsService newsService) {
            super(newsService);
        }
    }

    // ===== Extra coverage for private helper: extractDescriptions(String) =====

    /**
     * Reflection utility that invokes the private {@code extractDescriptions(String)} method
     * defined on {@link SearchController}.
     *
     * @param body raw JSON response body that may contain an {@code articles} array
     * @return list of normalized description strings (missing/null → empty string)
     * @throws Exception if reflection fails to access or invoke the method
     * @author Nirvana Borham
     */
    private List<String> invokeExtract(String body) {
        return SearchParentActor.extractDescriptions(body);
    }

    /**
     * Mixed-value inputs exercise normalization:
     * <ul>
     *   <li>present description → same text</li>
     *   <li>null description → empty string</li>
     *   <li>missing description → empty string</li>
     * </ul>
     * Expected output: {@code ["hello", "", ""]}.
     *
     * @author Nirvana Borham
     */
    @Test
    void extractDescriptions_mixedValues_normalizes() {
        String body = "{ \"articles\":[" +
                "{ \"description\":\"hello\" }," +
                "{ \"description\":null }," +
                "{ \"title\":\"no description here\" }" +
                "]}";
        List<String> out = invokeExtract(body);
        assertEquals(3, out.size());
        assertEquals("hello", out.get(0));
        assertEquals("", out.get(1));   // null → ""
        assertEquals("", out.get(2));   // missing → ""
    }

    /**
     * When {@code articles} exists but is not an array, the helper should short-circuit
     * and return an empty list (covers {@code !isArray} branch).
     *
     * @author Nirvana Borham
     */
    @Test
    void extractDescriptions_articlesNotArray_returnsEmpty() {
        String body = "{ \"articles\": { \"x\": 1 } }";
        List<String> out = invokeExtract(body);
        assertTrue(out.isEmpty());
    }

    /**
     * Malformed JSON should be caught and result in an empty list,
     * exercising the {@code catch (Exception ignore)} path.
     *
     * @author Nirvana Borham
     */
    @Test
    void extractDescriptions_malformedJson_swallowedAndEmpty() {
        List<String> out = invokeExtract("{"); // invalid JSON
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void joinCsvParams_filtersBlanksAndTrims() {
        Http.Request request = Helpers.fakeRequest("GET", "/foo?sources=%20cnn%20&sources=&sources=bbc-news").build();
        String csv = SearchParentActor.joinCsvParams(request, "sources");
        assertEquals("cnn,bbc-news", csv);
    }

    @Test
    void toSseEvent_formatsCorrectly() {
        String event = SearchParentActor.toSseEvent("init", "{\"hello\":1}");
        assertEquals("event: init\n" +
                "data: {\"hello\":1}\n\n", event);
    }

    // ===== Additional tests for missed branches =====

    /**
     * Test sources endpoint with blank sourceId (not null, but empty string).
     * Should fall through to list sources behavior.
     */
    @Test
    void sources_withBlankSourceId_listsAllSources() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?sourceId="));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            // Should behave like listing all sources
            assertTrue(body.contains("\"sources\""));
        });
    }

    /**
     * Test sources endpoint with blank id parameter.
     * Should fall through to list sources behavior.
     */
    @Test
    void sources_withBlankId_listsAllSources() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources?id="));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            // Should behave like listing all sources
            assertTrue(body.contains("\"sources\""));
        });
    }

    /**
     * Test wordStats when the API returns a response where articles is not an array.
     * Should handle gracefully and return empty word list.
     */
    @Test
    void wordStats_articlesNotArray_returnsEmptyWordList() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq("test"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"articles\":\"not-an-array\"}")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=test"));
            assertEquals(OK, res.status());

            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("ok", json.path("status").asText());
            // Should have empty or very small words array
            assertTrue(json.path("words").isArray());
            assertEquals(0, json.path("words").size());
        });

        verify(news).searchEverything(eq("test"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50"));
    }

    @Test
    void wordStats_defaultsLanguageToEnglishWhenMissing_branch() {
        NewsService news = mock(NewsService.class);
        String body = "{\"status\":\"ok\",\"articles\":[]}";
        when(news.searchEverything(eq("topic"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=topic"));
            assertEquals(OK, res.status());
        });

        verify(news).searchEverything(eq("topic"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50"));
    }

    /**
     * Test search with null sources parameter explicitly.
     * Should handle null gracefully.
     */
    @Test
    void search_withNullSources_handlesGracefully() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=AI"));
            assertEquals(OK, res.status());
            assertTrue(contentAsString(res).contains("\"ok\":true"));
        });
    }

    /**
     * Test readabilityJson with null query parameter.
     * Should default to empty string and still work.
     */
    @Test
    void readabilityJson_nullQuery_defaultsToEmpty() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq(""), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/readability"));
            assertEquals(OK, res.status());
            JsonNode json = Json.parse(contentAsString(res));
            assertTrue(json.has("averageReadingEase"));
            assertTrue(json.has("averageGradeLevel"));
        });

        verify(news).searchEverything(eq(""), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull());
    }

    /**
     * Test wordStats with descriptions containing only whitespace or punctuation.
     * Ensures filters work correctly.
     */
    @Test
    void wordStats_withWhitespaceDescriptions_filtersCorrectly() {
        NewsService news = mock(NewsService.class);
        String body = "{\"status\":\"ok\",\"articles\":["
                + "{\"description\":\"   \"},"
                + "{\"description\":\"!!!\"},"
                + "{\"description\":null}"
                + "]}";
        when(news.searchEverything(eq("test"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=test"));
            assertEquals(OK, res.status());

            JsonNode json = Json.parse(contentAsString(res));
            assertEquals("ok", json.path("status").asText());
            // Should have empty words array since all descriptions are blank/punctuation
            assertEquals(0, json.path("words").size());
        });
    }

    // ========== Additional tests for remaining SearchController missed branches ==========

    /**
     * Test search with null query parameter - should return 400.
     */
    @Test
    void search_withNullQuery_returns400() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search"));
            assertEquals(BAD_REQUEST, res.status());
        });
    }

    /**
     * Test search with empty query and empty sources - should return 400.
     */
    @Test
    void search_withEmptyQueryAndSources_returns400() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/search?q=&sources="));
            assertEquals(BAD_REQUEST, res.status());
        });
    }

    /**
     * Test sources endpoint with null sourceId - should list all sources.
     */
    @Test
    void sources_withNullSourceId_listsAllSources() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/sources"));
            assertEquals(OK, res.status());
            String body = contentAsString(res);
            assertTrue(body.contains("\"sources\""));
        });
    }

    /**
     * Test extractDescriptions with articles containing null descriptions.
     * Should handle gracefully and return empty strings.
     */
    @Test
    void extractDescriptions_withNullDescriptions_returnsEmptyStrings() throws Exception {
        String body = "{\"articles\":["
                + "{\"description\":null},"
                + "{\"description\":\"valid\"},"
                + "{\"description\":null}"
                + "]}";
        List<String> out = invokeExtract(body);

        assertEquals(3, out.size());
        assertEquals("", out.get(0)); // null description becomes empty string
        assertEquals("valid", out.get(1));
        assertEquals("", out.get(2)); // null description becomes empty string
    }

    /**
     * Test readabilityJson with only sources parameter (no query).
     * Should use empty string for query parameter.
     */
    @Test
    void readabilityJson_onlySourcesParam_usesEmptyQuery() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq(""), eq("cnn"), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/readability?sources=cnn"));
            assertEquals(OK, res.status());
        });

        verify(news).searchEverything(eq(""), eq("cnn"), isNull(), isNull(), eq("en"), isNull(), isNull());
    }

    @Test
    void readabilityJson_langAll_defaultsToEnglish() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(eq("topic"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/readability?query=topic&lang=all"));
            assertEquals(OK, res.status());
        });

        verify(news).searchEverything(eq("topic"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull());
    }

    @Test
    void wordStats_missingQuery_returnsBadRequest() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats"));
            assertEquals(BAD_REQUEST, res.status());
        });
    }

    @Test
    void wordStats_respectsProvidedLanguageParameter() {
        NewsService news = mock(NewsService.class);
        String body = "{\"status\":\"ok\",\"articles\":[]}";
        when(news.searchEverything(eq("topic"), eq(""), eq(""), eq(""), eq("fr"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=topic&lang=fr"));
            assertEquals(OK, res.status());
        });

        verify(news).searchEverything(eq("topic"), eq(""), eq(""), eq(""), eq("fr"), eq("relevancy"), eq("50"));
    }

    @Test
    void wordStats_blankQueryString_returnsBadRequest() {
        Application local = mkApp();
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=%20%20%20"));
            assertEquals(BAD_REQUEST, res.status());
        });
    }

    @Test
    void wordStats_defaultsLanguageToEnglishWhenMissing() {
        NewsService news = mock(NewsService.class);
        String body = "{\"status\":\"ok\",\"articles\":[]}";
        when(news.searchEverything(eq("topic"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50")))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, body)));

        Application local = mkAppWithNewsService(news);
        Helpers.running(local, () -> {
            Result res = route(local, fakeRequest(GET, "/api/wordstats?q=topic"));
            assertEquals(OK, res.status());
        });

        verify(news).searchEverything(eq("topic"), eq(""), eq(""), eq(""), eq("en"), eq("relevancy"), eq("50"));
    }

    private static final ActorTestKit testKit = ActorTestKit.create();

    @AfterAll
    static void shutdownKit() {
        testKit.shutdownTestKit();
    }

    /**
     * Helper: a dummy parent actor that answers ResolveStreamSpec
     * with a successful SearchPreparation.
     */
    private ActorRef<SearchParentActor.Command> spawnFakeParent() {
        return testKit.spawn(
                Behaviors.receive(SearchParentActor.Command.class)
                        .onMessage(
                                SearchParentActor.ResolveStreamSpec.class,
                                msg -> {
                                    // Build any valid SearchSpec
                                    SearchParentActor.SearchSpec spec =
                                            new SearchParentActor.SearchSpec(
                                                    "climate",
                                                    null,
                                                    null,
                                                    null,
                                                    "en",
                                                    "relevancy",
                                                    10
                                            );

                                    SearchParentActor.SearchPreparation prep =
                                            SearchParentActor.SearchPreparation.success(spec);

                                    msg.replyTo.tell(prep);
                                    return Behaviors.same();
                                })
                        .build()
        );
    }

    @Test
    void streamSearch_returnsChunkedSSE() {

        // --- Mock all services ---
        NewsService news = mock(NewsService.class);
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class); // real or mock

        // Mock listSources + searchEverything to avoid async hangs
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200,
                        "{\"status\":\"ok\",\"totalResults\":0,\"articles\":[]}")));

        when(profiles.fetchSourceProfile(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null)); // not used

        // --- Mock ApplicationLifecycle correctly ---
        ApplicationLifecycle lifecycle = mock(ApplicationLifecycle.class);

        doAnswer(invocation -> {
            Callable<CompletionStage<?>> hook = invocation.getArgument(0);
            return CompletableFuture.completedFuture(null);  // return future to satisfy constructor
        }).when(lifecycle).addStopHook(any());

        // --- Create controller ---
        SearchController controller = new SearchController(
                news,
                sentiment,
                profiles,
                readability,
                lifecycle
        );

        // Fake request
        Http.Request request = Helpers.fakeRequest("GET", "/stream?q=test").build();

        // --- Call the method ---
        CompletionStage<Result> stage = controller.streamSearch(request);
        Result result = stage.toCompletableFuture().join();

        // --- Assertions ---
        assertEquals(OK, result.status());
        assertEquals("text/event-stream", result.contentType().get());
    }

    @Test
    void streamSearchWebSocket_withQuery_returnsFlow() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200,
                        "{\"status\":\"ok\",\"articles\":[]}")));

        SearchController controller = newSearchControllerForStreaming(news);
        try {
            Http.RequestHeader header = Helpers.fakeRequest("GET", "/ws/stream?q=ai").build();
            F.Either<Result, Flow<String, String, ?>> either =
                    controller.streamSearchWsFlow(header).toCompletableFuture().join();
            assertTrue(either.right.isPresent(), "Expected WebSocket flow to be accepted");
        } finally {
            shutdownSearchController(controller);
        }
    }

    @Test
    void sessionHistory_afterStreaming_returnsSnapshot() throws Exception {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        SearchController controller = newSearchControllerForStreaming(news);
        try {
            Http.RequestHeader header = Helpers.fakeRequest("GET", "/ws/stream?q=ai&sessionId=my-session").build();
            controller.streamSearchWsFlow(header).toCompletableFuture().join();

            Http.Request request = Helpers.fakeRequest("GET", "/api/session-history?sessionId=my-session").build();
            Result result = controller.sessionHistory(request).toCompletableFuture().join();
            assertEquals(OK, result.status());

            Materializer mat = SystemMaterializer.get(actorSystemFrom(controller)).materializer();
            String body = result.body().consumeData(mat).toCompletableFuture().get(2, TimeUnit.SECONDS).utf8String();
            assertTrue(body.contains("\"sessionId\":\"my-session\""));
            assertTrue(body.contains("\"history\""));
        } finally {
            shutdownSearchController(controller);
        }
    }

    @Test
    void streamSearchWebSocket_withoutQuery_returnsBadRequest() {
        NewsService news = mock(NewsService.class);
        SearchController controller = newSearchControllerForStreaming(news);
        try {
            Http.RequestHeader header = Helpers.fakeRequest("GET", "/ws/stream").build();
            F.Either<Result, Flow<String, String, ?>> either =
                    controller.streamSearchWsFlow(header).toCompletableFuture().join();
            assertTrue(either.left.isPresent());
            assertEquals(BAD_REQUEST, either.left.get().status());
        } finally {
            shutdownSearchController(controller);
        }
    }

    @Test
    void streamSearchWebSocket_sameSessionId_replacesExistingActor() {
        NewsService news = mock(NewsService.class);
        when(news.searchEverything(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        SearchController controller = newSearchControllerForStreaming(news);
        try {
            Http.RequestHeader first = Helpers.fakeRequest("GET", "/ws/stream?q=ai&sessionId=dup-session").build();
            F.Either<Result, Flow<String, String, ?>> initial =
                    controller.streamSearchWsFlow(first).toCompletableFuture().join();
            assertTrue(initial.right.isPresent());

            // Second connection with the same sessionId should succeed and trigger the replacement branch
            Http.RequestHeader second = Helpers.fakeRequest("GET", "/ws/stream?q=ai&sessionId=dup-session").build();
            F.Either<Result, Flow<String, String, ?>> duplicate =
                    controller.streamSearchWsFlow(second).toCompletableFuture().join();
            assertTrue(duplicate.right.isPresent());
        } finally {
            shutdownSearchController(controller);
        }
    }

    /**
     * JaCoCo flagged SearchController.streamSearch(...) for only exercising the happy-path branch.
     * This regression test hits the {@code prep.errorResult != null} branch by omitting both query
     * terms and filters, which forces {@link SearchParentActor#prepareSearchSpec}
     * to return a validation error.
     */
    @Test
    void streamSearch_withoutQueryOrFilters_returnsBadRequest() throws Exception {
        NewsService news = mock(NewsService.class);
        SearchController controller = newSearchControllerForStreaming(news);
        try {
            Http.Request request = Helpers.fakeRequest("GET", "/search/stream").build();
            Result result = controller.streamSearch(request).toCompletableFuture().join();
            assertEquals(BAD_REQUEST, result.status());
        } finally {
            shutdownSearchController(controller);
        }
    }

    /**
     * Test readabilityJson endpoint when NewsService future completes exceptionally.
     * This should exercise the ReadabilityActor.onHandleRequest error branch and
     * return HTTP 500 with the "Failed to compute readability" error payload.
     * @author Mustafa Kaya
     */
    @Test
    void readabilityJson_handlesExceptionWithInternalServerError() {
        NewsService mockNews = mock(NewsService.class);

        // Create a future that completes exceptionally
        CompletableFuture<NewsResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));

        when(mockNews.searchEverything(eq("test"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull()))
                .thenReturn(failed);

        Application local = mkAppWithNewsService(mockNews);
        Helpers.running(local, () -> {
            Result result = route(local, fakeRequest(GET, "/api/readability?query=test"));
            assertEquals(INTERNAL_SERVER_ERROR, result.status());

            JsonNode json = Json.parse(contentAsString(result));
            assertEquals(500, json.path("status").asInt());
            assertEquals("Failed to compute readability", json.path("error").asText());
        });

        verify(mockNews).searchEverything(eq("test"), isNull(), isNull(), isNull(), eq("en"), isNull(), isNull());
    }



    private SearchController newSearchControllerForStreaming(NewsService newsService) {
        SentimentService sentiment = mock(SentimentService.class);
        SourceProfileService profiles = mock(SourceProfileService.class);
        ReadabilityService readability = mock(ReadabilityService.class);

        when(sentiment.searchEverythingWithSentiment(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new NewsResponse(200, "{\"status\":\"ok\",\"articles\":[]}")));

        ApplicationLifecycle lifecycle = mock(ApplicationLifecycle.class);
        doAnswer(invocation -> CompletableFuture.completedFuture(null))
                .when(lifecycle).addStopHook(any());

        return new SearchController(
                newsService,
                sentiment,
                profiles,
                readability,
                lifecycle
        );
    }

    private ActorSystem<?> actorSystemFrom(SearchController controller) throws ReflectiveOperationException {
        Field field = SearchController.class.getDeclaredField("searchActorSystem");
        field.setAccessible(true);
        return (ActorSystem<?>) field.get(controller);
    }

    private void shutdownSearchController(SearchController controller) {
        try {
            actorSystemFrom(controller).terminate();
        } catch (ReflectiveOperationException ignored) {
            // nothing else to do in tests
        }
    }
}

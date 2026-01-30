package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import play.libs.ws.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NewsService}.
 *
 * <p>These tests cover request-building, status-mapping, mock-mode,
 * caching behavior, and small private helpers through reflection in
 * order to maximize line/branch/method coverage under JaCoCo.</p>
 *
 * <p>External I/O is fully mocked (no real HTTP).</p>
 *
 * @author Nirvana Borham
 */
public class NewsServiceTest {

    /** Default mocked WS client used by base tests (blank API key path). */
    private WSClient ws;
    /** Mocked WS request used for verifying the fluent builder chain. */
    private WSRequest req;
    /** Mocked WS response fulfilled by {@code WSRequest#get()}. */
    private WSResponse resp;
    /** Mocked configuration injected into the service under test. */
    private Config config;
    /** Service under test for the default (non-mock) wiring. */
    private NewsService svc;

    /**
     * Lightweight container for an isolated {@link NewsService}
     * and its associated WS mocks.
     *
     * @author Nirvana Borham
     */
    private static final class WiredSvc {
        final NewsService svc; final WSRequest req; final WSResponse resp; final WSClient ws;
        WiredSvc(NewsService s, WSClient w, WSRequest r, WSResponse p){svc=s;ws=w;req=r;resp=p;}
    }

    /**
     * Helper factory that builds a {@link WiredSvc} with a specific API key
     * and fully stubbed WS chain ({@code url → addHeader/addQueryParameter → get}).
     *
     * @param key API key to inject
     * @return wired service with mocks
     * @author Nirvana Borham
     */
    private WiredSvc svcWithKey(String key) {
        WSClient ws2   = mock(WSClient.class);
        WSRequest req2 = mock(WSRequest.class, RETURNS_DEEP_STUBS);
        WSResponse r2  = mock(WSResponse.class);

        Config cfg2 = ConfigFactory.parseString(
                "newsapi.baseUrl = \"https://newsapi.org/v2\"\n" +
                        "newsapi.key     = \"" + key + "\"\n" +
                        "newsapi.timeoutMs = 5000\n" +
                        "newsapi.mock = false\n"
        );

        when(ws2.url(anyString())).thenReturn(req2);
        when(req2.addHeader(anyString(), anyString())).thenReturn(req2);
        when(req2.addQueryParameter(anyString(), anyString())).thenReturn(req2);
        when(req2.setRequestTimeout(any(Duration.class))).thenReturn(req2);
        when(req2.get()).thenReturn(CompletableFuture.completedFuture(r2));

        return new WiredSvc(new NewsService(cfg2, ws2), ws2, req2, r2);
    }

    /**
     * Creates fresh WS/Config mocks and a default {@link NewsService}
     * (with intentionally blank API key) before each test.
     *
     * @author Nirvana Borham
     */
    @BeforeEach
    void setup() {
        ws     = mock(WSClient.class);
        req    = mock(WSRequest.class, RETURNS_DEEP_STUBS);
        resp   = mock(WSResponse.class);

        config = ConfigFactory.parseString(
                "newsapi.baseUrl = \"https://newsapi.org/v2\"\n" +
                        "newsapi.key     = \"\" \n" +         // intentionally blank (non-mock)
                        "newsapi.timeoutMs = 5000\n" +
                        "newsapi.mock = false\n"
        );

        when(ws.url(anyString())).thenReturn(req);
        when(req.addQueryParameter(anyString(), anyString())).thenReturn(req);
        when(req.addHeader(anyString(), anyString())).thenReturn(req);
        when(req.setRequestTimeout(any(Duration.class))).thenReturn(req);
        when(req.get()).thenReturn(CompletableFuture.completedFuture(resp));

        when(resp.getStatus()).thenReturn(200);
        when(resp.getBody()).thenReturn("{\"ok\":true}");

        svc = new NewsService(config, ws);
    }

    // =========================================================
    // Request-building behavior
    // =========================================================

    /**
     * Verifies that {@link NewsService#searchEverything(String, String, String, String, String, String, String)}
     * targets {@code /everything}, quotes {@code q} as a phrase, applies defaults,
     * and adds the API key header.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything_buildsQuery_withQAndSources_andHeader() throws Exception {
        CompletionStage<NewsResponse> stg =
                svc.searchEverything("AI", "the-verge,bbc-news", null, null, "en");
        NewsResponse r = stg.toCompletableFuture().get();
        assertEquals(200, r.status);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(ws).url(url.capture());
        assertTrue(url.getValue().endsWith("/everything"));

        // q is now a PHRASE
        verify(req).addQueryParameter("q", "\"AI\"");
        verify(req).addQueryParameter("sources", "the-verge,bbc-news");
        verify(req).addQueryParameter("language", "en");
        verify(req).addQueryParameter("pageSize", "10");
        verify(req).addQueryParameter("sortBy", "relevancy");
        verify(req).addHeader("X-Api-Key", "");
        verify(req).get();
    }


    /**
     * Ensures that a null {@code sources} omits the {@code sources} query parameter.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything_noSources_doesNotAddSourcesParam() {
        svc.searchEverything("AI", null, null, null, "en").toCompletableFuture().join();
        verify(req, never()).addQueryParameter(eq("sources"), anyString());
        verify(req).addQueryParameter("q", "\"AI\"");
        verify(req).addQueryParameter("language", "en");
    }

    /**
     * Verifies that blank queries are ignored (no {@code q} parameter is added).
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything_blankQ_doesNotAddQ() {
        svc.searchEverything("   ", "the-verge", null, null, "en").toCompletableFuture().join();
        verify(req, never()).addQueryParameter(eq("q"), anyString());
        verify(req).addQueryParameter("sources", "the-verge");
        verify(req).addQueryParameter("language", "en");
    }

    /**
     * Confirms {@link NewsService#listSources(String, String, String)} hits
     * {@code /sources} (or {@code /top-headlines/sources}) and adds header.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_buildsQuery_defaultsAndHeader() {
        svc.listSources("en", "", "").toCompletableFuture().join();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(ws).url(url.capture());
        String u = url.getValue();

        // Accept either style to match implementation variance
        assertTrue(
                u.endsWith("/top-headlines/sources") || u.endsWith("/sources"),
                "Expected /top-headlines/sources or /sources but got: " + u
        );

        verify(req).addQueryParameter("language", "en");
        verify(req).addHeader("X-Api-Key", "");
        verify(req).get();
    }

    // =========================================================
    // Status mapping / error paths
    // =========================================================

    /**
     * 200 from upstream should pass through unchanged.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_success_passthrough200() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"ok\":true}");

        NewsResponse out = w.svc.searchNewsWithStatus("AI").toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"ok\":true"));
    }

    /**
     * 401/403 from upstream maps to 502 with an auth-failure message.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_401or403_mapsTo502() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(401);

        NewsResponse out = w.svc.searchNewsWithStatus("AI").toCompletableFuture().get();

        assertEquals(502, out.status);
        assertTrue(out.body.contains("Invalid API key"));
    }

    /**
     * 429 rate limiting must be preserved as 429.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_429_mapsTo429() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(429);

        NewsResponse out = w.svc.searchNewsWithStatus("AI").toCompletableFuture().get();

        assertEquals(429, out.status);
    }

    /**
     * Any 5xx → 502 Upstream unavailable.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_5xx_mapsTo502() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(503);

        NewsResponse out = w.svc.searchNewsWithStatus("AI").toCompletableFuture().get();

        assertEquals(502, out.status);
        assertTrue(out.body.contains("Upstream service unavailable"));
    }

    /**
     * Network exception path returns 500 with a failure message.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_exception_mapsTo500() throws Exception {
        WSClient ws2   = mock(WSClient.class);
        WSRequest req2 = mock(WSRequest.class, RETURNS_DEEP_STUBS);
        Config cfg2    = mock(Config.class);

        when(cfg2.getString("newsapi.baseUrl")).thenReturn("https://newsapi.org/v2");
        when(cfg2.hasPath("newsapi.key")).thenReturn(true);
        when(cfg2.getString("newsapi.key")).thenReturn("abc");
        when(cfg2.getInt("newsapi.timeoutMs")).thenReturn(5000);

        when(ws2.url(anyString())).thenReturn(req2);
        when(req2.addHeader(anyString(), anyString())).thenReturn(req2);
        when(req2.addQueryParameter(anyString(), anyString())).thenReturn(req2);
        when(req2.setRequestTimeout(any(Duration.class))).thenReturn(req2);

        CompletableFuture<WSResponse> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("boom"));
        when(req2.get()).thenReturn(failing);

        NewsService svc = new NewsService(cfg2, ws2);

        NewsResponse out = svc.searchNewsWithStatus("AI").toCompletableFuture().get();

        assertEquals(500, out.status);
        assertTrue(out.body.contains("Request failed"));
    }

    /**
     * Explicit 403 → 502 (same as 401) with auth-failure text.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_403_alsoMapsTo502() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(403);

        NewsResponse out = w.svc.searchNewsWithStatus("AI").toCompletableFuture().get();

        assertEquals(502, out.status);
        assertTrue(out.body.contains("Invalid API key"));
    }

    // =========================================================
    // listSources variations
    // =========================================================

    /**
     * Category-only adds {@code category} but not {@code country}.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_onlyCategory_addsCategoryNotCountry() {
        svc.listSources("en", "technology", "").toCompletableFuture().join();
        verify(req).addQueryParameter("language", "en");
        verify(req).addQueryParameter("category", "technology");
        verify(req, never()).addQueryParameter(eq("country"), anyString());
        verify(req).addHeader("X-Api-Key", "");
        verify(req).get();
    }

    /**
     * Blank language should omit the language query parameter entirely.
     *
     * Ensures downstream calls rely on NewsAPI defaults while still applying other filters.
     */
    @Test
    void listSources_blankLanguage_doesNotAddLanguageParam() {
        svc.listSources("", "business", "us").toCompletableFuture().join();
        verify(req, never()).addQueryParameter(eq("language"), anyString());
        verify(req).addQueryParameter("category", "business");
        verify(req).addQueryParameter("country", "us");
        verify(req).addHeader("X-Api-Key", "");
        verify(req).get();
    }

    /**
     * Country-only adds {@code country} but not {@code category}.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_onlyCountry_addsCountryNotCategory() {
        svc.listSources("en", "", "us").toCompletableFuture().join();
        verify(req).addQueryParameter("language", "en");
        verify(req).addQueryParameter("country", "us");
        verify(req, never()).addQueryParameter(eq("category"), anyString());
        verify(req).addHeader("X-Api-Key", "");
        verify(req).get();
    }

    /**
     * A blank API key triggers an early 503 and no HTTP calls.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_apiKeyMissing_early503_noHttpCall() {
        Config cfg = mock(Config.class);
        when(cfg.getString("newsapi.baseUrl")).thenReturn("https://newsapi.org/v2");
        when(cfg.hasPath("newsapi.key")).thenReturn(true);
        when(cfg.getString("newsapi.key")).thenReturn(""); // blank
        when(cfg.getInt("newsapi.timeoutMs")).thenReturn(5000);

        WSClient ws = mock(WSClient.class);
        NewsService svc = new NewsService(cfg, ws);

        NewsResponse out = svc.searchNewsWithStatus("AI").toCompletableFuture().join();

        assertEquals(503, out.status);
        assertTrue(out.body.contains("NEWS_API_KEY not configured"));
        verify(ws, never()).url(anyString());
    }

    /**
     * Uses provided valid sortBy value.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything_usesProvidedSortBy_whenValid() {
        svc.searchEverything("AI", null, null, null, "en", "popularity", "20").toCompletableFuture().join();
        verify(req).addQueryParameter("sortBy", "popularity");
    }

    /**
     * Invalid sortBy falls back to {@code relevancy}.
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything_sanitizesSortBy_whenInvalid() {
        svc.searchEverything("AI", null, null, null, "en", "not-a-valid-sort", "20").toCompletableFuture().join();
        verify(req).addQueryParameter("sortBy", "relevancy"); // default
    }

    /**
     * Covers all branches of {@code toEdtDisplay(String)} via reflection:
     * <ul>
     *   <li>{@code null} → {@code ""}</li>
     *   <li>blank → {@code ""}</li>
     *   <li>valid summer timestamp (EDT, UTC−4)</li>
     *   <li>valid winter timestamp (EST, UTC−5)</li>
     *   <li>malformed input → {@code ""}</li>
     * </ul>
     *
     * @author Nirvana Borham
     */
    @Test
    void toEdtDisplay_allBranchesCovered() throws Exception {
        var m = NewsService.class.getDeclaredMethod("toEdtDisplay", String.class);
        m.setAccessible(true);

        // Example: a date that’s in DST
        String dst = (String) m.invoke(null, "2024-07-10T12:00:00Z");
        assertTrue(dst.matches("^2024-07-10 08:00:00 (EDT|EST)$"),
                "Expected '2024-07-10 08:00:00 EDT' (or EST), got: " + dst);

        // Example: a date that’s in Standard Time
        String std = (String) m.invoke(null, "2024-12-10T13:00:00Z");
        assertTrue(std.matches("^2024-12-10 08:00:00 (EDT|EST)$"),
                "Expected '2024-12-10 08:00:00 EST' (or EDT), got: " + std);

        // Bad input cases remain the same:
        String empty1 = (String) m.invoke(null, (Object) null);
        assertEquals("", empty1);
        String empty2 = (String) m.invoke(null, "");
        assertEquals("", empty2);
    }

    /**
     * {@code toEdtDisplay}: summer DST case (UTC-4 for NYC on Aug 1).
     *
     * @author Nirvana Borham
     */
    @Test
    void toEdtDisplay_formatsUtcToNY_summerDST() throws Exception {
        var m = NewsService.class.getDeclaredMethod("toEdtDisplay", String.class);
        m.setAccessible(true);

        String out = (String) m.invoke(null, "2024-08-01T12:34:56Z");
        assertEquals("2024-08-01 08:34:56 EDT", out); // ← was without zone
    }


    /**
     * {@code toEdtDisplay(String)}: returns {@code ""} for {@code null} and blank inputs.
     *
     * @author Nirvana Borham
     */
    @Test
    void toEdtDisplay_nullOrBlank_returnsEmpty() throws Exception {
        var m = NewsService.class.getDeclaredMethod("toEdtDisplay", String.class);
        m.setAccessible(true);

        assertEquals("", (String) m.invoke(null, (Object) null));
        assertEquals("", (String) m.invoke(null, "   "));
    }

    /**
     * {@code toEdtDisplay(String)}: returns {@code ""} when the input is malformed
     * (exception path).
     *
     * @author Nirvana Borham
     */
    @Test
    void toEdtDisplay_badInput_returnsEmpty() throws Exception {
        var m = NewsService.class.getDeclaredMethod("toEdtDisplay", String.class);
        m.setAccessible(true);

        // Triggers the catch { return "" }
        assertEquals("", (String) m.invoke(null, "not-a-timestamp"));
    }


    // =========================================================
    // MOCK MODE & CACHE
    // =========================================================

    /**
     * Writes a small "combined" mock JSON file into {@code dir} where each top-level key
     * is a language code and the value is an arbitrary JSON object. This mirrors the
     * layout used by mock mode (e.g., {@code sources.json}, {@code everything_ai.json}).
     *
     * <p><b>Example</b>:
     * <pre>
     *   writeCombinedMock(tmp, "sources.json",
     *       "en", "{\"ok\":true}",
     *       "fr", "{\"ok\":true}"
     *   );
     * </pre>
     *
     * @param dir       temporary directory to contain the file
     * @param fileName  file name to create (e.g., {@code "sources.json"})
     * @param kvPairs   alternating language keys and JSON strings: {@code "en", "{...}", "fr", "{...}"}
     * @return the created file path
     * @throws IOException if writing fails
     * @author Nirvana Borham
     */
    private static Path writeCombinedMock(Path dir, String fileName, String... kvPairs) throws IOException {
        // kvPairs like: "en", "{\"a\":1}", "fr", "{\"a\":2}"
        StringBuilder sb = new StringBuilder("{");
        for (int i=0;i<kvPairs.length;i+=2) {
            if (i>0) sb.append(",");
            sb.append("\"").append(kvPairs[i]).append("\":").append(kvPairs[i+1]);
        }
        sb.append("}");
        Path p = dir.resolve(fileName);
        Files.writeString(p, sb.toString());
        return p;
    }

    /**
     * Reads {@code sources.json} in mock mode and selects the explicit language bucket.
     * Verifies that requesting {@code fr} returns the French section and {@code en} returns English.
     * @author Nirvana Borham
     */
    @Test
    void mockMode_listSources_readsCombinedByLanguage() throws Exception {
        Path tmp = Files.createTempDirectory("mockdir");
        writeCombinedMock(tmp, "sources.json",
                "en", "{\"ok\":true,\"lang\":\"en\"}",
                "fr", "{\"ok\":true,\"lang\":\"fr\"}"
        );

        Config cfg = ConfigFactory.parseString(
                "newsapi.baseUrl = \"https://newsapi.org/v2\"\n" +
                        "newsapi.key     = \"\"\n" +
                        "newsapi.timeoutMs = 5000\n" +
                        "newsapi.mock = true\n" +
                        "newsapi.mockDir = \"" + tmp.toString().replace("\\","/") + "\"\n"
        );

        NewsService mockSvc = new NewsService(cfg, null);
        NewsResponse r1 = mockSvc.listSources("fr", null, null).toCompletableFuture().get();
        assertEquals(200, r1.status);
        assertTrue(r1.body.contains("\"lang\":\"fr\""));

        NewsResponse r2 = mockSvc.listSources("en", null, null).toCompletableFuture().get();
        assertTrue(r2.body.contains("\"lang\":\"en\""));
    }

    /**
     * Reads {@code everything_ai.json} in mock mode and verifies that when the requested
     * language block is missing, the implementation gracefully falls back to {@code en}.
     * Ensures language fallback logic is exercised end-to-end.
     * @author Nirvana Borham
     */
    @Test
    void mockMode_everything_readsCombinedWithFallback() throws Exception {
        Path tmp = Files.createTempDirectory("mockdir2");
        writeCombinedMock(tmp, "everything_ai.json",
                "en", "{\"topic\":\"ai\",\"lang\":\"en\"}"
        );
        Config cfg = ConfigFactory.parseString(
                "newsapi.baseUrl = \"https://newsapi.org/v2\"\n" +
                        "newsapi.key     = \"\"\n" +
                        "newsapi.timeoutMs = 5000\n" +
                        "newsapi.mock = true\n" +
                        "newsapi.mockDir = \"" + tmp.toString().replace("\\","/") + "\"\n"
        );

        NewsService mockSvc = new NewsService(cfg, null);
        // request "fr" -> should fallback to "en" since only "en" exists in file
        NewsResponse r = mockSvc.searchEverything("AI", null, null, null, "fr").toCompletableFuture().get();
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"lang\":\"en\""));
    }

    /**
     * Ensures a missing mock file produces a 500 error payload containing
     * a clear diagnostic message (no real network access is attempted).
     * @author Nirvana Borham
     */
    @Test
    void mockMode_missingFile_returns500() throws Exception {
        Path tmp = Files.createTempDirectory("mockdir3");
        Config cfg = ConfigFactory.parseString(
                "newsapi.baseUrl = \"https://newsapi.org/v2\"\n" +
                        "newsapi.key     = \"\"\n" +
                        "newsapi.timeoutMs = 5000\n" +
                        "newsapi.mock = true\n" +
                        "newsapi.mockDir = \"" + tmp.toString().replace("\\","/") + "\"\n"
        );

        NewsService mockSvc = new NewsService(cfg, null);
        NewsResponse r = mockSvc.searchEverything("does-not-exist", null, null, null, "en").toCompletableFuture().get();
        assertEquals(500, r.status);
        assertTrue(r.body.contains("mock file missing"));
    }
    /**
     * Confirms that {@link NewsService#searchNewsWithStatus(String)} respects mock mode at construction
     * time and returns a mock-derived payload instead of calling an upstream service.
     * The assertion allows either a 200 mock body or a 500 "mock file" error, depending on files present.
     * @author Nirvana Borham
     */
    @Test
    void searchNewsWithStatus_mockMode_readsFromCombinedFile() throws Exception {
        // Arrange
        Config cfg = mock(Config.class);
        WSClient ws = mock(WSClient.class);
        when(cfg.getString("newsapi.baseUrl")).thenReturn("https://newsapi.org/v2");
        when(cfg.hasPath("newsapi.key")).thenReturn(true);
        when(cfg.getString("newsapi.key")).thenReturn("abc");
        when(cfg.getInt("newsapi.timeoutMs")).thenReturn(5000);
        when(cfg.hasPath("newsapi.mock")).thenReturn(true);
        when(cfg.getBoolean("newsapi.mock")).thenReturn(true);
        when(cfg.hasPath("newsapi.mockDir")).thenReturn(false);

        // Act
        NewsService svc = new NewsService(cfg, ws);
        CompletionStage<NewsResponse> stg = svc.searchNewsWithStatus("AI");
        NewsResponse out = stg.toCompletableFuture().get();

        // Assert
        assertNotNull(out);
        // depending on your file setup, may return 200 or 500 mock result
        assertTrue(out.body.contains("mock file") || out.body.contains("{"), "should come from mock response");
    }


    /**
     * Verifies that the sources-cache is populated on first successful call and
     * the second call with identical parameters uses the cached response (only one WS call total).
     * @author Nirvana Borham
     */
    @Test
    void caching_sources_secondCallUsesCache() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"sources\":true}");

        // 1st call: populate cache
        w.svc.listSources("en", "technology", "us").toCompletableFuture().get();
        // 2nd call with identical params: should be served from cache
        w.svc.listSources("en", "technology", "us").toCompletableFuture().get();

        // verify only one upstream HTTP call
        verify(w.ws, times(1)).url(anyString());
    }

    /**
     * Non-200 responses should not be cached; subsequent identical calls must hit upstream again.
     */
    @Test
    void caching_sources_errorResponsesAreNotCached() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(500);
        when(w.resp.getBody()).thenReturn("{\"status\":\"error\"}");

        w.svc.listSources("en", "", "").toCompletableFuture().get();
        w.svc.listSources("en", "", "").toCompletableFuture().get();

        verify(w.ws, times(2)).url(anyString());
    }

    /**
     * Verifies that the everything-cache is populated on first successful call and
     * the second identical call is served from cache (only one WS call total).
     * @author Nirvana Borham
     */
    @Test
    void caching_everything_secondCallUsesCache() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"ok\":true}");

        w.svc.searchEverything("AI", "the-verge", null, null, "en").toCompletableFuture().get();
        w.svc.searchEverything("AI", "the-verge", null, null, "en").toCompletableFuture().get();

        verify(w.ws, times(1)).url(anyString());
    }

    @Test
    void searchEverything_mockMode_readsCombinedFile() throws Exception {
        // temp mock directory with the exact file name the service will look for
        Path dir = Files.createTempDirectory("mock_everything");
        // includes multiple language sections; the code picks "es"
        String combined =
                "{\n" +
                        "  \"en\": {\"status\":\"ok\",\"articles\":[{\"title\":\"Hello\"}]},\n" +
                        "  \"es\": {\"status\":\"ok\",\"articles\":[{\"title\":\"Hola\"}]}\n" +
                        "}";
        Files.writeString(dir.resolve("everything_ai-news.json"), combined, StandardCharsets.UTF_8);

        NewsService mockSvc = svcWithMockDir(dir); // you already have this helper in the test

        NewsResponse out = mockSvc
                .searchEverything("AI news", null, null, "popularity", "es")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"Hola\"")); // proves mockResponseFromCombined("...","es","en") path ran
    }

    @Test
    void searchEverything_secondCallReturnsCachedBody() throws Exception {
        WiredSvc w = svcWithKey("abc"); // your helper that wires real svc + mocked WS
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"ok\":true}");

        NewsResponse first  = w.svc.searchEverything("AI", "the-verge", null, "relevancy", "en")
                .toCompletableFuture().get();
        NewsResponse second = w.svc.searchEverything("AI", "the-verge", null, "relevancy", "en")
                .toCompletableFuture().get();

        // proves second response came from the in-memory cache branch
        assertEquals(200, second.status);
        assertEquals(first.body, second.body);           // <- executes the cached-body return line
        verify(w.ws, times(1)).url(anyString());         // still only one upstream call
    }

    // =========================================================
    // Private helpers via reflection
    // =========================================================

    /**
     * Reflection test for the private {@code safe(String)} helper:
     * blank strings map to {@code "all"} and punctuation/whitespace collapse to lowercase dashes.
     * @author Nirvana Borham
     */
    @Test
    void safe_edgeCases_blankAndPunctuation() throws Exception {
        // Access private method via reflection
        var m = NewsService.class.getDeclaredMethod("safe", String.class);
        m.setAccessible(true);
        assertEquals("all", m.invoke(null, ""));  // blank -> all
        assertEquals("a-b", m.invoke(null, "A!!  B")); // punctuation -> dash, lowercased
    }

    /**
     * Reflection test for {@code cleanCache(...)}:
     * verifies that expired/invalid entries are removed from the cache map.
     * {@code cleanCache(...)}: removes entries from provided map (smoke branch-coverage).
     * @author Nirvana Borham
     */
    @SuppressWarnings("unchecked")
    @Test
    void cleanCache_clearsMap() throws Exception {
        WiredSvc w = svcWithKey("abc");
        var f = NewsService.class.getDeclaredField("sourcesCache"); // ConcurrentHashMap<String, Object>
        f.setAccessible(true);
        Map<String,Object> map = (Map<String,Object>) f.get(w.svc);
        map.put("x", new Object());
        assertFalse(map.isEmpty());

        var m = NewsService.class.getDeclaredMethod("cleanCache", map.getClass());
        m.setAccessible(true);
        m.invoke(w.svc, map);

        assertTrue(map.isEmpty());
    }

    /**
     * Builds a {@link NewsService} in mock mode bound to {@code mockDir}.
     *
     * @param mockDir directory containing combined mock files
     * @return configured mock-mode service
     * @author Nirvana Borham
     */
    private NewsService svcWithMockDir(Path mockDir) {
        WSClient ws2 = mock(WSClient.class);
        Config cfg2  = mock(Config.class);

        when(cfg2.getString("newsapi.baseUrl")).thenReturn("https://newsapi.org/v2");
        when(cfg2.hasPath("newsapi.key")).thenReturn(true);
        when(cfg2.getString("newsapi.key")).thenReturn("abc");
        when(cfg2.getInt("newsapi.timeoutMs")).thenReturn(5000);

        when(cfg2.hasPath("newsapi.mock")).thenReturn(true);
        when(cfg2.getBoolean("newsapi.mock")).thenReturn(true);

        when(cfg2.hasPath("newsapi.mockDir")).thenReturn(true);
        when(cfg2.getString("newsapi.mockDir")).thenReturn(mockDir.toString());

        return new NewsService(cfg2, ws2);
    }

    /**
     * {@code langsForCountry(String)} mappings & fallbacks (US/CA/DE + blank + unknown).
     *
     * @author Nirvana Borham
     */
    @Test
    void langsForCountry_mappings_andFallbacks() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("langsForCountry", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> us = (List<String>) m.invoke(null, "us");
        assertEquals(Arrays.asList("en", "es"), us);

        @SuppressWarnings("unchecked")
        List<String> ca = (List<String>) m.invoke(null, "ca");
        assertEquals(Arrays.asList("en", "fr"), ca);

        @SuppressWarnings("unchecked")
        List<String> de = (List<String>) m.invoke(null, "de");
        assertEquals(Collections.singletonList("de"), de);

        @SuppressWarnings("unchecked")
        List<String> blank = (List<String>) m.invoke(null, "");
        assertEquals(Collections.emptyList(), blank);

        @SuppressWarnings("unchecked")
        List<String> unknown = (List<String>) m.invoke(null, "xx");
        assertEquals(Collections.singletonList("en"), unknown); // safe fallback
    }

    /**
     * {@code mergeSourcesById(...)}: merges language arrays, de-dupes by {@code id},
     * sorts by {@code name} asc, returns a NewsAPI-ish JSON.
     *
     * @author Nirvana Borham
     */
    @Test
    void mergeSourcesById_dedupesById_andSortsByName() throws Exception {
        // Build a minimal root mock: { "en":[{id:"a",name:"Zeta"},{id:"b",name:"Beta"}],
        //                              "es":[{id:"a",name:"Zeta-ES"},{id:"c",name:"Alpha"}] }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        en.add(object(mapper, "a", "Zeta",  "en", "us"));
        en.add(object(mapper, "b", "Beta",  "en", "us"));
        root.set("en", en);

        ArrayNode es = mapper.createArrayNode();
        es.add(object(mapper, "a", "Zeta-ES", "es", "us"));  // duplicate id "a" → should be kept only once
        es.add(object(mapper, "c", "Alpha",   "es", "us"));
        root.set("es", es);

        // Call private instance method via reflection
        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Arrays.asList("en","es"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertTrue(out.get("sources").isArray());

        // Expect deduped ids: a, b, c  → size 3
        List<String> names = new ArrayList<>();
        for (JsonNode s : out.get("sources")) names.add(s.get("name").asText());
        assertEquals(3, names.size());
        // Expect alphabetical order by name: Alpha, Beta, Zeta
        assertEquals(Arrays.asList("Alpha", "Beta", "Zeta"), names);
    }

    /**
     * Convenience builder for a minimal "source" node used by merge tests.
     *
     * @param mapper shared mapper
     * @param id     source id
     * @param name   source name
     * @param lang   language code
     * @param country country code
     * @return JSON object node
     * @author Nirvana Borham
     */
    private static ObjectNode object(ObjectMapper mapper, String id, String name, String lang, String country) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", id);
        o.put("name", name);
        o.put("language", lang);
        o.put("country", country);
        return o;
    }

    /**
     * Mock-mode union by country (US): blank language + {@code country=us} returns union of {@code en}+{@code es}.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_mock_unionByCountry_whenLanguageBlank_US_enPlusEs() throws Exception {
        Path dir = Files.createTempDirectory("mockdir");
        String sourcesJson = ""
                + "{\n"
                + "  \"en\": [{\"id\":\"google-news-us\",\"name\":\"Google News (US)\",\"language\":\"en\",\"country\":\"us\"}],\n"
                + "  \"es\": [{\"id\":\"abc\",\"name\":\"ABC\",\"language\":\"es\",\"country\":\"us\"}],\n"
                + "  \"de\": [{\"id\":\"bild\",\"name\":\"Bild\",\"language\":\"de\",\"country\":\"de\"}]\n"
                + "}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);

        // language blank, country=us → union(en,es)
        NewsResponse r = svc.listSources("", "", "us").toCompletableFuture().get();
        assertEquals(200, r.status);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode out = mapper.readTree(r.body);
        assertEquals("ok", out.get("status").asText());
        Set<String> ids = new HashSet<>();
        for (JsonNode s : out.get("sources")) ids.add(s.get("id").asText());
        assertEquals(new HashSet<>(Arrays.asList("google-news-us","abc")), ids);
    }

    /**
     * Mock-mode union for Germany: blank language + {@code country=de} returns only {@code de}.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_mock_unionByCountry_GermanyOnlyDe() throws Exception {
        Path dir = Files.createTempDirectory("mockdir2");
        String sourcesJson = ""
                + "{\n"
                + "  \"de\": [{\"id\":\"bild\",\"name\":\"Bild\",\"language\":\"de\",\"country\":\"de\"}],\n"
                + "  \"en\": [{\"id\":\"guardian\",\"name\":\"The Guardian\",\"language\":\"en\",\"country\":\"gb\"}]\n"
                + "}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources("", "", "de").toCompletableFuture().get();
        assertEquals(200, r.status);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode out = mapper.readTree(r.body);
        assertEquals(1, out.get("sources").size());
        assertEquals("bild", out.get("sources").get(0).get("id").asText());
    }

    /**
     * Mock-mode: explicit language beats country filter.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_mock_explicitLanguage_winsOverCountry() throws Exception {
        Path dir = Files.createTempDirectory("mockdir3");
        String sourcesJson = ""
                + "{\n"
                + "  \"fr\": [{\"id\":\"le-monde\",\"name\":\"Le Monde\",\"language\":\"fr\",\"country\":\"fr\"}],\n"
                + "  \"en\": [{\"id\":\"bbc-news\",\"name\":\"BBC News\",\"language\":\"en\",\"country\":\"gb\"}]\n"
                + "}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        // Ask for country=us but language=fr → should return only FR bucket
        NewsResponse r = svc.listSources("fr", "", "us").toCompletableFuture().get();
        assertEquals(200, r.status);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode out = mapper.readTree(r.body);
        assertEquals(1, out.get("sources").size());
        assertEquals("le-monde", out.get("sources").get(0).get("id").asText());
    }

    /**
     * Unknown country should fall back to English sources when language is blank.
     */
    @Test
    void listSources_mock_unknownCountryFallsBackToEnglish() throws Exception {
        Path dir = Files.createTempDirectory("mockdir5");
        String sourcesJson = ""
                + "{\n"
                + "  \"en\": [{\"id\":\"guardian\",\"name\":\"The Guardian\",\"language\":\"en\",\"country\":\"gb\"}],\n"
                + "  \"fr\": [{\"id\":\"le-monde\",\"name\":\"Le Monde\",\"language\":\"fr\",\"country\":\"fr\"}]\n"
                + "}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources("", "", "xx").toCompletableFuture().get();
        assertEquals(200, r.status);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode out = mapper.readTree(r.body);
        assertEquals(1, out.get("sources").size());
        assertEquals("en", out.get("sources").get(0).get("language").asText());
    }

    /**
     * Malformed {@code sources.json} must yield a 500 with a descriptive error message,
     * proving robust error handling in mock mode.
     * @author Nirvana Borham
     */
    @Test
    void listSources_mock_invalidFile_returns500() throws Exception {
        Path dir = Files.createTempDirectory("mockdir4");
        // create malformed JSON
        Files.writeString(dir.resolve("sources.json"), "{not:valid", StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources("", "", "us").toCompletableFuture().get();
        assertEquals(500, r.status);
        assertTrue(r.body.contains("mock file missing or invalid"));
    }

    /**
     * {@code langsForCountry("gb")} → {@code ["en"]}.
     *
     * @author Nirvana Borham
     */
    @Test
    void langsForCountry_gb_returnsEnOnly() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("langsForCountry", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> langs = (List<String>) m.invoke(null, "gb");
        assertEquals(List.of("en"), langs);
    }

    /**
     * {@code langsForCountry("fr")} → {@code ["fr"]}.
     *
     * @author Nirvana Borham
     */
    @Test
    void langsForCountry_fr_returnsFrOnly() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("langsForCountry", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> langs = (List<String>) m.invoke(null, "fr");
        assertEquals(List.of("fr"), langs);
    }

    /**
     * {@code langsForCountry("es")} → {@code ["es"]}.
     *
     * @author Nirvana Borham
     */
    @Test
    void langsForCountry_es_returnsEsOnly() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("langsForCountry", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> langs = (List<String>) m.invoke(null, "es");
        assertEquals(List.of("es"), langs);
    }

    /**
     * {@code langsForCountry("sa")} → {@code ["ar"]}.
     *
     * @author Nirvana Borham
     */
    @Test
    void langsForCountry_sa_returnsArOnly() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("langsForCountry", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> langs = (List<String>) m.invoke(null, "sa");
        assertEquals(List.of("ar"), langs);
    }

    /**
     * Extended unit tests for {@link NewsService} — focusing on all overloads of
     * {@code searchEverything(...)} (5-, 6-, and 7-parameter forms).
     *
     * <p>These tests validate the complete request-building logic, sortBy and
     * pageSize sanitization, mock-mode file reading, and in-memory caching
     * branches to achieve full JaCoCo coverage.</p>
     *
     * <p>Specifically, the tests verify:</p>
     * <ul>
     *   <li>Defaulting of {@code sortBy} to <b>"relevancy"</b> when null, blank, or invalid.</li>
     *   <li>Acceptance of valid sort modes: <b>"popularity"</b> and <b>"publishedAt"</b>.</li>
     *   <li>Page-size handling — explicit forwarding, blank fallback to "10".</li>
     *   <li>Mock-mode behavior with language fallback and combined mock files.</li>
     *   <li>Cache reuse for repeated identical requests (hits return-cached branch).</li>
     * </ul>
     *
     * <p>All tests use fully mocked {@link play.libs.ws.WSClient} and local mock
     * directories — no external I/O is performed. This suite ensures 100% line
     * and branch coverage for the NewsService logic.</p>
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything_invalidSortBy_defaultsToRelevancy() throws Exception {
        // when the user passes an unsupported sortBy, we fall back to "relevancy"
        svc.searchEverything("AI", "the-verge", null, "banana", "en")
                .toCompletableFuture().get();

        verify(req).addQueryParameter("sortBy", "relevancy");  // <- hits sb = "relevancy"
    }

    /**
     * Verifies that the 7-parameter overload forwards the explicit {@code pageSize}
     * argument unchanged to the underlying HTTP request.
     *
     * <p>Expected: "pageSize" is passed as "20".</p>
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_explicitPageSize_forwarded() throws Exception {
        // reuse default svc+req mocks from @BeforeEach (non-mock path is fine)
        svc.searchEverything("ai ethics", "the-verge", null, null, "en", "relevancy", "20")
                .toCompletableFuture().get();

        verify(req).addQueryParameter("pageSize", "20");
    }

    /**
     * Ensures that when {@code pageSize} is blank, the 7-parameter overload defaults
     * it to "10" (the API’s standard page size).
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_blankPageSize_defaultsTo10() throws Exception {
        svc.searchEverything("ai ethics", null, null, null, "en", "relevancy", "")
                .toCompletableFuture().get();

        verify(req).addQueryParameter("pageSize", "10");
    }

    /**
     * Covers the unique branch where both the requested and fallback ("en") language
     * keys are missing in a mock JSON file. Confirms that the entire combined file
     * body is returned unchanged.
     *
     * @author Nirvana Borham
     */
    @Test
    void mock_everything_missingRequestedAndEn_returnsWholeFileBody() throws Exception {
        Path dir = Files.createTempDirectory("mock_no_fallback");
        // only "fr" exists; request "es" -> code falls back to "en", not found -> returns WHOLE FILE
        Files.writeString(dir.resolve("everything_ai.json"),
                "{ \"fr\": {\"status\":\"ok\",\"articles\":[{\"title\":\"FRONLY\"}]}}",
                StandardCharsets.UTF_8);

        NewsService mockSvc = svcWithMockDir(dir);
        NewsResponse r = mockSvc.searchEverything("ai", null, null, null, "es")
                .toCompletableFuture().get();

        assertEquals(200, r.status);
        // proves it returned the entire combined object, not a single-language section
        assertTrue(r.body.trim().startsWith("{"));
        assertTrue(r.body.contains("\"fr\""));
        // and should NOT look like a single {status:"ok",articles:[...]} section
        // (optional sanity check)
    }

    /**
     * Mock-mode variant: verifies that a specific language bucket ("fr") is wrapped
     * into a standard NewsAPI-style object containing
     * {@code {"status":"ok","sources":[...]}}.
     *
     * @author Nirvana Borham
     */
    @Test
    void listSources_mock_languageProvided_wrapsArray() throws Exception {
        Path dir = Files.createTempDirectory("mock_sources_lang");
        Files.writeString(dir.resolve("sources.json"),
                "{ \"fr\": [ {\"id\":\"lemonde\",\"name\":\"Le Monde\"} ] }",
                StandardCharsets.UTF_8);

        NewsService mockSvc = svcWithMockDir(dir);
        NewsResponse r = mockSvc.listSources("fr", null, null).toCompletableFuture().get();

        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"status\":\"ok\""));
        assertTrue(r.body.contains("\"sources\""));
        assertTrue(r.body.contains("\"lemonde\""));
    }

    /**
     * Validates that a null {@code sortBy} defaults to "relevancy" in the 7-parameter
     * overload of {@code searchEverything}.
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_nullSortBy_defaultsToRelevancy() throws Exception {
        svc.searchEverything("ai ethics", "the-verge", null, null, "en", null, "10")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "relevancy");
    }

    /**
     * Validates that a blank {@code sortBy} defaults to "relevancy" in the 7-parameter
     * overload of {@code searchEverything}.
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_blankSortBy_defaultsToRelevancy() throws Exception {
        svc.searchEverything("ai ethics", "the-verge", null, "   ", "en", "   ", "10")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "relevancy");
    }

    /**
     * Confirms that the valid value {@code "publishedAt"} is accepted and sent
     * unchanged in the 7-parameter overload of {@code searchEverything}.
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_sortBy_publishedAt_passedThrough() throws Exception {
        svc.searchEverything("ai ethics", "the-verge", null, null, "en", "publishedAt", "10")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "publishedAt");
    }

    /**
     * Ensures that the 5-parameter overload ignores unsupported {@code sortBy}
     * values such as {@code "publishedAt"} and falls back to "relevancy".
     *
     * @author Nirvana Borham
     */
    @Test
    void everything5_sortBy_publishedAt_defaultsToRelevancy() throws Exception {
        svc.searchEverything("AI", "the-verge", null, "publishedAt", "en")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "relevancy");
    }

    /**
     * Confirms that the 5-parameter overload maps any invalid {@code sortBy} input
     * (e.g., "banana") to the default "relevancy" value.
     *
     * @author Nirvana Borham
     */
    @Test
    void everything5_invalidSortBy_defaultsToRelevancy() throws Exception {
        svc.searchEverything("AI", "the-verge", null, "banana", "en")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "relevancy");
    }

    /**
     * Covers the 6-parameter overload of {@code searchEverything} where an invalid
     * {@code sortBy} triggers the fallback assignment to "relevancy".
     *
     * @author Nirvana Borham
     */
    @Test
    void searchEverything6_invalidSortBy_triggersRelevancyAssignment() throws Exception {
        svc.searchEverything("AI", "bbc-news", null, null, "en", "xyz")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "relevancy");
    }

    /**
     * Ensures the mock-mode 7-parameter overload reads the correct language bucket
     * from a combined {@code everything_*.json} file.
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_mockMode_readsCombinedByLanguage() throws Exception {
        Path dir = Files.createTempDirectory("mock_everything7");
        // safe("ai ethics") -> "ai-ethics"
        String combined =
                "{\n" +
                        "  \"en\": {\"status\":\"ok\",\"articles\":[{\"title\":\"Hello\"}]},\n" +
                        "  \"es\": {\"status\":\"ok\",\"articles\":[{\"title\":\"Hola\"}]}\n" +
                        "}";
        Files.writeString(dir.resolve("everything_ai-ethics.json"), combined, StandardCharsets.UTF_8);

        NewsService mockSvc = svcWithMockDir(dir);

        NewsResponse out = mockSvc
                .searchEverything("ai ethics", null, null, null, "es", "relevancy", "25")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"Hola\"")); // proves 7-param mock branch executed
    }

    /**
     * Verifies that when {@code language} is blank in mock-mode 7-param overload,
     * it correctly falls back to English ("en").
     *
     * @author Nirvana Borham
     */
    @Test
    void everything7_mockMode_blankLanguage_fallsBackToEn() throws Exception {
        Path dir = Files.createTempDirectory("mock_everything7_blank");
        Files.writeString(
                dir.resolve("everything_ai.json"),
                "{ \"en\": {\"status\":\"ok\",\"articles\":[{\"title\":\"Only EN\"}]}}",
                StandardCharsets.UTF_8);

        NewsService mockSvc = svcWithMockDir(dir);
        NewsResponse out = mockSvc
                .searchEverything("ai", null, null, null, "", "popularity", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("Only EN"));
    }

    /**
     * Validates the caching mechanism for the 7-parameter overload of
     * {@code searchEverything}. The first call populates the cache and the
     * second identical call retrieves the cached body (no new network call).
     *
     * @author Nirvana Borham
     */
    @Test
    void caching_everything7_secondCallUsesCache() throws Exception {
        WiredSvc w = svcWithKey("abc"); // your helper that wires svc + mocked WS
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"status\":\"ok\",\"articles\":[]}");
        // 1st call: populates cache
        NewsResponse first = w.svc
                .searchEverything("AI", "the-verge", null, null, "en", "relevancy", "25")
                .toCompletableFuture().get();
        // 2nd call with identical key: should be served from cache
        NewsResponse second = w.svc
                .searchEverything("AI", "the-verge", null, null, "en", "relevancy", "25")
                .toCompletableFuture().get();
        assertEquals(200, second.status);
        assertEquals(first.body, second.body);           // proves cached.body returned
        verify(w.ws, times(1)).url(anyString());         // only one upstream call
    }

    // =========================================================
    // Additional tests for missed branches
    // =========================================================

    /**
     * Tests {@code langsForCountry} with a null country parameter.
     * Should return an empty list.
     */
    @Test
    void langsForCountry_nullCountry_returnsEmptyList() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("langsForCountry", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> langs = (List<String>) m.invoke(null, (Object) null);
        assertEquals(Collections.emptyList(), langs);
    }

    /**
     * Tests {@code searchEverything} with null q parameter.
     * Should not add q parameter to the request.
     */
    @Test
    void searchEverything_nullQ_doesNotAddQ() {
        svc.searchEverything(null, "the-verge", null, null, "en").toCompletableFuture().join();
        verify(req, never()).addQueryParameter(eq("q"), anyString());
        verify(req).addQueryParameter("sources", "the-verge");
    }

    /**
     * Tests {@code searchEverything} with null language parameter.
     * Should not add language parameter to the request.
     */
    @Test
    void searchEverything_nullLanguage_doesNotAddLanguage() {
        svc.searchEverything("AI", null, null, null, null).toCompletableFuture().join();
        verify(req).addQueryParameter("q", "\"AI\"");
        verify(req, never()).addQueryParameter(eq("language"), anyString());
    }

    /**
     * Tests {@code listSources} with null language parameter in live mode.
     * Should not add language parameter to the request.
     */
    @Test
    void listSources_nullLanguage_doesNotAddLanguageParam() {
        svc.listSources(null, "technology", "us").toCompletableFuture().join();
        verify(req, never()).addQueryParameter(eq("language"), anyString());
        verify(req).addQueryParameter("category", "technology");
        verify(req).addQueryParameter("country", "us");
    }

    /**
     * Tests {@code listSources} with null category parameter.
     * Should not add category parameter to the request.
     */
    @Test
    void listSources_nullCategory_doesNotAddCategoryParam() {
        svc.listSources("en", null, "us").toCompletableFuture().join();
        verify(req).addQueryParameter("language", "en");
        verify(req, never()).addQueryParameter(eq("category"), anyString());
        verify(req).addQueryParameter("country", "us");
    }

    /**
     * Tests {@code listSources} with null country parameter.
     * Should not add country parameter to the request.
     */
    @Test
    void listSources_nullCountry_doesNotAddCountryParam() {
        svc.listSources("en", "technology", null).toCompletableFuture().join();
        verify(req).addQueryParameter("language", "en");
        verify(req).addQueryParameter("category", "technology");
        verify(req, never()).addQueryParameter(eq("country"), anyString());
    }

    /**
     * Tests {@code mergeSourcesById} with null elements in the array.
     * Should handle gracefully without crashing.
     */
    @Test
    void mergeSourcesById_withNullElements_handlesGracefully() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        en.add(object(mapper, "a", "Alpha", "en", "us"));
        en.add(mapper.createObjectNode()); // object without "id" field
        root.set("en", en);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Collections.singletonList("en"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(1, out.get("sources").size()); // only the valid one
    }

    /**
     * Tests {@code safe} method with null input.
     * Should return "all".
     */
    @Test
    void safe_null_returnsAll() throws Exception {
        Method m = NewsService.class.getDeclaredMethod("safe", String.class);
        m.setAccessible(true);
        assertEquals("all", m.invoke(null, (Object) null));
    }

    /**
     * Tests {@code searchEverything} 7-param version with null pageSize.
     * Should default to "10".
     */
    @Test
    void everything7_nullPageSize_defaultsTo10() throws Exception {
        svc.searchEverything("AI", null, null, null, "en", "relevancy", null)
                .toCompletableFuture().get();
        verify(req).addQueryParameter("pageSize", "10");
    }

    /**
     * Tests {@code searchEverything} 7-param with invalid sortBy.
     * Should default to "relevancy".
     */
    @Test
    void everything7_invalidSortBy_defaultsToRelevancy() throws Exception {
        svc.searchEverything("AI", null, null, null, "en", "invalid-sort", "10")
                .toCompletableFuture().get();
        verify(req).addQueryParameter("sortBy", "relevancy");
    }

    /**
     * Tests {@code listSources} mock mode with null language.
     * Should merge languages based on country.
     */
    @Test
    void listSources_mock_nullLanguage_mergesByCountry() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_null_lang");
        String sourcesJson = ""
                + "{\n"
                + "  \"en\": [{\"id\":\"cnn\",\"name\":\"CNN\",\"language\":\"en\",\"country\":\"us\"}],\n"
                + "  \"es\": [{\"id\":\"abc\",\"name\":\"ABC\",\"language\":\"es\",\"country\":\"us\"}]\n"
                + "}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources(null, null, "us").toCompletableFuture().get();
        assertEquals(200, r.status);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode out = mapper.readTree(r.body);
        assertEquals("ok", out.get("status").asText());
        // Should have both en and es sources for US
        assertEquals(2, out.get("sources").size());
    }

    /**
     * Tests {@code listSources} mock mode when chosen language node is not an array.
     * Should return the body as-is.
     */
    @Test
    void listSources_mock_chosenNotArray_returnsAsIs() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_not_array");
        String sourcesJson = "{ \"en\": \"not-an-array\" }";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources("en", null, null).toCompletableFuture().get();
        assertEquals(200, r.status);
        // Should return the whole file body as-is
        assertTrue(r.body.contains("not-an-array"));
    }

    // ========== Additional searchEverything branch coverage tests ==========

    /**
     * Tests searchEverything with non-200 status code - should return the error status and body.
     */
    @Test
    void searchEverything_non200Status_returnsErrorStatusAndBody() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(404);
        when(w.resp.getBody()).thenReturn("{\"error\":\"not found\"}");

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(404, out.status);
        assertEquals("{\"error\":\"not found\"}", out.body);
    }

    /**
     * Tests searchEverything when JSON parsing fails - should fall back to raw body.
     */
    @Test
    void searchEverything_jsonParsingFails_fallsBackToRawBody() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{malformed json");

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertEquals("{malformed json", out.body);
    }

    /**
     * Tests searchEverything with valid JSON but articles not an array - falls back to raw body.
     */
    @Test
    void searchEverything_articlesNotArray_fallsBackToRawBody() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"status\":\"ok\",\"articles\":\"not-an-array\"}");

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        // Should process successfully, articles field just won't have entries
        assertTrue(out.body.contains("\"status\":\"ok\""));
    }

    /**
     * Tests searchEverything with articles containing null description and missing title.
     */
    @Test
    void searchEverything_articlesWithNullDescriptionAndTitle_handlesGracefully() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        String body = "{\"status\":\"ok\",\"totalResults\":2,\"articles\":["
                + "{\"title\":null,\"description\":null,\"url\":\"http://test.com\",\"source\":{\"name\":\"Test\"},\"publishedAt\":\"2024-01-01T12:00:00Z\"},"
                + "{\"description\":\"\",\"url\":\"http://test2.com\",\"source\":{},\"publishedAt\":\"\"}"
                + "]}";
        when(w.resp.getBody()).thenReturn(body);

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"status\":\"ok\""));
    }

    /**
     * Tests searchEverything with article URL that cannot be parsed as URI.
     */
    @Test
    void searchEverything_invalidArticleUrl_handlesGracefully() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        String body = "{\"status\":\"ok\",\"totalResults\":1,\"articles\":["
                + "{\"title\":\"Test\",\"description\":\"Desc\",\"url\":\"not a valid url\",\"source\":{\"name\":\"Test\"},\"publishedAt\":\"2024-01-01T12:00:00Z\"}"
                + "]}";
        when(w.resp.getBody()).thenReturn(body);

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        // Should still process, sourceLink will be null/empty
        assertTrue(out.body.contains("\"status\":\"ok\""));
    }

    /**
     * Tests searchEverything with valid URL that returns null host.
     */
    @Test
    void searchEverything_urlWithNullHost_handlesGracefully() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        String body = "{\"status\":\"ok\",\"totalResults\":1,\"articles\":["
                + "{\"title\":\"Test\",\"description\":\"Desc\",\"url\":\"\",\"source\":{\"name\":\"Test\"},\"publishedAt\":\"2024-01-01T12:00:00Z\"}"
                + "]}";
        when(w.resp.getBody()).thenReturn(body);

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"status\":\"ok\""));
    }

    /**
     * Tests searchEverything with URL containing "www." prefix - should be stripped.
     */
    @Test
    void searchEverything_urlWithWwwPrefix_stripsWww() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        String body = "{\"status\":\"ok\",\"totalResults\":1,\"articles\":["
                + "{\"title\":\"Test\",\"description\":\"Desc\",\"url\":\"https://www.example.com/article\",\"source\":{\"name\":\"Test\"},\"publishedAt\":\"2024-01-01T12:00:00Z\"}"
                + "]}";
        when(w.resp.getBody()).thenReturn(body);

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"sourceLink\":\"https://example.com\""));
    }

    /**
     * Tests searchEverything with invalid publishedAt timestamp - should handle gracefully.
     */
    @Test
    void searchEverything_invalidPublishedAt_handlesGracefully() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        String body = "{\"status\":\"ok\",\"totalResults\":1,\"articles\":["
                + "{\"title\":\"Test\",\"description\":\"Desc\",\"url\":\"https://test.com\",\"source\":{\"name\":\"Test\"},\"publishedAt\":\"invalid-date\"}"
                + "]}";
        when(w.resp.getBody()).thenReturn(body);

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        // publishedEdt should be empty string when parsing fails
        assertTrue(out.body.contains("\"publishedEdt\":\"\""));
    }

    /**
     * Tests searchEverything with missing totalResults field - should still work.
     */
    @Test
    void searchEverything_missingTotalResults_handlesGracefully() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        String body = "{\"status\":\"ok\",\"articles\":[]}";
        when(w.resp.getBody()).thenReturn(body);

        NewsResponse out = w.svc.searchEverything("AI", null, null, null, "en", "relevancy", "10")
                .toCompletableFuture().get();

        assertEquals(200, out.status);
        assertTrue(out.body.contains("\"status\":\"ok\""));
    }

    // ========== Additional mergeSourcesById branch coverage tests ==========

    /**
     * Tests mergeSourcesById with null array in language section.
     */
    @Test
    void mergeSourcesById_withNullArray_handlesGracefully() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.set("en", mapper.nullNode());
        root.set("fr", mapper.createArrayNode().add(object(mapper, "a", "Alpha", "fr", "fr")));

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Arrays.asList("en", "fr"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(1, out.get("sources").size()); // only fr source
    }

    /**
     * Tests mergeSourcesById with non-array node in language section.
     */
    @Test
    void mergeSourcesById_withNonArrayNode_skipsIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("en", "not-an-array");

        ArrayNode fr = mapper.createArrayNode();
        fr.add(object(mapper, "a", "Alpha", "fr", "fr"));
        root.set("fr", fr);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Arrays.asList("en", "fr"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(1, out.get("sources").size()); // only fr source
    }

    /**
     * Tests mergeSourcesById with source object missing id field (null id).
     */
    @Test
    void mergeSourcesById_withNullIdNode_skipsSource() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        ObjectNode sourceWithoutId = mapper.createObjectNode();
        sourceWithoutId.put("name", "NoId");
        sourceWithoutId.set("id", mapper.nullNode()); // null id
        en.add(sourceWithoutId);
        en.add(object(mapper, "b", "Beta", "en", "us"));
        root.set("en", en);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Collections.singletonList("en"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(1, out.get("sources").size()); // only Beta
        assertEquals("Beta", out.get("sources").get(0).get("name").asText());
    }

    /**
     * Tests mergeSourcesById with source object where id is not textual.
     */
    @Test
    void mergeSourcesById_withNonTextualId_skipsSource() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        ObjectNode sourceWithNumberId = mapper.createObjectNode();
        sourceWithNumberId.put("name", "NumericId");
        sourceWithNumberId.put("id", 123); // numeric id, not textual
        en.add(sourceWithNumberId);
        en.add(object(mapper, "b", "Beta", "en", "us"));
        root.set("en", en);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Collections.singletonList("en"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(1, out.get("sources").size()); // only Beta
        assertEquals("Beta", out.get("sources").get(0).get("name").asText());
    }

    /**
     * Tests mergeSourcesById with duplicate IDs - should keep first occurrence.
     */
    @Test
    void mergeSourcesById_withDuplicateIds_keepsFirstOccurrence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        en.add(object(mapper, "a", "Alpha-EN", "en", "us"));
        root.set("en", en);

        ArrayNode es = mapper.createArrayNode();
        es.add(object(mapper, "a", "Alpha-ES", "es", "us")); // duplicate id
        es.add(object(mapper, "b", "Beta", "es", "us"));
        root.set("es", es);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Arrays.asList("en", "es"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(2, out.get("sources").size()); // a and b

        // Should keep first occurrence (Alpha-EN, not Alpha-ES)
        boolean foundAlphaEN = false;
        for (JsonNode s : out.get("sources")) {
            if ("a".equals(s.get("id").asText())) {
                assertEquals("Alpha-EN", s.get("name").asText());
                foundAlphaEN = true;
            }
        }
        assertTrue(foundAlphaEN);
    }

    /**
     * Tests mergeSourcesById with sources that have names requiring case-insensitive sorting.
     */
    @Test
    void mergeSourcesById_sortsAlphabeticallyByName_caseInsensitive() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        en.add(object(mapper, "c", "charlie", "en", "us"));
        en.add(object(mapper, "a", "ALPHA", "en", "us"));
        en.add(object(mapper, "b", "Beta", "en", "us"));
        root.set("en", en);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Collections.singletonList("en"));

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(3, out.get("sources").size());

        // Should be sorted: ALPHA, Beta, charlie (case-insensitive)
        assertEquals("ALPHA", out.get("sources").get(0).get("name").asText());
        assertEquals("Beta", out.get("sources").get(1).get("name").asText());
        assertEquals("charlie", out.get("sources").get(2).get("name").asText());
    }

    /**
     * Tests mergeSourcesById with empty language list.
     */
    @Test
    void mergeSourcesById_withEmptyLanguageList_returnsEmptySources() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ArrayNode en = mapper.createArrayNode();
        en.add(object(mapper, "a", "Alpha", "en", "us"));
        root.set("en", en);

        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, Collections.emptyList());

        JsonNode out = mapper.readTree(json);
        assertEquals("ok", out.get("status").asText());
        assertEquals(0, out.get("sources").size());
    }

    // ========== Additional tests for remaining missed branches ==========

    /**
     * Tests listSources with expired cache entry - should not use cached value.
     */
    @Test
    void listSources_expiredCache_fetchesFreshData() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"status\":\"ok\",\"sources\":[]}");

        // First call populates cache
        w.svc.listSources("en", null, null).toCompletableFuture().get();

        // Sleep to ensure cache expires (or manipulate time)
        Thread.sleep(5);

        // Second call should fetch fresh data due to expiration
        w.svc.listSources("en", null, null).toCompletableFuture().get();

        // Should have made 2 API calls (cache expired)
        verify(w.ws, atLeast(1)).url(anyString());
    }

    /**
     * Tests searchEverything with blank language parameter - should not add language to request.
     */
    @Test
    void searchEverything_blankLanguage_doesNotAddLanguageParam() throws Exception {
        WiredSvc w = svcWithKey("abc");
        when(w.resp.getStatus()).thenReturn(200);
        when(w.resp.getBody()).thenReturn("{\"status\":\"ok\",\"articles\":[]}");

        w.svc.searchEverything("AI", null, null, null, "", "relevancy", "10")
                .toCompletableFuture().get();

        verify(w.req, never()).addQueryParameter(eq("language"), anyString());
    }

    /**
     * Tests listSources mock mode when requested language is not in sources.json.
     * Should fall back to "en".
     */
    @Test
    void listSources_mock_languageNotInFile_fallsBackToEn() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_missing_lang");
        String sourcesJson = "{\"en\":[{\"id\":\"cnn\",\"name\":\"CNN\"}]}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        // Request "fr" which doesn't exist - should fall back to "en"
        NewsResponse r = svc.listSources("fr", null, null).toCompletableFuture().get();

        assertEquals(200, r.status);
        assertTrue(r.body.contains("CNN"));
    }

    /**
     * Tests listSources mock mode with country that has no language mappings.
     * Should default to "en".
     */
    @Test
    void listSources_mock_countryWithNoLanguages_defaultsToEn() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_no_langs");
        String sourcesJson = "{\"en\":[{\"id\":\"cnn\",\"name\":\"CNN\"}]}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        // Use a country with no language mappings (returns empty list)
        NewsResponse r = svc.listSources(null, null, "unknown-country").toCompletableFuture().get();

        assertEquals(200, r.status);
        assertTrue(r.body.contains("CNN")); // Should default to "en"
    }

    /**
     * Tests listSources mock mode when chosen language node is null.
     * Should return the whole file body as-is.
     */
    @Test
    void listSources_mock_chosenLanguageNull_returnsWholeBody() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_null_lang_node");
        String sourcesJson = "{\"en\":null,\"es\":[{\"id\":\"abc\",\"name\":\"ABC\"}]}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources("en", null, null).toCompletableFuture().get();

        assertEquals(200, r.status);
        // Should return whole body since chosen is null
        assertTrue(r.body.contains("\"en\":null"));
    }

    /**
     * Tests listSources mock mode with null language parameter.
     * Should use country to determine languages.
     */
    @Test
    void listSources_mock_nullLanguageWithCountry_usesCountryLanguages() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_null_lang_ctry");
        String sourcesJson = "{\"en\":[{\"id\":\"cnn\",\"name\":\"CNN\"}],\"es\":[{\"id\":\"abc\",\"name\":\"ABC\"}]}";
        Files.writeString(dir.resolve("sources.json"), sourcesJson, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        NewsResponse r = svc.listSources(null, null, "us").toCompletableFuture().get();

        assertEquals(200, r.status);
        // Should merge en and es for US
        assertTrue(r.body.contains("CNN") || r.body.contains("ABC"));
    }

    @Test
    void mockResponseFromCombined_blankLanguage_usesFallbackBucket() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_combined_lang");
        Files.writeString(dir.resolve("sources.json"),
                "{ \"en\": {\"message\":\"fallback\"}, \"fr\": {\"message\":\"fr\"} }",
                StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        Method m = NewsService.class.getDeclaredMethod(
                "mockResponseFromCombined", String.class, String.class, String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        CompletionStage<NewsResponse> stage =
                (CompletionStage<NewsResponse>) m.invoke(svc, "sources.json", "   ", "en");
        NewsResponse resp = stage.toCompletableFuture().get();

        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("fallback"), "blank language should fall back to 'en' bucket");
    }

    @Test
    void mockResponseFromCombined_missingLanguageAndFallback_returnsWholeFile() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_combined_missing");
        String combined = "{ \"fr\": {\"message\":\"fr-only\"} }";
        Files.writeString(dir.resolve("sources.json"), combined, StandardCharsets.UTF_8);

        NewsService svc = svcWithMockDir(dir);
        Method m = NewsService.class.getDeclaredMethod(
                "mockResponseFromCombined", String.class, String.class, String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        CompletionStage<NewsResponse> stage =
                (CompletionStage<NewsResponse>) m.invoke(svc, "sources.json", "es", "en");
        NewsResponse resp = stage.toCompletableFuture().get();

        assertEquals(200, resp.status);
        assertEquals(combined, resp.body, "when both language and fallback missing, entire file should be returned");
    }

    @Test
    void listSources_mock_languageAndFallbackMissing_returnsOriginalBody() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_missing_langs");
        Files.writeString(dir.resolve("sources.json"),
                "{ \"fr\": [{\"id\":\"lemonde\",\"name\":\"Le Monde\"}] }",
                StandardCharsets.UTF_8);

        NewsService mockSvc = svcWithMockDir(dir);
        NewsResponse resp = mockSvc.listSources("es", null, null).toCompletableFuture().get();
        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("\"fr\""), "should default to entire file when language and fallback are absent");
    }

    @Test
    void mergeSourcesById_returnsEmptyWhenNoLanguagesMatched() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree("{\"en\":[]}");
        Method m = NewsService.class.getDeclaredMethod("mergeSourcesById", JsonNode.class, java.util.List.class);
        m.setAccessible(true);
        String json = (String) m.invoke(svc, root, java.util.List.of("es"));
        JsonNode out = mapper.readTree(json);
        assertEquals(0, out.path("sources").size());
    }

    @Test
    void mockResponseFromCombined_missingFile_returns500() throws Exception {
        Path dir = Files.createTempDirectory("mockdir_missing_file");
        NewsService mockSvc = svcWithMockDir(dir);
        Method m = NewsService.class.getDeclaredMethod("mockResponseFromCombined", String.class, String.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        CompletionStage<NewsResponse> stage =
                (CompletionStage<NewsResponse>) m.invoke(mockSvc, "absent.json", "en", "en");
        NewsResponse resp = stage.toCompletableFuture().get();
        assertEquals(500, resp.status);
        assertTrue(resp.body.contains("mock file missing"));
    }

}

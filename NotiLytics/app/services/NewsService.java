package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import models.Article;
import play.libs.Json;
import play.libs.ws.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Service responsible for interacting with the external News API.
 *
 * <p>This class handles all HTTP requests to the News API, manages optional caching,
 * provides a mock mode for offline testing, and maps API errors to standardized responses.</p>
 *
 * <p>Core functions:</p>
 * <ul>
 *     <li>{@link #listSources(String, String, String)} — Retrieve available news sources</li>
 *     <li>{@link #searchEverything(String, String, String, String, String, String)} — Search articles</li>
 *     <li>{@link #searchNewsWithStatus(String)} — Perform keyword searches with error handling</li>
 * </ul>
 *
 * <p>Supports both live and mock modes as defined in <code>application.conf</code>.</p>
 *
 * @author Nirvana Borham
 */
@Singleton
public class NewsService {

    /** The asynchronous HTTP client used to make API calls. */
    private final WSClient ws;

    /** The base URL for the News API (e.g., https://newsapi.org/v2). */
    private final String baseUrl;

    /** The API key for authenticating News API requests. */
    private final String apiKey;

    /** Timeout for requests, in milliseconds. */
    private final int timeoutMs;

    /** Mock mode flags and directory path for static JSON files. */
    private final boolean mock;
    private final String mockDir;

    /** Cache lifetime in milliseconds (15 minutes). */
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L; // 15 minutes

    /**
     * Simple structure for cached entries.
     *
     * @author Nirvana Borham
     */
    private static final class CacheEntry {
        final String body;
        final long expiresAt;
        CacheEntry(String body, long expiresAt) {
            this.body = body;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Creates a cache entry with a body and expiry timestamp.
     *
     * @param body      cached JSON payload
     * @param expiresAt epoch millis when this entry becomes invalid
     * @author Nirvana Borham
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> sourcesCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> searchCache  =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** JSON parser and builder. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Constructs a {@code NewsService} instance by injecting configuration and HTTP client dependencies.
     * <p>
     * Reads the base URL, API key, and timeout from {@code application.conf}.
     * Logs a warning if the API key is missing, as it is required for successful API calls.
     * </p>
     *
     * @param config the application configuration containing News API parameters
     * @param ws     the Play WSClient for performing asynchronous HTTP requests
     * @author Nirvana Borham
     */
    @Inject
    public NewsService(Config config, WSClient ws) {
        this.ws       = ws;
        this.baseUrl  = config.getString("newsapi.baseUrl");
        this.apiKey   = config.hasPath("newsapi.key") ? config.getString("newsapi.key") : "";
        this.timeoutMs = config.getInt("newsapi.timeoutMs");

        this.mock     = config.hasPath("newsapi.mock") && config.getBoolean("newsapi.mock");
        this.mockDir  = config.hasPath("newsapi.mockDir") ? config.getString("newsapi.mockDir") : "conf/mock";

        if (!mock && this.apiKey.isBlank()) {
            System.err.println("[WARN] NEWS_API_KEY not configured — API calls may fail.");
        }
        if (mock) {
            System.out.println("[INFO] NewsService running in MOCK MODE — no real API calls.");
        }
    }

    /* ======================================================
       Utility Methods for Cache and Mock Behavior
       ====================================================== */

    /**
     * Removes expired entries from a given in-memory cache.
     *
     * @param map the concurrent hash map cache to clean
     * @author Nirvana Borham
     */
    public void cleanCache(java.util.concurrent.ConcurrentHashMap<String, ?> map) {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> {
            Object v = e.getValue();
            if (!(v instanceof CacheEntry)) return true;
            return ((CacheEntry) v).expiresAt <= now;
        });
    }

    /**
     * Generates a list of languages typically associated with a given country.
     *
     * <p>This is used in mock mode to merge multiple language sections in
     * {@code sources.json} when the "All Languages" filter is chosen.</p>
     *
     * @param country two-letter ISO country code (e.g. {@code "us"}, {@code "ca"}, {@code "fr"})
     * @return a list of language codes to merge (never {@code null})
     * @author Nirvana Borham
     */
    private static java.util.List<String> langsForCountry(String country) {
        if (country == null || country.isBlank()) return java.util.List.of();
        switch (country.toLowerCase()) {
            case "us": return java.util.List.of("en","es");
            case "ca": return java.util.List.of("en","fr");
            case "gb": return java.util.List.of("en");
            case "fr": return java.util.List.of("fr");
            case "de": return java.util.List.of("de");
            case "es": return java.util.List.of("es");
            case "sa": return java.util.List.of("ar");
            default:   return java.util.List.of("en"); // safe fallback
        }
    }

    /**
     * Combines multiple language-specific sections from a JSON file
     * (such as {@code sources.json}) into a single "merged" JSON object.
     *
     * <p>Used when the language filter is blank but a country is selected,
     * ensuring the "All Languages" option shows all sources under that country.</p>
     *
     * @param root  the root JSON node containing language keys
     * @param langs list of language codes to merge (e.g., {@code ["en","es"]})
     * @return a serialized JSON string containing merged sources in NewsAPI-like shape
     * @throws Exception if the JSON is malformed or merging fails
     * @author Nirvana Borham
     */
    private String mergeSourcesById(JsonNode root, java.util.List<String> langs) throws Exception {
        // root is the parsed JSON from conf/mock/sources.json where each top-level field is a language code
        java.util.Map<String, JsonNode> byId = new java.util.HashMap<>();
        for (String l : langs) {
            JsonNode arr = root.get(l);
            if (arr != null && arr.isArray()) {
                for (JsonNode s : arr) {
                    JsonNode idNode = s.get("id");
                    if (idNode != null && idNode.isTextual()) {
                        byId.putIfAbsent(idNode.asText(), s);
                    }
                }
            }
        }
        // build { "status":"ok", "sources":[ ... ] } to match NewsAPI shape
        ObjectNode out = mapper.createObjectNode();
        out.put("status", "ok");
        com.fasterxml.jackson.databind.node.ArrayNode sources = mapper.createArrayNode();
        byId.values().stream()
                .sorted((a,b) -> a.get("name").asText("").compareToIgnoreCase(b.get("name").asText("")))
                .forEach(sources::add);
        out.set("sources", sources);
        return mapper.writeValueAsString(out);
    }


    /**
     * Sanitizes input strings for use in filenames or cache keys.
     *
     * @param s the input string (e.g., user query or filter)
     * @return lowercase safe string with non-alphanumeric characters replaced by dashes; {@code "all"} if blank/null
     * @author Nirvana Borham
     */
    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "all" : s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    /**
     * Reads a mock JSON file containing multiple language sections,
     * and returns only the section corresponding to the requested language.
     *
     * @param filename    name of the mock file (e.g., {@code "sources.json"})
     * @param language    desired language code (may be blank)
     * @param fallbackLang fallback language if the desired one is not found
     * @return a {@link CompletionStage} with a {@link NewsResponse} containing the selected JSON section
     * @author Nirvana Borham
     */
    private CompletionStage<NewsResponse> mockResponseFromCombined(String filename, String language, String fallbackLang) {
        try {
            var path = Paths.get(mockDir, filename);
            String body = Files.readString(path, StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(body);

            String lang = (language == null || language.isBlank()) ? fallbackLang : language;
            JsonNode chosen = root.has(lang) ? root.get(lang) : root.get(fallbackLang);

            String out = (chosen == null) ? body : mapper.writeValueAsString(chosen);
            return CompletableFuture.completedFuture(new NewsResponse(200, out));
        } catch (Exception e) {
            String err = Json.newObject()
                    .put("error", "mock file missing or invalid: " + filename)
                    .toString();
            return CompletableFuture.completedFuture(new NewsResponse(500, err));
        }
    }

    /// -----------------------------------------------------------
    // Core public API methods
    // -----------------------------------------------------------

    /**
     * Performs a search request using the News API and maps errors to descriptive responses.
     * <p>
     * Sends a query string to the {@code /everything} endpoint and handles
     * various status codes with custom JSON error messages. If the API key is missing,
     * returns an immediate 503 response without performing any network call.
     * </p>
     *
     * <ul>
     *     <li>401 or 403 → 502 "Invalid API key or unauthorized"</li>
     *     <li>429 → 429 "Rate limit exceeded"</li>
     *     <li>5xx → 502 "Upstream service unavailable"</li>
     *     <li>Exceptions → 500 "Request failed: {error}"</li>
     * </ul>
     *
     * @param query the user-entered keyword or phrase
     * @return a {@link CompletionStage} of {@link NewsResponse} containing the status code and response body
     * @author Nirvana Borham
     */
    public CompletionStage<NewsResponse> searchNewsWithStatus(String query) {
        if (mock) {
            String file = "everything_" + safe(query) + ".json";
            return mockResponseFromCombined(file, "en", "en");
        }

        if (apiKey.isBlank()) {
            ObjectNode error = Json.newObject()
                    .put("error", "NEWS_API_KEY not configured. Please set it as an environment variable.");
            return CompletableFuture.completedFuture(new NewsResponse(503, error.toString()));
        }

        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/everything?q=" + q + "&language=en&pageSize=10&sortBy=relevancy";

        WSRequest req = ws.url(url)
                .addHeader("X-Api-Key", apiKey)
                .setRequestTimeout(Duration.ofMillis(timeoutMs));

        return req.get().thenApply(r -> {
            int s = r.getStatus();
            if (s == 401 || s == 403) {
                return new NewsResponse(502, Json.newObject().put("error", "Invalid API key or unauthorized").toString());
            } else if (s == 429) {
                return new NewsResponse(429, Json.newObject().put("error", "Rate limit exceeded").toString());
            } else if (s >= 500) {
                return new NewsResponse(502, Json.newObject().put("error", "Upstream service unavailable").toString());
            } else {
                return new NewsResponse(s, r.getBody());
            }
        }).exceptionally(e ->
                new NewsResponse(500, Json.newObject().put("error", "Request failed: " + e.getMessage()).toString())
        );
    }

    /**
     * Retrieves a list of news sources available in the News API.
     * <p>
     * The list can be optionally filtered by language, category, or country.
     * If the language is not specified, it defaults to {@code "en"}.
     * </p>
     *
     * @param language the desired language (e.g., {@code "en"}, {@code "fr"}); defaults to {@code "en"}
     * @param category the news category (optional; e.g., {@code "technology"})
     * @param country  the country code (optional; e.g., {@code "us"})
     * @return a {@link CompletionStage} of {@link NewsResponse} with the list of sources
     * @author Nirvana Borham
     */
    public CompletionStage<NewsResponse> listSources(String language, String category, String country) {
        // --- MOCK MODE: read from conf/mock/sources.json (supports combined per-language file) ---
        if (mock) {
            try {
                String body = Files.readString(Paths.get(mockDir, "sources.json"), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(body);

                // If a specific language is chosen, return that bucket (fallback to en)
                if (language != null && !language.isBlank()) {
                    JsonNode chosen = root.has(language) ? root.get(language) : root.get("en");
                    String out;
                    if (chosen != null && chosen.isArray()) {
                        ObjectNode ok = mapper.createObjectNode().put("status", "ok");
                        ok.set("sources", chosen);
                        out = mapper.writeValueAsString(ok);
                    } else {
                        out = body; // as-is if shape unexpected
                    }
                    return CompletableFuture.completedFuture(new NewsResponse(200, out));
                }

                // language is blank → union all mapped langs for this country
                java.util.List<String> langs = langsForCountry(country);
                if (langs.isEmpty()) langs = java.util.List.of("en"); // final safety
                String merged = mergeSourcesById(root, langs);
                return CompletableFuture.completedFuture(new NewsResponse(200, merged));
            } catch (Exception e) {
                String err = Json.newObject()
                        .put("error", "mock file missing or invalid: sources.json")
                        .toString();
                return CompletableFuture.completedFuture(new NewsResponse(500, err));
            }
        }

        // --- LIVE MODE + cache (unchanged) ---
        cleanCache(sourcesCache);
        String key = String.format("sources|lang=%s|cat=%s|ctry=%s",
                safe(language), safe(category), safe(country));

        long now = System.currentTimeMillis();
        CacheEntry cached = sourcesCache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return CompletableFuture.completedFuture(new NewsResponse(200, cached.body));
        }

        WSRequest req = ws.url(baseUrl + "/sources");
        if (language != null && !language.isBlank()) req = req.addQueryParameter("language", language);
        if (category != null && !category.isBlank()) req = req.addQueryParameter("category", category);
        if (country  != null && !country.isBlank())  req = req.addQueryParameter("country",  country);
        req = req.addHeader("X-Api-Key", apiKey);

        return req.get().thenApply(res -> {
            String resBody = res.getBody();
            if (res.getStatus() == 200) {
                sourcesCache.put(key, new CacheEntry(resBody, now + CACHE_TTL_MS));
            }
            return new NewsResponse(res.getStatus(), resBody);
        });
    }


    /**
     * Executes an "everything" search using the News API.
     * <p>
     * This endpoint searches all available news articles matching the provided
     * keyword or source filters. The request includes default query parameters:
     * </p>
     * <ul>
     *     <li>{@code language = "en"}</li>
     *     <li>{@code pageSize = 10}</li>
     *     <li>{@code sortBy = "relevancy"}</li>
     * </ul>
     *
     * @param q          search keyword (free text)
     * @param sourcesCsv comma-separated source IDs (optional)
     * @param country    optional country code (not applied by default)
     * @param category   optional category code
     * @param language   desired article language (default {@code "en"})
     * @param sortBy     sorting strategy: {@code relevancy} (default), {@code popularity}, {@code publishedAt}
     * @return asynchronous {@link NewsResponse} with search results
     * @author Nirvana Borham
     */
    public CompletionStage<NewsResponse> searchEverything(
            String q, String sourcesCsv, String country, String category, String language, String sortBy) {
        return searchEverything(q, sourcesCsv, country, category, language, sortBy, "10");
    }

    /**
     * Convenience overload that defaults {@code sortBy} to {@code "relevancy"}.
     *
     * @param q          search keyword (free text)
     * @param s          comma-separated source IDs (optional)
     * @param ctry       optional country code (not applied by default)
     * @param cat        optional category code
     * @param lang       desired article language (default {@code "en"})
     * @return asynchronous {@link NewsResponse} with search results
     * @author Nirvana Borham
     */
    public CompletionStage<NewsResponse> searchEverything(String q, String s, String ctry, String cat, String lang) {
        return searchEverything(q, s, ctry, cat, lang, "relevancy");
    }

    /**
     * 7-parameter overload that includes pageSize.
     *
     * @param q          search keyword
     * @param sourcesCsv comma-separated source IDs
     * @param country    optional country code
     * @param category   optional category code
     * @param language   desired article language
     * @param sortBy     sorting strategy
     * @param pageSize   number of results to return (1-100)
     * @return asynchronous {@link NewsResponse} with search results
     * @author Nirvana Borham
     */
    public CompletionStage<NewsResponse> searchEverything(String q, String sourcesCsv,
                                                          String country, String category, String language,
                                                          String sortBy, String pageSize) {
        // Similar logic to the 6-parameter version, but allow an explicit pageSize value
        if (mock) {
            String file = "everything_" + safe(q) + ".json";
            return mockResponseFromCombined(file, language, "en");
        }

        // sanitize sortBy
        String sb = (sortBy == null || sortBy.isBlank()) ? "relevancy" : sortBy;
        if (!sb.equals("relevancy") && !sb.equals("popularity") && !sb.equals("publishedAt")) {
            sb = "relevancy";
        }

        cleanCache(searchCache);
        String key = String.format("everything|q=%s|src=%s|ctry=%s|cat=%s|lang=%s|sort=%s|ps=%s",
                safe(q), safe(sourcesCsv), safe(country), safe(category), safe(language), safe(sb), safe(pageSize));

        long now = System.currentTimeMillis();
        CacheEntry cached = searchCache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return CompletableFuture.completedFuture(new NewsResponse(200, cached.body));
        }

        WSRequest req = ws.url(baseUrl + "/everything");
        if (q != null && !q.isBlank()) req = req.addQueryParameter("q", "\"" + q + "\""); // phrase search
        if (sourcesCsv != null && !sourcesCsv.isBlank()) req = req.addQueryParameter("sources", sourcesCsv);
        if (language != null && !language.isBlank())     req = req.addQueryParameter("language", language);

        String ps = (pageSize == null || pageSize.isBlank()) ? "10" : pageSize;
        req = req.addQueryParameter("pageSize", ps)    // allow explicit pageSize
                .addQueryParameter("sortBy", sb)        // ← use user preference
                .addHeader("X-Api-Key", apiKey)
                .setRequestTimeout(Duration.ofMillis(timeoutMs));

        return req.get().thenApply(res -> {
            String body = res.getBody();
            int status  = res.getStatus();
            if (status != 200) {
                return new NewsResponse(status, body);
            }

            try {
                JsonNode root = mapper.readTree(body);
                JsonNode arr  = root.path("articles");

                // Stream over the JSON array and build enriched Article objects
                java.util.List<Article> mapped = new java.util.ArrayList<>();
                if (arr.isArray()) {
                    java.util.Spliterator<JsonNode> spl = arr.spliterator();
                    mapped = java.util.stream.StreamSupport.stream(spl, false)
                            .map(n -> {
                                Article a = new Article();
                                a.title       = n.path("title").asText("");
                                a.description = n.path("description").asText("");
                                a.url         = n.path("url").asText("");
                                a.sourceName  = n.path("source").path("name").asText("");
                                a.publishedAt = n.path("publishedAt").asText("");

                                // Title link = the article url
                                a.titleLink   = a.url;

                                // Source link = https://<host> (strip leading www.)
                                try {
                                    String host = java.net.URI.create(a.url).getHost();
                                    if (host != null) {
                                        a.sourceLink = "https://" + host.replaceFirst("^www\\.", "");
                                    }
                                } catch (Exception ignore) { /* leave null/empty */ }

                                // Convert to EDT (Task 5 – see details below)
                                a.publishedEdt = toEdtDisplay(a.publishedAt);

                                return a;
                            })
                            .collect(java.util.stream.Collectors.toList());
                }

                // Build the new response JSON: { status:"ok", totalResults, articles:[...] }
                com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
                out.put("status", root.path("status").asText("ok"));
                if (root.has("totalResults")) out.put("totalResults", root.get("totalResults").asInt());
                out.set("articles", mapper.valueToTree(mapped));

                String outBody = mapper.writeValueAsString(out);
                // cache the enriched body
                searchCache.put(key, new CacheEntry(outBody, now + CACHE_TTL_MS));
                return new NewsResponse(200, outBody);

            } catch (Exception e) {
                // fall back to raw body if mapping fails
                return new NewsResponse(200, body);
            }
        });
    }

    /**
     * Formats an ISO-8601 UTC timestamp into {@code yyyy-MM-dd HH:mm:ss} in
     * the {@code America/New_York} timezone (EDT/EST depending on date).
     *
     * <p>Returns an empty string on parsing failure or blank input.</p>
     *
     * @param publishedAtIso ISO-8601 timestamp in UTC (e.g., {@code 2024-08-01T12:34:56Z})
     * @return formatted local time string or {@code ""} if invalid/blank input
     * @author Nirvana Borham
     */
    private static String toEdtDisplay(String publishedAtIso) {
        if (publishedAtIso == null || publishedAtIso.isBlank()) return "";
        try {
            // Parse UTC (e.g., 2024-08-01T12:34:56Z)
            java.time.ZonedDateTime utc = java.time.ZonedDateTime.parse(publishedAtIso);
            // Convert to Eastern Time
            java.time.ZonedDateTime et  = utc.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
            // Include zone short name (EDT/EST). Add " (XXX)" if you also want the numeric offset.
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format(et);
            // For offset too: "yyyy-MM-dd HH:mm:ss z (XXX)"
        } catch (Exception e) {
            return "";
        }
    }

}

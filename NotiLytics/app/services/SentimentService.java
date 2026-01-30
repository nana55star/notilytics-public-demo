package services;
import models.SentimentJson;
import models.ReadabilityJson;



import javax.inject.Inject;
import java.util.concurrent.CompletionStage;


/**
 * Service that augments News API search results with sentiment information.
 *
 * <p><b>What this does:</b>
 * <ul>
 *   <li>Delegates the actual article fetch to {@link NewsService}.</li>
 *   <li>When the downstream HTTP status is {@code 200}, it injects:
 *     <ul>
 *       <li>A per-article {@code "sentiment"} field (one of {@code ":-)"},
 *           {@code ":-("}, {@code ":-|"}), and</li>
 *       <li>A root-level {@code "overallSentiment"} field for the whole query.</li>
 *     </ul>
 *   </li>
 *   <li>For non-200 responses, it passes the response through unchanged.</li>
 * </ul>
 * </p>
 *
 * <p><b>Assignment alignment (Part D — Article Sentiment):</b>
 * The 70% rule and the 50-article cap are implemented in the downstream helpers:
 * <ul>
 *   <li>{@link models.SentimentAnalyzer} classifies a single text by counting
 *       happy/sad <i>words</i>, <i>ASCII emoticons</i>, and <i>emoji</i> and returns
 *       HAPPY/SAD/NEUTRAL using <b>strictly more than 70%</b> threshold.</li>
 *   <li>{@link models.SentimentJson} applies that classification to each article,
 *       caps the overall computation at the <b>first 50 articles</b>, and writes the
 *       emoticon strings into the JSON payload returned to the client.</li>
 * </ul>
 * This service only coordinates those steps and never changes the rule itself.
 * </p>
 */


public class SentimentService {

    /** Underlying client for the News API. */
    private final NewsService newsService;

    /**
     * Create the service with its dependency.
     *
     * @param newsService the News API client used to fetch raw articles
     */
    @Inject
    public SentimentService(NewsService newsService) {
        this.newsService = newsService;
    }

 /**
     * Perform a News API search and, on success (HTTP 200), inject sentiment fields.
     *
     * <p><b>Important details:</b>
     * <ul>
     *   <li>Only the response body is modified; the HTTP status is preserved.</li>
     *   <li>Sentiment injection calls
     *       {@link SentimentJson#addSentimentToNewsApiJson(String)} which:
     *       <ul>
     *         <li>Classifies each article from description → title → content (fallbacks),</li>
     *         <li>Appends per-article {@code "sentiment"} emoticons, and</li>
     *         <li>Computes {@code "overallSentiment"} from the <b>first 50</b> articles
     *             using the averaging rule over HAPPY=+1, SAD=−1, NEUTRAL=0.</li>
     *       </ul>
     *   </li>
     * </ul>
     * </p>
     *
     * @param q        query keywords (may be blank if sources provided)
     * @param sources  comma-separated source IDs (may be blank if q provided)
     * @param country  optional country filter (e.g., {@code "us"}, {@code "ca"})
     * @param category optional category (e.g., {@code "business"})
     * @param language language code (e.g., {@code "en"})
     * @param sortBy   sorting strategy: {@code relevancy} (default), {@code popularity}, {@code publishedAt}
     * @param pageSize number of articles to return (string form, bounded to 1–100)
     * @return a stage that completes with:
     * <ul>
     *   <li>{@code NewsResponse(200, enrichedJson)} on success; or</li>
     *   <li>the original {@code NewsResponse} unchanged for non-200 statuses.</li>
     * </ul>
     */
    public CompletionStage<NewsResponse> searchEverythingWithSentiment(
            String q,
            String sources,
            String country,
            String category,
            String language,
            String sortBy,
            String pageSize
    ) {
        String effectivePageSize = (pageSize == null || pageSize.isBlank()) ? "20" : pageSize;

        // 1) Fetch raw results (no sentiment or readability yet).
        return newsService.searchEverything(q, sources, country, category, language, sortBy, effectivePageSize)
                // 2) Enrich the body ONLY when the downstream status is 200.
                .thenApply(r -> {
                    if (r.status == 200) {
                        // Add per-article "sentiment" and root "overallSentiment".
                        String withSentiment = SentimentJson.addSentimentToNewsApiJson(r.body);
                        // Add per-article readability scores (readingEase, gradeLevel).
                        String enriched = ReadabilityJson.addReadabilityToNewsApiJson(withSentiment);
                        return new NewsResponse(r.status, enriched);
                    }
                    // Non-200 → leave as-is (surface errors/rate-limits to the client).
                    return r;
                });
    }
}

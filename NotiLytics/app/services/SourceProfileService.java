package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.ArticleSummary;
import models.ErrorInfo;
import models.ServiceResult;
import models.SourceDetails;
import models.SourceProfile;
import play.libs.Json;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Service responsible for populating the source page
 * @author Alex Sutherland
 */
@Singleton
public class SourceProfileService {

    private final NewsService newsService;

    @Inject
    public SourceProfileService(NewsService newsService) {
        this.newsService = newsService;
    }

    /**
     * Builds a profile for a specific news source, retrieving metadata and its latest articles.
     *
     * @param sourceId the source identifier requested
     * @param language preferred article language
     * @param category optional category filter
     * @param country  optional country filter
     * @param sortBy   requested sort order (e.g. publishedAt)
     * @param pageSize requested number of articles to surface
     */
    public CompletionStage<ServiceResult<SourceProfile>> fetchSourceProfile(String sourceId,
                                                                            String language,
                                                                            String category,
                                                                            String country,
                                                                            String sortBy,
                                                                            String pageSize) {
        return newsService.listSources(language, category, country)
                .thenCompose(sourceResponse -> {
                    if (sourceResponse.status >= 400) {
                        ErrorInfo error = new ErrorInfo(
                                "Failed to load the list of sources.",
                                safeParse(sourceResponse.body)
                        );
                        return CompletableFuture.completedFuture(ServiceResult.failure(sourceResponse.status, error));
                    }

                    JsonNode json = safeParse(sourceResponse.body);
                    ArrayNode sourcesArray = extractArray(json, "sources");
                    SourceDetails details = findSourceDetails(sourcesArray, sourceId);
                    if (details == null) {
                        ObjectNode detailsNode = Json.newObject().put("sourceId", sourceId);
                        ErrorInfo error = new ErrorInfo("Source not found", detailsNode);
                        return CompletableFuture.completedFuture(ServiceResult.failure(404, error));
                    }

                    int limit = safePageSize(pageSize, 10);
                    String resolvedSort = (sortBy == null || sortBy.isBlank()) ? "publishedAt" : sortBy;
                    String effectiveLanguage = normalizeLanguage(language);

                    // Use the actual source ID from the details if available, otherwise use the input
                    String actualSourceId = (details.id != null && !details.id.isBlank()) ? details.id : sourceId;

                    return newsService.searchEverything(null, actualSourceId, country, category, effectiveLanguage, resolvedSort, Integer.toString(limit))
                            .thenApply(articleResponse -> {
                                if (articleResponse.status >= 400) {
                                    ErrorInfo error = new ErrorInfo(
                                            "Failed to load recent articles for this source. Try again later.",
                                            safeParse(articleResponse.body)
                                    );
                                    return ServiceResult.failure(articleResponse.status, error);
                                }

                                JsonNode articlesJson = safeParse(articleResponse.body);
                                ArrayNode articleNodes = extractArray(articlesJson, "articles");
                                List<ArticleSummary> articles = new ArrayList<>();
                                for (JsonNode node : articleNodes) {
                                    ArticleSummary summary = ArticleSummary.fromJson(node);
                                    if (summary != null) {
                                        articles.add(summary);
                                    }
                                }

                                List<ArticleSummary> limited = applyArticleOrdering(articles, resolvedSort, limit);
                                int totalResults = articlesJson.path("totalResults").asInt(articles.size());
                                SourceProfile profile = new SourceProfile(details, limited, totalResults);
                                return ServiceResult.success(articleResponse.status, profile);
                            });
                });
    }

    /**
     Order by date function for recent orders with 10 limit
     */
    private List<ArticleSummary> applyArticleOrdering(List<ArticleSummary> sourceArticles,
                                                      String sortBy,
                                                      int limit) {
        List<ArticleSummary> working = new ArrayList<>(sourceArticles);

        if ("publishedAt".equalsIgnoreCase(sortBy)) {
            working.sort((a, b) -> Long.compare(parsePublishedAt(b.publishedAt), parsePublishedAt(a.publishedAt)));
        }

        if (working.size() > limit) {
            return new ArrayList<>(working.subList(0, limit));
        }
        return working;
    }

    private int safePageSize(String pageSize, int fallback) {
        if (pageSize == null || pageSize.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(pageSize);
            if (parsed < 1) return 1;
            if (parsed > 100) return 100;
            return parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long parsePublishedAt(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return OffsetDateTime.parse(publishedAt).toInstant().toEpochMilli();
        } catch (DateTimeParseException ex) {
            return Long.MIN_VALUE;
        }
    }

    private String normalizeLanguage(String language) {
        if (language == null) {
            return null;
        }
        return "all".equalsIgnoreCase(language) ? null : language;
    }

    /**
     Function to find further source details using the id or name in the response array
     */
    private SourceDetails findSourceDetails(ArrayNode sourcesArray, String sourceIdOrName) {
        if (sourceIdOrName == null || sourceIdOrName.isBlank()) {
            return null;
        }

        String target = sourceIdOrName.toLowerCase(Locale.ROOT);

        // First try to find by ID (exact match)
        for (JsonNode node : sourcesArray) {
            String id = node.path("id").asText("");
            if (!id.isBlank() && id.toLowerCase(Locale.ROOT).equals(target)) {
                return SourceDetails.fromJson(node);
            }
        }

        // If not found by ID, try to find by name (case-insensitive)
        for (JsonNode node : sourcesArray) {
            String name = node.path("name").asText("");
            if (!name.isBlank() && name.toLowerCase(Locale.ROOT).equals(target)) {
                return SourceDetails.fromJson(node);
            }
        }

        return null;
    }

    private ArrayNode extractArray(JsonNode parent, String fieldName) {
        if (parent != null && parent.has(fieldName) && parent.get(fieldName).isArray()) {
            return (ArrayNode) parent.get(fieldName);
        }
        return Json.newArray();
    }

    /**
     A parsing wrapper for json response in order to properly handle any sort of parsing issues
     */
    private JsonNode safeParse(String body) {
        if (body == null || body.isBlank()) {
            return Json.newObject();
        }
        try {
            return Json.parse(body);
        } catch (RuntimeException ex) {
            ObjectNode fallback = Json.newObject();
            fallback.put("raw", body);
            fallback.put("parseError", ex.getMessage());
            return fallback;
        }
    }
}

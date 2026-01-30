package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import services.ReadabilityService;

/**
 * Utilities to enrich NewsAPI JSON payloads with readability fields.
 *
 * <p>Adds per-article readability scores (Reading Ease, Grade Level) to each article.
 * Limits analysis to the first 50 articles.</p>
 *
 * @author Mustafa Kaya
 */
public final class ReadabilityJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ReadabilityService readabilityService = new ReadabilityService();

    private ReadabilityJson() {}

    /**
     * Adds readability annotations to a NewsAPI-style JSON payload.
     * <ul>
     *   <li>For each article, computes readability metrics from description (fallback: title).</li>
     *   <li>Adds "readingEase" and "gradeLevel" fields to each article.</li>
     * </ul>
     *
     * @param json JSON string
     * @return enriched JSON string
     */
    public static String addReadabilityToNewsApiJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!(root instanceof ObjectNode)) return json;

            JsonNode arts = root.get("articles");
            if (arts == null || !arts.isArray()) return json;

            ArrayNode arr = (ArrayNode) arts;
            int limit = Math.min(50, arr.size());

            // Calculate readability for first 50 articles
            for (int i = 0; i < limit; i++) {
                JsonNode a = arr.get(i);
                if (!(a instanceof ObjectNode)) continue;

                String desc = getText(a, "description");
                String title = getText(a, "title");
                String text = firstNonBlank(desc, title);

                ReadabilityMetrics metrics = readabilityService.computeForText(text);

                // Add readability fields to article
                ObjectNode article = (ObjectNode) a;
                article.put("readingEase", round(metrics.getReadingEase(), 1));
                article.put("gradeLevel", round(metrics.getGradeLevel(), 1));
            }

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return json; // fail-safe: never break the endpoint
        }
    }

    // --- helpers ---
    private static String getText(JsonNode n, String field) {
        return (n != null && n.hasNonNull(field)) ? n.get(field).asText() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}

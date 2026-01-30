package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Utilities to enrich NewsAPI JSON payloads with sentiment fields.
 *
 * <p>Adds per-article "sentiment" (":-)", ":-(", ":-|") and root-level "overallSentiment".
 * Limits analysis to the first 50 articles.</p>
 *
 * @author zahra ebrahimizadehghahrood
 */
public final class SentimentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SentimentJson() {}


    /**
     * Adds sentiment annotations to a NewsAPI-style JSON payload.
     * <ul>
     *   <li>For each article, computes a per-article sentiment from description (fallback: title, content).</li>
     *   <li>Computes root-level {@code overallSentiment} from the first 50 articles.</li>
     * </ul>
     *
     * @param newsApiJson JSON string
     * @return enriched JSON string
     */    
    public static String addSentimentToNewsApiJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!(root instanceof ObjectNode)) return json;

            JsonNode arts = root.get("articles");
            if (arts == null || !arts.isArray()) return json;

            ArrayNode arr = (ArrayNode) arts;
            int limit = Math.min(50, arr.size());

             // sentiments for first 50 articles
            List<SentimentAnalyzer.Sentiment> perArticle = IntStream.range(0, limit).mapToObj(i -> {
                JsonNode a = arr.get(i);

                String desc    = getText(a, "description");
                String title   = getText(a, "title");
                String content = getText(a, "content");
                String text    = firstNonBlank(desc, title, content); // fallback chain

                SentimentAnalyzer.Sentiment s = SentimentAnalyzer.analyzeText(text);
                if (a instanceof ObjectNode) {
                    ((ObjectNode) a).put("sentiment", SentimentAnalyzer.toEmoticon(s));
                }

                return s;
            }).collect(Collectors.toList());

            // Overall (70% rule over the 50-article window)
            SentimentAnalyzer.Sentiment overall = SentimentAnalyzer.overallFromArticles(perArticle);
            ((ObjectNode) root).put("overallSentiment", SentimentAnalyzer.toEmoticon(overall));



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
}
 


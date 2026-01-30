package models;

import java.util.Collections;
import java.util.List;
import play.libs.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Aggregated view model containing a source and its most recent articles.
 */
public class SourceProfile {
    private final SourceDetails source;
    private final List<ArticleSummary> articles;
    private final int totalResults;

    public SourceProfile(SourceDetails source, List<ArticleSummary> articles, int totalResults) {
        this.source = source;
        this.articles = articles == null ? Collections.emptyList() : List.copyOf(articles);
        this.totalResults = totalResults;
    }

    public SourceDetails getSource() {
        return source;
    }

    public List<ArticleSummary> getArticles() {
        return articles;
    }

    public int getTotalResults() {
        return totalResults;
    }

    public ObjectNode toJson() {
        ObjectNode node = Json.newObject();
        node.put("status", "ok");
        node.set("source", source != null ? source.toJson() : Json.newObject());
        ArrayNode articleArray = Json.newArray();
        for (ArticleSummary summary : articles) {
            articleArray.add(summary.toJson());
        }
        node.set("articles", articleArray);
        node.put("totalResults", totalResults);
        return node;
    }
}

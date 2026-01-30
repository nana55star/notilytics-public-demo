package models;

import play.libs.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lightweight representation of an article from the News API.
 */
public class ArticleSummary {
    public final String title;
    public final String description;
    public final String url;
    public final String publishedAt;
    public final String sourceId;
    public final String sourceName;

    public ArticleSummary(String title,
                          String description,
                          String url,
                          String publishedAt,
                          String sourceId,
                          String sourceName) {
        this.title = title;
        this.description = description;
        this.url = url;
        this.publishedAt = publishedAt;
        this.sourceId = sourceId;
        this.sourceName = sourceName;
    }

    public static ArticleSummary fromJson(JsonNode node) {
        if (node == null) return null;
        JsonNode sourceNode = node.path("source");
        return new ArticleSummary(
                node.path("title").asText(null),
                node.path("description").asText(null),
                node.path("url").asText(null),
                node.path("publishedAt").asText(null),
                sourceNode.path("id").asText(null),
                sourceNode.path("name").asText(null)
        );
    }

    public ObjectNode toJson() {
        ObjectNode node = Json.newObject();
        if (title != null) node.put("title", title);
        if (description != null) node.put("description", description);
        if (url != null) node.put("url", url);
        if (publishedAt != null) node.put("publishedAt", publishedAt);

        ObjectNode sourceNode = Json.newObject();
        if (sourceId != null) sourceNode.put("id", sourceId);
        if (sourceName != null) sourceNode.put("name", sourceName);
        node.set("source", sourceNode);
        return node;
    }
}

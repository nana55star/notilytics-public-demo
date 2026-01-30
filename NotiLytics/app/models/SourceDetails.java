package models;

import play.libs.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strongly-typed representation of a news source returned by the News API.
 */
public class SourceDetails {
    public final String id;
    public final String name;
    public final String description;
    public final String url;
    public final String category;
    public final String language;
    public final String country;

    public SourceDetails(String id,
                         String name,
                         String description,
                         String url,
                         String category,
                         String language,
                         String country) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.url = url;
        this.category = category;
        this.language = language;
        this.country = country;
    }

    public static SourceDetails fromJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        return new SourceDetails(
                node.path("id").asText(null),
                node.path("name").asText(null),
                node.path("description").asText(null),
                node.path("url").asText(null),
                node.path("category").asText(null),
                node.path("language").asText(null),
                node.path("country").asText(null)
        );
    }

    public ObjectNode toJson() {
        ObjectNode node = Json.newObject();
        if (id != null) node.put("id", id);
        if (name != null) node.put("name", name);
        if (description != null) node.put("description", description);
        if (url != null) node.put("url", url);
        if (category != null) node.put("category", category);
        if (language != null) node.put("language", language);
        if (country != null) node.put("country", country);
        return node;
    }
}

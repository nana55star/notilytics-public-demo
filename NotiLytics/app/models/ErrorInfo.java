package models;

import com.fasterxml.jackson.databind.JsonNode;
import play.libs.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Represents an application-level error with an optional details payload.
 */
public class ErrorInfo {
    private final String message;
    private final JsonNode details;

    public ErrorInfo(String message, JsonNode details) {
        this.message = message;
        this.details = details;
    }

    public String getMessage() {
        return message;
    }

    public JsonNode getDetails() {
        return details;
    }

    public ObjectNode toJson() {
        ObjectNode node = Json.newObject();
        node.put("status", "error");
        node.put("message", message);
        if (details != null) {
            node.set("details", details);
        }
        return node;
    }
}

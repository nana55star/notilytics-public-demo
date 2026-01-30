package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import play.libs.Json;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ErrorInfo}.
 * @author Alex Sutherland
 */
public class ErrorInfoTest {
    /*
    Test that constructor sets message when built
    **/
    @Test
    void constructor_setsMessageAndDetails() {
        ObjectNode details = Json.newObject();
        details.put("code", "ERR_001");
        details.put("field", "username");

        ErrorInfo error = new ErrorInfo("Validation failed", details);

        assertEquals("Validation failed", error.getMessage());
        assertNotNull(error.getDetails());
        assertEquals("ERR_001", error.getDetails().get("code").asText());
        assertEquals("username", error.getDetails().get("field").asText());
    }

    /*
    Test that constructor accepts error messages without details
    **/
    @Test
    void constructor_withNullDetails_allowsNull() {
        ErrorInfo error = new ErrorInfo("Simple error", null);

        assertEquals("Simple error", error.getMessage());
        assertNull(error.getDetails());
    }

    /*
    Test that constructor handles complete response (mocking real data)
    **/
    @Test
    void toJson_withDetails_createsCompleteJson() {
        ObjectNode details = Json.newObject();
        details.put("statusCode", 404);
        details.put("resource", "user");

        ErrorInfo error = new ErrorInfo("Resource not found", details);
        ObjectNode json = error.toJson();

        assertEquals("error", json.get("status").asText());
        assertEquals("Resource not found", json.get("message").asText());
        assertTrue(json.has("details"));
        assertEquals(404, json.get("details").get("statusCode").asInt());
        assertEquals("user", json.get("details").get("resource").asText());
    }

    /*
    Test that no details error is handled correctly
    **/
    @Test
    void toJson_withoutDetails_omitsDetailsField() {
        ErrorInfo error = new ErrorInfo("No details error", null);
        ObjectNode json = error.toJson();

        assertEquals("error", json.get("status").asText());
        assertEquals("No details error", json.get("message").asText());
        assertFalse(json.has("details"));
    }

    /*
    Test that empty object is handled correctly
    **/
    @Test
    void toJson_withEmptyDetails_includesEmptyObject() {
        ObjectNode emptyDetails = Json.newObject();
        ErrorInfo error = new ErrorInfo("Error with empty details", emptyDetails);
        ObjectNode json = error.toJson();

        assertEquals("error", json.get("status").asText());
        assertEquals("Error with empty details", json.get("message").asText());
        assertTrue(json.has("details"));
        assertEquals(0, json.get("details").size());
    }

    /*
    Test that custom message is accepted and returned
    **/
    @Test
    void getMessage_returnsCorrectMessage() {
        ErrorInfo error = new ErrorInfo("Test message", null);
        assertEquals("Test message", error.getMessage());
    }

    /*
    Test that message details are returned verbatim, without any post processing
    **/
    @Test
    void getDetails_returnsCorrectDetails() {
        ObjectNode details = Json.newObject();
        details.put("key", "value");

        ErrorInfo error = new ErrorInfo("Message", details);
        JsonNode retrievedDetails = error.getDetails();

        assertNotNull(retrievedDetails);
        assertEquals("value", retrievedDetails.get("key").asText());
    }
}

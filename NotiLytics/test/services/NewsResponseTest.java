package services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NewsResponse}.
 * @author Alex Sutherland
 */
public class NewsResponseTest {
    /**
    Test that NewsResponse accepts status and body in constructor
    **/
    @Test
    void constructor_setsStatusAndBody() {
        NewsResponse response = new NewsResponse(200, "{\"status\":\"ok\"}");

        assertEquals(200, response.status);
        assertEquals("{\"status\":\"ok\"}", response.body);
    }

    /**
    Test that constructor stores error status as is
    **/
    @Test
    void constructor_withErrorStatus_storesCorrectly() {
        NewsResponse response = new NewsResponse(404, "{\"error\":\"not found\"}");

        assertEquals(404, response.status);
        assertEquals("{\"error\":\"not found\"}", response.body);
    }

    /**
    Test that constructor accepts empty string (empty body)
    **/
    @Test
    void constructor_withEmptyBody_allowsEmptyString() {
        NewsResponse response = new NewsResponse(204, "");

        assertEquals(204, response.status);
        assertEquals("", response.body);
    }

    /**
    Test that constructor allows null for body response
    **/
    @Test
    void constructor_withNullBody_allowsNull() {
        NewsResponse response = new NewsResponse(500, null);

        assertEquals(500, response.status);
        assertNull(response.body);
    }

    /**
    Test that varying status codes are handled and stored correctly
    **/
    @Test
    void constructor_withVariousStatusCodes_storesCorrectly() {
        NewsResponse response1 = new NewsResponse(200, "OK");
        NewsResponse response2 = new NewsResponse(201, "Created");
        NewsResponse response3 = new NewsResponse(400, "Bad Request");
        NewsResponse response4 = new NewsResponse(401, "Unauthorized");
        NewsResponse response5 = new NewsResponse(500, "Internal Server Error");
        NewsResponse response6 = new NewsResponse(502, "Bad Gateway");

        assertEquals(200, response1.status);
        assertEquals(201, response2.status);
        assertEquals(400, response3.status);
        assertEquals(401, response4.status);
        assertEquals(500, response5.status);
        assertEquals(502, response6.status);
    }

    /**
    Test that constructor stores complex/nested response body correctly
    **/
    @Test
    void constructor_withComplexJsonBody_storesCorrectly() {
        String complexJson = "{\"status\":\"ok\",\"articles\":[{\"title\":\"Test\",\"description\":\"Desc\"}],\"totalResults\":1}";
        NewsResponse response = new NewsResponse(200, complexJson);

        assertEquals(200, response.status);
        assertEquals(complexJson, response.body);
    }
}

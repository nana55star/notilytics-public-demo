package models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import play.libs.Json;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceDetails}.
 * @author  Alex Sutherland
 */
public class SourceDetailsTest {

    /*
    Test that the  Source Details constructor sets all fields
    **/
    @Test
    void constructor_setsAllFields() {
        SourceDetails details = new SourceDetails(
                "bbc-news",
                "BBC News",
                "British news organization",
                "https://bbc.com",
                "general",
                "en",
                "gb"
        );

        assertEquals("bbc-news", details.id);
        assertEquals("BBC News", details.name);
        assertEquals("British news organization", details.description);
        assertEquals("https://bbc.com", details.url);
        assertEquals("general", details.category);
        assertEquals("en", details.language);
        assertEquals("gb", details.country);
    }

    /*
    Test that the constructor builds from a mock response
    **/
    @Test
    void fromJson_withCompleteData_createsSourceDetails() {
        ObjectNode json = Json.newObject();
        json.put("id", "cnn");
        json.put("name", "CNN");
        json.put("description", "Cable News Network");
        json.put("url", "https://cnn.com");
        json.put("category", "general");
        json.put("language", "en");
        json.put("country", "us");

        SourceDetails details = SourceDetails.fromJson(json);

        assertNotNull(details);
        assertEquals("cnn", details.id);
        assertEquals("CNN", details.name);
        assertEquals("Cable News Network", details.description);
        assertEquals("https://cnn.com", details.url);
        assertEquals("general", details.category);
        assertEquals("en", details.language);
        assertEquals("us", details.country);
    }

    /*
     Test that the constructor sets with null fields (the remaining data is still valuable)
     **/
    @Test
    void fromJson_withMissingFields_usesNullDefaults() {
        ObjectNode json = Json.newObject();
        json.put("id", "minimal-source");

        SourceDetails details = SourceDetails.fromJson(json);

        assertNotNull(details);
        assertEquals("minimal-source", details.id);
        assertNull(details.name);
        assertNull(details.description);
        assertNull(details.url);
        assertNull(details.category);
        assertNull(details.language);
        assertNull(details.country);
    }

    /*
    Test that null summary returns null
    **/
    @Test
    void fromJson_withNullNode_returnsNull() {
        SourceDetails details = SourceDetails.fromJson(null);
        assertNull(details);
    }

    /*
    Test that the json format is built correctly
    **/
    @Test
    void toJson_withAllFields_createsCompleteJson() {
        SourceDetails details = new SourceDetails(
                "test-id",
                "Test Source",
                "Test Description",
                "https://test.com",
                "technology",
                "en",
                "us"
        );

        ObjectNode json = details.toJson();

        assertEquals("test-id", json.get("id").asText());
        assertEquals("Test Source", json.get("name").asText());
        assertEquals("Test Description", json.get("description").asText());
        assertEquals("https://test.com", json.get("url").asText());
        assertEquals("technology", json.get("category").asText());
        assertEquals("en", json.get("language").asText());
        assertEquals("us", json.get("country").asText());
    }

    /*
    Test that the constructor with some null fields omits null fields
    **/
    @Test
    void toJson_withNullFields_omitsNullValues() {
        SourceDetails details = new SourceDetails(
                "id-only",
                null,
                null,
                null,
                null,
                null,
                null
        );

        ObjectNode json = details.toJson();

        assertTrue(json.has("id"));
        assertFalse(json.has("name"));
        assertFalse(json.has("description"));
        assertFalse(json.has("url"));
        assertFalse(json.has("category"));
        assertFalse(json.has("language"));
        assertFalse(json.has("country"));
    }

    /*
    Test that json original is parsed and returned json without data loss when data is provided
    **/
    @Test
    void roundTrip_fromJsonToJson_preservesData() {
        ObjectNode original = Json.newObject();
        original.put("id", "round-trip");
        original.put("name", "Round Trip News");
        original.put("description", "Testing round trip");
        original.put("url", "https://roundtrip.com");
        original.put("category", "business");
        original.put("language", "fr");
        original.put("country", "fr");

        SourceDetails details = SourceDetails.fromJson(original);
        ObjectNode result = details.toJson();

        assertEquals(original.get("id").asText(), result.get("id").asText());
        assertEquals(original.get("name").asText(), result.get("name").asText());
        assertEquals(original.get("description").asText(), result.get("description").asText());
        assertEquals(original.get("url").asText(), result.get("url").asText());
        assertEquals(original.get("category").asText(), result.get("category").asText());
        assertEquals(original.get("language").asText(), result.get("language").asText());
        assertEquals(original.get("country").asText(), result.get("country").asText());
    }

    /**
     * Test fromJson with empty JSON object.
     */
    @Test
    void fromJson_withEmptyObject_createsAllNullFields() {
        ObjectNode json = Json.newObject();
        SourceDetails details = SourceDetails.fromJson(json);

        assertNotNull(details);
        assertNull(details.id);
        assertNull(details.name);
        assertNull(details.description);
        assertNull(details.url);
        assertNull(details.category);
        assertNull(details.language);
        assertNull(details.country);
    }

    /**
     * Test toJson with completely empty SourceDetails.
     */
    @Test
    void toJson_withAllNullFields_createsEmptyObject() {
        SourceDetails details = new SourceDetails(null, null, null, null, null, null, null);
        ObjectNode json = details.toJson();

        assertNotNull(json);
        assertFalse(json.has("id"));
        assertFalse(json.has("name"));
        assertFalse(json.has("description"));
        assertFalse(json.has("url"));
        assertFalse(json.has("category"));
        assertFalse(json.has("language"));
        assertFalse(json.has("country"));
    }

    /**
     * Test fromJson with partial data - only name and url.
     */
    @Test
    void fromJson_withPartialData_fillsAvailableFields() {
        ObjectNode json = Json.newObject();
        json.put("name", "Partial Source");
        json.put("url", "https://partial.com");

        SourceDetails details = SourceDetails.fromJson(json);

        assertNotNull(details);
        assertNull(details.id);
        assertEquals("Partial Source", details.name);
        assertNull(details.description);
        assertEquals("https://partial.com", details.url);
        assertNull(details.category);
        assertNull(details.language);
        assertNull(details.country);
    }

    /**
     * Test toJson with partial data - only name and url.
     */
    @Test
    void toJson_withPartialFields_includesOnlyNonNull() {
        SourceDetails details = new SourceDetails(
                null,
                "Source Name",
                null,
                "https://example.com",
                null,
                null,
                null
        );

        ObjectNode json = details.toJson();

        assertFalse(json.has("id"));
        assertTrue(json.has("name"));
        assertFalse(json.has("description"));
        assertTrue(json.has("url"));
        assertFalse(json.has("category"));
        assertFalse(json.has("language"));
        assertFalse(json.has("country"));
        assertEquals("Source Name", json.get("name").asText());
        assertEquals("https://example.com", json.get("url").asText());
    }

    /**
     * Test with special characters in fields.
     */
    @Test
    void constructor_withSpecialCharacters_storesCorrectly() {
        SourceDetails details = new SourceDetails(
                "special-id",
                "Source & Co. \"News\"",
                "Description with 'quotes' and <tags>",
                "https://example.com/path?query=value&other=123",
                "business",
                "en",
                "us"
        );

        assertEquals("Source & Co. \"News\"", details.name);
        assertEquals("Description with 'quotes' and <tags>", details.description);
        assertEquals("https://example.com/path?query=value&other=123", details.url);
    }

    /**
     * Test toJson handles special characters properly.
     */
    @Test
    void toJson_withSpecialCharacters_encodesCorrectly() {
        SourceDetails details = new SourceDetails(
                "special-id",
                "News & \"Updates\"",
                "Description <test>",
                "https://test.com",
                "general",
                "en",
                "gb"
        );

        ObjectNode json = details.toJson();
        assertEquals("News & \"Updates\"", json.get("name").asText());
        assertEquals("Description <test>", json.get("description").asText());
    }

    /**
     * Test fromJson with different categories.
     */
    @Test
    void fromJson_withDifferentCategories_storesCorrectly() {
        String[] categories = {"general", "business", "technology", "entertainment", "sports", "science", "health"};

        for (String category : categories) {
            ObjectNode json = Json.newObject();
            json.put("id", "test");
            json.put("category", category);

            SourceDetails details = SourceDetails.fromJson(json);
            assertEquals(category, details.category);
        }
    }

    /**
     * Test fromJson with different languages.
     */
    @Test
    void fromJson_withDifferentLanguages_storesCorrectly() {
        String[] languages = {"en", "fr", "de", "es", "ar", "zh"};

        for (String lang : languages) {
            ObjectNode json = Json.newObject();
            json.put("id", "test");
            json.put("language", lang);

            SourceDetails details = SourceDetails.fromJson(json);
            assertEquals(lang, details.language);
        }
    }

    /**
     * Test fromJson with different countries.
     */
    @Test
    void fromJson_withDifferentCountries_storesCorrectly() {
        String[] countries = {"us", "gb", "ca", "au", "de", "fr"};

        for (String country : countries) {
            ObjectNode json = Json.newObject();
            json.put("id", "test");
            json.put("country", country);

            SourceDetails details = SourceDetails.fromJson(json);
            assertEquals(country, details.country);
        }
    }
}

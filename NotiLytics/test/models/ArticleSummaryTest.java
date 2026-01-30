package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import play.libs.Json;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ArticleSummary}.
 * @author Alex Sutherland
 */
public class ArticleSummaryTest {

    /**
    Test that the constructor sets all fields
     **/
    @Test
    void constructor_setsAllFields() {
        ArticleSummary summary = new ArticleSummary(
                "Test Title",
                "Test Description",
                "https://example.com",
                "2024-01-01T12:00:00Z",
                "test-source",
                "Test Source"
        );

        assertEquals("Test Title", summary.title);
        assertEquals("Test Description", summary.description);
        assertEquals("https://example.com", summary.url);
        assertEquals("2024-01-01T12:00:00Z", summary.publishedAt);
        assertEquals("test-source", summary.sourceId);
        assertEquals("Test Source", summary.sourceName);
    }

    /**
    Test that the constructor builds from a mock response
    **/
    @Test
    void fromJson_withCompleteData_createsArticleSummary() {
        ObjectNode json = Json.newObject();
        json.put("title", "Breaking News");
        json.put("description", "Something happened");
        json.put("url", "https://news.com/article");
        json.put("publishedAt", "2024-03-15T10:30:00Z");

        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "bbc-news");
        sourceNode.put("name", "BBC News");
        json.set("source", sourceNode);

        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertNotNull(summary);
        assertEquals("Breaking News", summary.title);
        assertEquals("Something happened", summary.description);
        assertEquals("https://news.com/article", summary.url);
        assertEquals("2024-03-15T10:30:00Z", summary.publishedAt);
        assertEquals("bbc-news", summary.sourceId);
        assertEquals("BBC News", summary.sourceName);
    }

    /**
    Test that the constructor sets with null fields (the remaining data is still valuable)
    **/
    @Test
    void fromJson_withMissingFields_usesNullDefaults() {
        ObjectNode json = Json.newObject();
        json.put("title", "Title Only");

        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertNotNull(summary);
        assertEquals("Title Only", summary.title);
        assertNull(summary.description);
        assertNull(summary.url);
        assertNull(summary.publishedAt);
        assertNull(summary.sourceId);
        assertNull(summary.sourceName);
    }

    /**
    Test that null summary returns null
    **/
    @Test
    void fromJson_withNullNode_returnsNull() {
        ArticleSummary summary = ArticleSummary.fromJson(null);
        assertNull(summary);
    }

    /**
    Test that the json format is built correctly
    **/
    @Test
    void toJson_withAllFields_createsCompleteJson() {
        ArticleSummary summary = new ArticleSummary(
                "News Title",
                "News Desc",
                "https://test.com",
                "2024-02-20T15:00:00Z",
                "source-id",
                "Source Name"
        );

        ObjectNode json = summary.toJson();

        assertEquals("News Title", json.get("title").asText());
        assertEquals("News Desc", json.get("description").asText());
        assertEquals("https://test.com", json.get("url").asText());
        assertEquals("2024-02-20T15:00:00Z", json.get("publishedAt").asText());
        assertEquals("source-id", json.get("source").get("id").asText());
        assertEquals("Source Name", json.get("source").get("name").asText());
    }

    /**
    Test that the constructor with some null fields omits null fields
    **/
    @Test
    void toJson_withNullFields_omitsNullValues() {
        ArticleSummary summary = new ArticleSummary(
                "Title",
                null,
                null,
                null,
                null,
                "Source"
        );

        ObjectNode json = summary.toJson();

        assertTrue(json.has("title"));
        assertFalse(json.has("description"));
        assertFalse(json.has("url"));
        assertFalse(json.has("publishedAt"));
        assertTrue(json.has("source"));
        assertFalse(json.get("source").has("id"));
        assertTrue(json.get("source").has("name"));
    }

    /**
    Test that json original is parsed and returned json without data loss when data is provided
    */
    @Test
    void roundTrip_fromJsonToJson_preservesData() {
        ObjectNode original = Json.newObject();
        original.put("title", "Round Trip Test");
        original.put("description", "Testing serialization");
        original.put("url", "https://round.trip");
        original.put("publishedAt", "2024-04-01T00:00:00Z");

        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test-id");
        sourceNode.put("name", "Test Name");
        original.set("source", sourceNode);

        ArticleSummary summary = ArticleSummary.fromJson(original);
        ObjectNode result = summary.toJson();

        assertEquals(original.get("title").asText(), result.get("title").asText());
        assertEquals(original.get("description").asText(), result.get("description").asText());
        assertEquals(original.get("url").asText(), result.get("url").asText());
        assertEquals(original.get("publishedAt").asText(), result.get("publishedAt").asText());
        assertEquals(original.get("source").get("id").asText(), result.get("source").get("id").asText());
        assertEquals(original.get("source").get("name").asText(), result.get("source").get("name").asText());
    }

    /**
     * Test fromJson when source node is missing entirely.
     */
    @Test
    void fromJson_withMissingSourceNode_usesNullDefaults() {
        ObjectNode json = Json.newObject();
        json.put("title", "Article Without Source");
        json.put("description", "Testing missing source");
        json.put("url", "https://example.com");

        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertNotNull(summary);
        assertEquals("Article Without Source", summary.title);
        assertNull(summary.sourceId);
        assertNull(summary.sourceName);
    }

    /**
     * Test fromJson when source node exists but is empty.
     */
    @Test
    void fromJson_withEmptySourceNode_usesNullDefaults() {
        ObjectNode json = Json.newObject();
        json.put("title", "Test Article");
        json.set("source", Json.newObject());

        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertNotNull(summary);
        assertNull(summary.sourceId);
        assertNull(summary.sourceName);
    }

    /**
     * Test toJson always includes source node even if both fields are null.
     */
    @Test
    void toJson_withNullSourceFields_includesEmptySourceObject() {
        ArticleSummary summary = new ArticleSummary(
                "Title",
                "Desc",
                "https://test.com",
                "2024-01-01T00:00:00Z",
                null,
                null
        );

        ObjectNode json = summary.toJson();

        assertTrue(json.has("source"));
        JsonNode sourceNode = json.get("source");
        assertFalse(sourceNode.has("id"));
        assertFalse(sourceNode.has("name"));
    }

    /**
     * Test fromJson with empty JSON object.
     */
    @Test
    void fromJson_withEmptyObject_createsAllNullFields() {
        ObjectNode json = Json.newObject();
        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertNotNull(summary);
        assertNull(summary.title);
        assertNull(summary.description);
        assertNull(summary.url);
        assertNull(summary.publishedAt);
        assertNull(summary.sourceId);
        assertNull(summary.sourceName);
    }

    /**
     * Test toJson with only source information.
     */
    @Test
    void toJson_withOnlySourceInfo_includesOnlySource() {
        ArticleSummary summary = new ArticleSummary(
                null,
                null,
                null,
                null,
                "source-123",
                "Test Source"
        );

        ObjectNode json = summary.toJson();

        assertFalse(json.has("title"));
        assertFalse(json.has("description"));
        assertFalse(json.has("url"));
        assertFalse(json.has("publishedAt"));
        assertTrue(json.has("source"));
        assertEquals("source-123", json.get("source").get("id").asText());
        assertEquals("Test Source", json.get("source").get("name").asText());
    }

    /**
     * Test with special characters in title and description.
     */
    @Test
    void constructor_withSpecialCharacters_storesCorrectly() {
        ArticleSummary summary = new ArticleSummary(
                "Breaking: \"Major\" Event & <Updates>",
                "Description with 'quotes' and symbols: $ € £",
                "https://example.com/article?id=123&ref=home",
                "2024-03-15T14:30:00Z",
                "source-id",
                "Source & News"
        );

        assertEquals("Breaking: \"Major\" Event & <Updates>", summary.title);
        assertEquals("Description with 'quotes' and symbols: $ € £", summary.description);
        assertEquals("https://example.com/article?id=123&ref=home", summary.url);
        assertEquals("Source & News", summary.sourceName);
    }

    /**
     * Test toJson handles special characters in fields.
     */
    @Test
    void toJson_withSpecialCharacters_encodesCorrectly() {
        ArticleSummary summary = new ArticleSummary(
                "Title with & and \"quotes\"",
                "Desc <test>",
                "https://test.com",
                "2024-01-01T00:00:00Z",
                "src-1",
                "Source & Co."
        );

        ObjectNode json = summary.toJson();
        assertEquals("Title with & and \"quotes\"", json.get("title").asText());
        assertEquals("Desc <test>", json.get("description").asText());
        assertEquals("Source & Co.", json.get("source").get("name").asText());
    }

    /**
     * Test with very long title and description.
     */
    @Test
    void constructor_withLongStrings_storesCorrectly() {
        String longTitle = "A".repeat(500);
        String longDesc = "B".repeat(1000);

        ArticleSummary summary = new ArticleSummary(
                longTitle,
                longDesc,
                "https://example.com",
                "2024-01-01T00:00:00Z",
                "source-id",
                "Source"
        );

        assertEquals(500, summary.title.length());
        assertEquals(1000, summary.description.length());
    }

    /**
     * Test fromJson with various date formats.
     */
    @Test
    void fromJson_withDifferentDateFormats_storesAsIs() {
        String[] dates = {
                "2024-01-01T00:00:00Z",
                "2024-12-31T23:59:59Z",
                "2024-06-15T12:30:45.123Z",
                "2024-03-15T10:00:00+00:00"
        };

        for (String date : dates) {
            ObjectNode json = Json.newObject();
            json.put("title", "Test");
            json.put("publishedAt", date);

            ArticleSummary summary = ArticleSummary.fromJson(json);
            assertEquals(date, summary.publishedAt);
        }
    }

    /**
     * Test toJson with various URL formats.
     */
    @Test
    void toJson_withDifferentUrlFormats_preservesExactly() {
        String[] urls = {
                "https://example.com",
                "https://example.com/path/to/article",
                "https://example.com/article?id=123&lang=en",
                "https://sub.example.com:8080/path",
                "http://example.com/article"
        };

        for (String url : urls) {
            ArticleSummary summary = new ArticleSummary(
                    "Title",
                    "Desc",
                    url,
                    "2024-01-01T00:00:00Z",
                    "id",
                    "name"
            );

            ObjectNode json = summary.toJson();
            assertEquals(url, json.get("url").asText());
        }
    }

    /**
     * Test fromJson with source having only id.
     */
    @Test
    void fromJson_withSourceIdOnly_storesIdAndNullName() {
        ObjectNode json = Json.newObject();
        json.put("title", "Test");

        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "source-123");
        json.set("source", sourceNode);

        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertEquals("source-123", summary.sourceId);
        assertNull(summary.sourceName);
    }

    /**
     * Test fromJson with source having only name.
     */
    @Test
    void fromJson_withSourceNameOnly_storesNameAndNullId() {
        ObjectNode json = Json.newObject();
        json.put("title", "Test");

        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("name", "Test Source");
        json.set("source", sourceNode);

        ArticleSummary summary = ArticleSummary.fromJson(json);

        assertNull(summary.sourceId);
        assertEquals("Test Source", summary.sourceName);
    }
}

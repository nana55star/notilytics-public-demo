package models;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import play.libs.Json;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceProfile}.
 * @author Alex Sutherland
 */
public class SourceProfileTest {

    /**
    Test that sources profile is populated with associated objects
    **/
    @Test
    void constructor_setsAllFields() {
        SourceDetails source = new SourceDetails(
                "bbc-news", "BBC News", "British news", "https://bbc.com",
                "general", "en", "gb"
        );
        ArticleSummary article1 = new ArticleSummary(
                "Article 1", "Desc 1", "https://bbc.com/1",
                "2024-01-01T12:00:00Z", "bbc-news", "BBC News"
        );
        ArticleSummary article2 = new ArticleSummary(
                "Article 2", "Desc 2", "https://bbc.com/2",
                "2024-01-02T12:00:00Z", "bbc-news", "BBC News"
        );
        List<ArticleSummary> articles = Arrays.asList(article1, article2);

        SourceProfile profile = new SourceProfile(source, articles, 100);

        assertNotNull(profile.getSource());
        assertEquals("bbc-news", profile.getSource().id);
        assertEquals(2, profile.getArticles().size());
        assertEquals("Article 1", profile.getArticles().get(0).title);
        assertEquals(100, profile.getTotalResults());
    }

    /**
    Test that constructor of source details with null articles for Source profile returns empty list
    **/
    @Test
    void constructor_withNullArticles_createsEmptyList() {
        SourceDetails source = new SourceDetails(
                "test", "Test", null, null, null, null, null
        );

        SourceProfile profile = new SourceProfile(source, null, 0);

        assertNotNull(profile.getArticles());
        assertEquals(0, profile.getArticles().size());
        assertEquals(0, profile.getTotalResults());
    }

    /*
    Test that constructor of source details with empty articles for Source profile returns empty list
    **/
    @Test
    void constructor_withEmptyArticles_createsEmptyList() {
        SourceDetails source = new SourceDetails(
                "test", "Test", null, null, null, null, null
        );

        SourceProfile profile = new SourceProfile(source, Collections.emptyList(), 0);

        assertNotNull(profile.getArticles());
        assertEquals(0, profile.getArticles().size());
    }

    /*
    Test that getArticles returns immutable list
    **/
    @Test
    void getArticles_returnsUnmodifiableList() {
        SourceDetails source = new SourceDetails(
                "test", "Test", null, null, null, null, null
        );
        ArticleSummary article = new ArticleSummary(
                "Title", "Desc", "url", "date", "id", "name"
        );
        List<ArticleSummary> articles = Arrays.asList(article);

        SourceProfile profile = new SourceProfile(source, articles, 1);
        List<ArticleSummary> retrievedArticles = profile.getArticles();

        // Verify it's a copy, not the original list
        assertNotSame(articles, retrievedArticles);
        assertEquals(1, retrievedArticles.size());

        // Attempting to modify should throw exception
        assertThrows(UnsupportedOperationException.class, () -> {
            retrievedArticles.add(new ArticleSummary("New", null, null, null, null, null));
        });
    }

    /*
    Test complete response creates proper json object nodes
    **/
    @Test
    void toJson_withCompleteData_createsValidJson() {
        SourceDetails source = new SourceDetails(
                "cnn", "CNN", "Cable News", "https://cnn.com",
                "general", "en", "us"
        );
        ArticleSummary article = new ArticleSummary(
                "Breaking News", "Important event", "https://cnn.com/breaking",
                "2024-03-15T10:00:00Z", "cnn", "CNN"
        );
        List<ArticleSummary> articles = Arrays.asList(article);

        SourceProfile profile = new SourceProfile(source, articles, 50);
        ObjectNode json = profile.toJson();

        assertEquals("ok", json.get("status").asText());
        assertTrue(json.has("source"));
        assertEquals("cnn", json.get("source").get("id").asText());
        assertTrue(json.has("articles"));
        assertTrue(json.get("articles").isArray());
        assertEquals(1, json.get("articles").size());
        assertEquals("Breaking News", json.get("articles").get(0).get("title").asText());
        assertEquals(50, json.get("totalResults").asInt());
    }

    /*
    Test that constructor of source profile accepts a null source and creates empty source in objectNode
    **/
    @Test
    void toJson_withNullSource_createsEmptySourceObject() {
        SourceProfile profile = new SourceProfile(null, Collections.emptyList(), 0);
        ObjectNode json = profile.toJson();

        assertEquals("ok", json.get("status").asText());
        assertTrue(json.has("source"));
        assertTrue(json.get("source").isObject());
        assertEquals(0, json.get("source").size());
    }

    /*
    Test that constructor of source profile accepts emtpy articles and creates empty article array in objectNode
    **/
    @Test
    void toJson_withNoArticles_createsEmptyArray() {
        SourceDetails source = new SourceDetails(
                "test", "Test", null, null, null, null, null
        );
        SourceProfile profile = new SourceProfile(source, Collections.emptyList(), 0);
        ObjectNode json = profile.toJson();

        assertTrue(json.has("articles"));
        assertTrue(json.get("articles").isArray());
        assertEquals(0, json.get("articles").size());
        assertEquals(0, json.get("totalResults").asInt());
    }

    /*
    Test that multiple articles are included in array
    **/
    @Test
    void toJson_withMultipleArticles_includesAllInArray() {
        SourceDetails source = new SourceDetails(
                "test", "Test", null, null, null, null, null
        );
        ArticleSummary article1 = new ArticleSummary(
                "Article 1", null, null, null, null, null
        );
        ArticleSummary article2 = new ArticleSummary(
                "Article 2", null, null, null, null, null
        );
        ArticleSummary article3 = new ArticleSummary(
                "Article 3", null, null, null, null, null
        );
        List<ArticleSummary> articles = Arrays.asList(article1, article2, article3);

        SourceProfile profile = new SourceProfile(source, articles, 100);
        ObjectNode json = profile.toJson();

        ArrayNode articlesArray = (ArrayNode) json.get("articles");
        assertEquals(3, articlesArray.size());
        assertEquals("Article 1", articlesArray.get(0).get("title").asText());
        assertEquals("Article 2", articlesArray.get(1).get("title").asText());
        assertEquals("Article 3", articlesArray.get(2).get("title").asText());
    }

    /*
    Test that correct source is returned
    **/
    @Test
    void getSource_returnsCorrectSource() {
        SourceDetails source = new SourceDetails(
                "id", "Name", "Desc", "url", "cat", "lang", "country"
        );
        SourceProfile profile = new SourceProfile(source, null, 0);

        SourceDetails retrieved = profile.getSource();
        assertEquals("id", retrieved.id);
        assertEquals("Name", retrieved.name);
    }

    /*
    Test that correct count is returned
    **/
    @Test
    void getTotalResults_returnsCorrectCount() {
        SourceDetails source = new SourceDetails(
                "test", "Test", null, null, null, null, null
        );
        SourceProfile profile = new SourceProfile(source, null, 42);

        assertEquals(42, profile.getTotalResults());
    }
}

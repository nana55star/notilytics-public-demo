package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class ReadabilityJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void addReadabilityToNewsApiJson_enrichesUpTo50Articles() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"ok\",\"articles\":[");
        for (int i = 0; i < 55; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"Title ").append(i)
                    .append("\",\"description\":\"Desc ").append(i).append("\"}");
        }
        sb.append("]}");
        String input = sb.toString();

        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        JsonNode root = MAPPER.readTree(output);

        assertEquals("ok", root.path("status").asText());
        JsonNode articles = root.path("articles");
        assertTrue(articles.isArray());
        assertEquals(55, articles.size());

        // first 50 should have readability fields
        for (int i = 0; i < 50; i++) {
            JsonNode a = articles.get(i);
            assertTrue(a.has("readingEase"));
            assertTrue(a.has("gradeLevel"));
        }
        // article 50+ might not (depending on your intended behavior)
        JsonNode a51 = articles.get(51);
        assertFalse("Article > 50 should not be annotated",
                a51.has("readingEase") && a51.has("gradeLevel"));
    }

    @Test
    public void addReadabilityToNewsApiJson_fallsBackToTitle() throws Exception {
        String input = "{\"status\":\"ok\",\"articles\":[{\"title\":\"Only title\",\"description\":null}]}";
        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        JsonNode root = MAPPER.readTree(output);
        JsonNode article = root.path("articles").get(0);

        assertTrue(article.has("readingEase"));
        assertTrue(article.has("gradeLevel"));
    }

    @Test
    public void addReadabilityToNewsApiJson_invalidJson_returnsOriginal() {
        String input = "not-json";
        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        assertEquals(input, output);
    }

    @Test
    public void addReadabilityToNewsApiJson_handlesMissingDescriptionAndTitle() throws Exception {
        String input = "{\"status\":\"ok\",\"articles\":[{\"description\":\"   \",\"title\":\"   \",\"content\":null}]}";

        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        JsonNode root = MAPPER.readTree(output);
        JsonNode article = root.path("articles").get(0);

        assertTrue(article.has("readingEase"));
        assertTrue(article.has("gradeLevel"));
        assertEquals(0.0, article.path("readingEase").asDouble(), 0.0001);
        assertEquals(0.0, article.path("gradeLevel").asDouble(), 0.0001);
    }

    @Test
    public void addReadabilityToNewsApiJson_rootNotObject_returnsOriginal() {
        String input = "[]";
        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        assertEquals(input, output);
    }

    @Test
    public void addReadabilityToNewsApiJson_articlesMissing_returnsOriginal() {
        String input = "{\"status\":\"ok\"}";
        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        assertEquals(input, output);
    }

    @Test
    public void addReadabilityToNewsApiJson_ignoresNonObjectArticles() throws Exception {
        String input = "{\"status\":\"ok\",\"articles\":[\"not-object\",{\"title\":\"Keep\"}]}";
        String output = ReadabilityJson.addReadabilityToNewsApiJson(input);
        JsonNode root = MAPPER.readTree(output);
        assertEquals(2, root.path("articles").size());
        JsonNode obj = root.path("articles").get(1);
        assertTrue(obj.has("readingEase"));
    }

    @Test
    public void helper_firstNonBlank_prefersFirstNonEmptyValue() throws Exception {
        Method m = ReadabilityJson.class.getDeclaredMethod("firstNonBlank", String[].class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, new Object[]{new String[]{"", "  ", "keep", "later"}});
        assertEquals("keep", result);
    }

    @Test
    public void helper_getText_returnsEmptyWhenFieldMissing() throws Exception {
        Method m = ReadabilityJson.class.getDeclaredMethod("getText", JsonNode.class, String.class);
        m.setAccessible(true);
        JsonNode node = MAPPER.readTree("{\"title\":\"value\"}");
        String result = (String) m.invoke(null, node, "missing");
        assertEquals("", result);
    }
}

package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Test suite for {@link SentimentJson} that validates the enrichment behavior
 * described in the assignment/file explanation for the sentiment feature.
 *
 * <h2>What the production code is expected to do</h2>
 * <ul>
 *   <li><b>Input:</b> a NewsAPI-style JSON where the root is an <em>object</em> that
 *       may contain an {@code "articles"} array.</li>
 *   <li><b>Per-article enrichment (first 50 only):</b> write a {@code "sentiment"} emoticon
 *       (one of {@code ":-)"}, {@code ":-("}, {@code ":-|"}) onto each
 *       <em>object</em> item in the array. Text is taken using the fallback
 *       order <b>description → title → content</b> as stated in the file explanation.</li>
 *   <li><b>Root aggregation:</b> compute {@code "overallSentiment"} from the first 50 items.</li>
 *   <li><b>Defensive behavior:</b> if the JSON is malformed, the root is not an object,
 *       {@code "articles"} is missing/null/not an array, or items are not objects,
 *       the method must be fail-safe: return the original JSON unchanged for structural
 *       guards, skip unsafe writes on non-object items, and still produce
 *       {@code overallSentiment} where possible.</li>
 * </ul>
 *
 * <p>The tests below cover:</p>
 * <ul>
 *   <li>All guard/early-return branches</li>
 *   <li>Nominal enrichment (object items, fallback order, overall present)</li>
 *   <li>50-item cap for per-article writes and overall calculation</li>
 *   <li>Non-object items behavior</li>
 *   <li>Private helper coverage via reflection without changing production visibility</li>
 * </ul>
 *
 * <p><b>JUnit:</b> 5.x (uses {@code org.junit.jupiter.api.Test} and Jupiter assertions).</p>
 *
 * @author
 *   zahra ebrahimizadehghahrood 
 */

public class SentimentJsonTest {

    /** Shared ObjectMapper for building and parsing JSON during tests. */
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * Utility to build a minimal NewsAPI-like JSON payload with the provided {@code articles} array.
     *
     * @param arr array to place under {@code "articles"} on the root object
     * @return a JSON string like {@code {"status":"ok","articles":[...]}}
     */
    private static String newsApiJsonWithArticles(ArrayNode arr) {
        ObjectNode root = M.createObjectNode();
        root.put("status", "ok");
        root.set("articles", arr);
        return root.toString();
    }

 
    /**
     * When the root is not a JSON object (e.g., it is an array),
     * the method must return the original payload unchanged.
     */    
    @Test
    public void returnsOriginal_whenRootNotObject() {
        String input = "[{\"title\":\"x\"}]";       // root is an array, not an object
        String out = SentimentJson.addSentimentToNewsApiJson(input);
        assertEquals(input, out, "Must return original when root is not an object");
    }

    /**
     * When {@code "articles"} is missing or explicitly {@code null},
     * the method must return the original payload unchanged.
     */
    @Test
    public void returnsOriginal_whenArticlesMissingOrNull() {
        // missing
        String noArticles = "{\"status\":\"ok\"}";
        assertEquals(noArticles, SentimentJson.addSentimentToNewsApiJson(noArticles));

        // present but null
        String nullArticles = "{\"status\":\"ok\",\"articles\":null}";
        assertEquals(nullArticles, SentimentJson.addSentimentToNewsApiJson(nullArticles));
    }

    /**
     * When {@code "articles"} exists but is not an array (e.g., it is an object),
     * the method must return the original payload unchanged.
     */
    @Test
    public void returnsOriginal_whenArticlesNotArray() {
        String notArray = "{\"status\":\"ok\",\"articles\":{}}"; // object instead of array
        String out = SentimentJson.addSentimentToNewsApiJson(notArray);
        assertEquals(notArray, out);
    }

    /**
     * Malformed JSON must not throw; the method should return the original string unchanged.
     */
    @Test
    public void returnsOriginal_whenMalformedJson() {
        String bad = "{ this is not valid json ";
        assertEquals(bad, SentimentJson.addSentimentToNewsApiJson(bad));
    }

    /**
     * With a valid object root and an <em>empty</em> {@code articles} array, no per-article writes occur,
     * but the root {@code "overallSentiment"} must still be added and be neutral ({@code ":-|"}).
     */
    @Test
    public void enriches_emptyArray_addsOverallNeutral() throws Exception {
        String in = "{\"status\":\"ok\",\"articles\":[]}";
        String out = SentimentJson.addSentimentToNewsApiJson(in);
        JsonNode root = M.readTree(out);
        assertTrue(root.has("overallSentiment"));
        assertEquals(":-|", root.get("overallSentiment").asText()); // NEUTRAL
        assertEquals(0, root.get("articles").size());
    }


    /**
     * Verifies per-article enrichment and the documented text fallback order:
     * <b>description → title → content</b>. Also checks that {@code overallSentiment} exists.
     */   
    @Test
    public void enriches_objectArticles_andAddsPerArticleSentiment() throws Exception {
        ArrayNode arr = M.createArrayNode();

        ObjectNode happy = M.createObjectNode();
        happy.put("description", "great amazing awesome"); // clearly HAPPY
        arr.add(happy);

        ObjectNode sad = M.createObjectNode();
        sad.put("title", "disaster crisis tragedy");        // clearly SAD
        arr.add(sad);

        ObjectNode neutral = M.createObjectNode();
        neutral.put("description", "   ");                  // force fallback chain
        neutral.put("title", "   ");
        neutral.put("content", "text with no keywords");    // NEUTRAL
        arr.add(neutral);

        String out = SentimentJson.addSentimentToNewsApiJson(newsApiJsonWithArticles(arr));
        JsonNode root = M.readTree(out);
        assertEquals(":-)", root.get("articles").get(0).get("sentiment").asText());
        assertEquals(":-(", root.get("articles").get(1).get("sentiment").asText());
        assertEquals(":-|", root.get("articles").get(2).get("sentiment").asText());
        assertTrue(root.has("overallSentiment"));
    }

    /**
     * Enforces the 50-item cap for both per-article annotation and the overall calculation.
     * <ul>
     *   <li>First 50 items are SAD → {@code overallSentiment} should be SAD.</li>
     *   <li>Item #51 (index 50) must not receive a {@code "sentiment"} field.</li>
     * </ul>
     */    
    @Test
    public void limitsToFirst50_articles() throws Exception {
        ArrayNode arr = M.createArrayNode();
        for (int i = 0; i < 50; i++) {
            ObjectNode a = M.createObjectNode();
            a.put("title", "disaster crisis tragedy"); // SAD
            arr.add(a);
        }
        // beyond 50 should not be enriched
        for (int i = 0; i < 5; i++) arr.add(M.createObjectNode().put("title", "great awesome"));

        String out = SentimentJson.addSentimentToNewsApiJson(newsApiJsonWithArticles(arr));
        JsonNode root = M.readTree(out);

        for (int i = 0; i < 50; i++) {
            assertTrue(root.get("articles").get(i).has("sentiment"));
        }
        assertFalse(root.get("articles").get(50).has("sentiment"),
                "51st must not be enriched");
        assertEquals(":-(", root.get("overallSentiment").asText()); // overall from first 50
    }

    /**
     * Non-object entries (text/boolean/null/number) must be left untouched (no per-article write),
     * while {@code overallSentiment} is still produced. With no polarity tokens, it should be neutral.
     */
    @Test
    public void nonObjectArticles_areIgnoredForFieldWrite_butCountNeutral() throws Exception {
        ArrayNode arr = M.createArrayNode();
        arr.add("plain string");               // TextNode
        arr.add(BooleanNode.TRUE);             // BooleanNode
        arr.add(NullNode.getInstance());       // NullNode
        arr.add(IntNode.valueOf(42));          // NumericNode (this is the FIX)

        String out = SentimentJson.addSentimentToNewsApiJson(newsApiJsonWithArticles(arr));
        JsonNode root = M.readTree(out);

        // articles remain non-objects (no "sentiment" field could be written)
        assertTrue(root.get("articles").get(0).isTextual());
        assertTrue(root.get("articles").get(1).isBoolean());
        assertTrue(root.get("articles").get(2).isNull());
        assertTrue(root.get("articles").get(3).isNumber());

        // overall still present; with all-neutral inputs it should be NEUTRAL
        assertTrue(root.has("overallSentiment"));
        assertEquals(":-|", root.get("overallSentiment").asText());
    }

    /**
     * Forces the full fallback chain to the third field (content): blank description and title,
     * so sentiment must be derived from {@code content} (which is happy).
     */
    @Test
    public void fallsBack_descriptionThenTitleThenContent() throws Exception {
        ArrayNode arr = M.createArrayNode();
        ObjectNode a = M.createObjectNode();
        a.put("description", "   "); // blank
        a.put("title", "");          // blank
        a.put("content", "great amazing"); // → HAPPY via 3rd fallback
        arr.add(a);

        String out = SentimentJson.addSentimentToNewsApiJson(newsApiJsonWithArticles(arr));
        JsonNode root = M.readTree(out);
        assertEquals(":-)", root.get("articles").get(0).get("sentiment").asText());
    }



    /**
     * Covers {@code getText(JsonNode, String)}:
     * <ul>
     *   <li>returns {@code ""} when the node is {@code null};</li>
     *   <li>returns the field's text when present and non-null;</li>
     *   <li>otherwise returns {@code ""}.</li>
     * </ul>
     */
    @Test
    public void getText_coversNullNodeShortCircuit_andPresentField() throws Exception {
        Method m = SentimentJson.class.getDeclaredMethod("getText",
                JsonNode.class, String.class);
        m.setAccessible(true);

        // n == null → short-circuit false branch ("" result)
        String r1 = (String) m.invoke(null, new Object[]{null, "any"});
        assertEquals("", r1);

        // n != null && hasNonNull(field) → true branch
        ObjectNode obj = M.createObjectNode().put("title", "hello");
        String r2 = (String) m.invoke(null, new Object[]{obj, "title"});
        assertEquals("hello", r2);

        // n != null && !hasNonNull(field) → false branch
        String r3 = (String) m.invoke(null, new Object[]{obj, "missing"});
        assertEquals("", r3);
    }

    /**
     * Covers {@code firstNonBlank(String...)}:
     * <ul>
     *   <li>returns the first non-blank string,</li>
     *   <li>skips blanks to return a later non-blank,</li>
     *   <li>returns {@code ""} when all values are blank/null or when there are zero args.</li>
     * </ul>
     */    
    @Test
    public void firstNonBlank_coversAllPathsIncludingAllBlank() throws Exception {
        Method m = SentimentJson.class.getDeclaredMethod("firstNonBlank", String[].class);
        m.setAccessible(true);

        // returns first immediately
        String a = (String) m.invoke(null, new Object[]{ new String[]{"ok", "x"} });
        assertEquals("ok", a);

        // skips first (blank) and returns second
        String b = (String) m.invoke(null, new Object[]{ new String[]{"   ", "yes", ""} });
        assertEquals("yes", b);

        // includes a null and all-blank → returns ""
        String c = (String) m.invoke(null, new Object[]{ new String[]{ null, "   ", "" } });
        assertEquals("", c);

        // zero arguments → loop not entered, returns ""
        String d = (String) m.invoke(null, new Object[]{ new String[]{} });
        assertEquals("", d);
    }
}

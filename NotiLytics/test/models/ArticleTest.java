package models;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link Article} model.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>The all-args constructor correctly assigns public fields.</li>
 *   <li>The no-args constructor initializes safe default values.</li>
 * </ul>
 *
 * @author Nirvana Borham
 */
public class ArticleTest {

    /**
     * Ensures the all-args constructor assigns each public field as provided.
     *
     * @author Nirvana Borham
     */
    @Test
    void constructor_setsPublicFields() {
        Article a = new Article(
                "Title",
                "Desc",
                "https://example.com",
                "The Verge"
        );

        assertEquals("Title", a.title);
        assertEquals("Desc", a.description);
        assertEquals("https://example.com", a.url);
        assertEquals("The Verge", a.sourceName);
    }

    /**
     * Ensures the no-args constructor sets safe default values
     * (either {@code null} or empty strings, depending on implementation).
     *
     * @author Nirvana Borham
     */
    @Test
    void defaultConstructor_setsSafeDefaults() {
        Article a = new Article();

        // Be permissive across implementations (null or empty)
        assertTrue(a.title == null || a.title.isEmpty());
        assertTrue(a.description == null || a.description.isEmpty());
        assertTrue(a.url == null || a.url.isEmpty());
        assertTrue(a.sourceName == null || a.sourceName.isEmpty());
    }

}

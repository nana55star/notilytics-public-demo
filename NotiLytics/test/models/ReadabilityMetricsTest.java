package models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReadabilityMetrics}.
 * @author Mustafa Kaya
 */
public class ReadabilityMetricsTest {

    @Test
    public void equalsAndHashCode_workForIdenticalObjects() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_detectsDifferences() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(69.9, 7.5, 100, 5, 140);
        assertNotEquals(a, b);
    }

    @Test
    public void toString_containsKeyFields() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        String s = a.toString();
        assertTrue(s.contains("readingEase="));
        assertTrue(s.contains("gradeLevel="));
    }

    /**
     * Test that constructor properly sets all fields.
     */
    @Test
    public void constructor_setsAllFields() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(85.5, 6.2, 50, 3, 75);
        assertEquals(85.5, metrics.getReadingEase(), 0.001);
        assertEquals(6.2, metrics.getGradeLevel(), 0.001);
        assertEquals(50, metrics.getWordCount());
        assertEquals(3, metrics.getSentenceCount());
        assertEquals(75, metrics.getSyllableCount());
    }

    /**
     * Test getters return correct values.
     */
    @Test
    public void getters_returnCorrectValues() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(92.3, 4.1, 30, 2, 40);
        assertEquals(92.3, metrics.getReadingEase());
        assertEquals(4.1, metrics.getGradeLevel());
        assertEquals(30, metrics.getWordCount());
        assertEquals(2, metrics.getSentenceCount());
        assertEquals(40, metrics.getSyllableCount());
    }

    /**
     * Test constructor with zero values.
     */
    @Test
    public void constructor_withZeroValues_storesZeros() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(0.0, 0.0, 0, 0, 0);
        assertEquals(0.0, metrics.getReadingEase());
        assertEquals(0.0, metrics.getGradeLevel());
        assertEquals(0, metrics.getWordCount());
        assertEquals(0, metrics.getSentenceCount());
        assertEquals(0, metrics.getSyllableCount());
    }

    /**
     * Test constructor with negative values (valid for readability scores).
     */
    @Test
    public void constructor_withNegativeScores_storesNegatives() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(-10.5, -2.3, 200, 10, 400);
        assertEquals(-10.5, metrics.getReadingEase());
        assertEquals(-2.3, metrics.getGradeLevel());
        assertEquals(200, metrics.getWordCount());
        assertEquals(10, metrics.getSentenceCount());
        assertEquals(400, metrics.getSyllableCount());
    }

    /**
     * Test equals method is reflexive (object equals itself).
     */
    @Test
    public void equals_reflexive_returnsTrue() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        assertEquals(metrics, metrics);
    }

    /**
     * Test equals method with null returns false.
     */
    @Test
    public void equals_withNull_returnsFalse() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        assertNotEquals(null, metrics);
    }

    /**
     * Test equals method with different type returns false.
     */
    @Test
    public void equals_withDifferentType_returnsFalse() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        assertNotEquals("not a metrics object", metrics);
    }

    /**
     * Test equals detects difference in grade level.
     */
    @Test
    public void equals_differentGradeLevel_returnsFalse() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(70.0, 8.0, 100, 5, 140);
        assertNotEquals(a, b);
    }

    /**
     * Test equals detects difference in word count.
     */
    @Test
    public void equals_differentWordCount_returnsFalse() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(70.0, 7.5, 101, 5, 140);
        assertNotEquals(a, b);
    }

    /**
     * Test equals detects difference in sentence count.
     */
    @Test
    public void equals_differentSentenceCount_returnsFalse() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(70.0, 7.5, 100, 6, 140);
        assertNotEquals(a, b);
    }

    /**
     * Test equals detects difference in syllable count.
     */
    @Test
    public void equals_differentSyllableCount_returnsFalse() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(70.0, 7.5, 100, 5, 141);
        assertNotEquals(a, b);
    }

    /**
     * Test hashCode consistency - same object returns same hash.
     */
    @Test
    public void hashCode_consistent_returnsSameValue() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        int hash1 = metrics.hashCode();
        int hash2 = metrics.hashCode();
        assertEquals(hash1, hash2);
    }

    /**
     * Test different objects have different hash codes (usually).
     */
    @Test
    public void hashCode_differentObjects_differentHashes() {
        ReadabilityMetrics a = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        ReadabilityMetrics b = new ReadabilityMetrics(80.0, 6.0, 90, 4, 120);
        assertNotEquals(a.hashCode(), b.hashCode());
    }

    /**
     * Test toString contains all field names.
     */
    @Test
    public void toString_containsAllFieldNames() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        String s = metrics.toString();
        assertTrue(s.contains("readingEase="));
        assertTrue(s.contains("gradeLevel="));
        assertTrue(s.contains("wordCount="));
        assertTrue(s.contains("sentenceCount="));
        assertTrue(s.contains("syllableCount="));
    }

    /**
     * Test toString contains all field values.
     */
    @Test
    public void toString_containsAllFieldValues() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(70.0, 7.5, 100, 5, 140);
        String s = metrics.toString();
        assertTrue(s.contains("70.0"));
        assertTrue(s.contains("7.5"));
        assertTrue(s.contains("100"));
        assertTrue(s.contains("5"));
        assertTrue(s.contains("140"));
    }

    /**
     * Test immutability - getters always return same values.
     */
    @Test
    public void immutability_gettersReturnSameValues() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(65.5, 8.2, 75, 4, 110);
        assertEquals(65.5, metrics.getReadingEase());
        assertEquals(65.5, metrics.getReadingEase()); // Call twice
        assertEquals(8.2, metrics.getGradeLevel());
        assertEquals(8.2, metrics.getGradeLevel()); // Call twice
    }

    /**
     * Test with very high readability scores (very easy text).
     */
    @Test
    public void constructor_withHighScores_storesCorrectly() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(120.0, 1.0, 20, 5, 25);
        assertEquals(120.0, metrics.getReadingEase());
        assertEquals(1.0, metrics.getGradeLevel());
    }

    /**
     * Test with large counts (long article).
     */
    @Test
    public void constructor_withLargeCounts_storesCorrectly() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(55.0, 12.5, 5000, 250, 7500);
        assertEquals(5000, metrics.getWordCount());
        assertEquals(250, metrics.getSentenceCount());
        assertEquals(7500, metrics.getSyllableCount());
    }

    /**
     * Test equals with object of different type - should return false.
     */
    @Test
    public void equals_withString_returnsFalse() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(75.0, 8.0, 100, 10, 150);
        assertFalse(metrics.equals("not a ReadabilityMetrics object"));
    }

    /**
     * Test equals with Integer object - should return false.
     */
    @Test
    public void equals_withInteger_returnsFalse() {
        ReadabilityMetrics metrics = new ReadabilityMetrics(75.0, 8.0, 100, 10, 150);
        assertFalse(metrics.equals(Integer.valueOf(42)));
    }
}

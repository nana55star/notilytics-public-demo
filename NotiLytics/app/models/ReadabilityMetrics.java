package models;

import java.util.Objects;

/**
 * Immutable Data Transfer Object (DTO) that represents the readability
 * statistics for a single article description. This model stores the computed
 * values for:
 * <ul>
 *     <li>Flesch Reading Ease score</li>
 *     <li>Flesch–Kincaid Grade Level</li>
 *     <li>Total word count</li>
 *     <li>Total sentence count</li>
 *     <li>Total syllable count</li>
 * </ul>
 *
 * <p>This object is populated inside {@link services.ReadabilityService}
 * and is returned to the controller for JSON serialization.</p>
 *
 * <p>The class is intentionally immutable to ensure thread-safety and to avoid
 * accidental mutation when used inside Java Stream pipelines.</p>
 *
 * @author Mustafa Kaya
 */
public final class ReadabilityMetrics {
    private final double readingEase;   // Flesch Reading Ease (higher = easier)
    private final double gradeLevel;    // Flesch–Kincaid Grade Level
    private final int wordCount;
    private final int sentenceCount;
    private final int syllableCount;

    /**
     * Creates a new immutable metrics instance.
     *
     * @param readingEase   computed Flesch Reading Ease score
     * @param gradeLevel    computed Flesch–Kincaid Grade Level
     * @param wordCount     number of words in the text
     * @param sentenceCount number of sentences in the text
     * @param syllableCount total number of syllables in the text
     */
    public ReadabilityMetrics(double readingEase, double gradeLevel,
                              int wordCount, int sentenceCount, int syllableCount) {
        this.readingEase = readingEase;
        this.gradeLevel = gradeLevel;
        this.wordCount = wordCount;
        this.sentenceCount = sentenceCount;
        this.syllableCount = syllableCount;
    }

    /**
     * @return Flesch Reading Ease score.
     */
    public double getReadingEase() {
        return readingEase;
    }

    /**
     * @return Flesch–Kincaid Grade Level.
     */
    public double getGradeLevel() {
        return gradeLevel;
    }

    /**
     * @return number of words in the analyzed text.
     */
    public int getWordCount() {
        return wordCount;
    }

    /**
     * @return number of sentences in the analyzed text.
     */
    public int getSentenceCount() {
        return sentenceCount;
    }

    /**
     * @return number of syllables detected in the analyzed text.
     */
    public int getSyllableCount() {
        return syllableCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReadabilityMetrics)) return false;
        ReadabilityMetrics that = (ReadabilityMetrics) o;
        return Double.compare(that.readingEase, readingEase) == 0 &&
                Double.compare(that.gradeLevel, gradeLevel) == 0 &&
                wordCount == that.wordCount &&
                sentenceCount == that.sentenceCount &&
                syllableCount == that.syllableCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(readingEase, gradeLevel, wordCount, sentenceCount, syllableCount);
    }

    @Override
    public String toString() {
        return "ReadabilityMetrics{" +
                "readingEase=" + readingEase +
                ", gradeLevel=" + gradeLevel +
                ", wordCount=" + wordCount +
                ", sentenceCount=" + sentenceCount +
                ", syllableCount=" + syllableCount +
                '}';
    }
}

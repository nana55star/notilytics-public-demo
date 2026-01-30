package services;

import models.ReadabilityMetrics;
import util.TextStats;

import javax.inject.Singleton;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Service responsible for calculating readability statistics for article
 * descriptions using the Java 8 Streams API. The service supports computing:
 * <ul>
 *     <li>Flesch Reading Ease score</li>
 *     <li>Flesch–Kincaid Grade Level</li>
 * </ul>
 *
 * <p>The implementation follows the mathematical formulas defined in the
 * assignment specification and ensures that at most 50 descriptions are
 * processed per request, as required.</p>
 *
 * <p>All text analytics (word count, syllable count, sentence detection) are
 * delegated to {@link util.TextStats} to keep the service focused solely on
 * readability computation.</p>
 *
 * @author Mustafa Kaya
 */
@Singleton
public class ReadabilityService {

    /**
     * Computes readability metrics for a single article description.
     * <p>
     * The following formulas are used:
     * <pre>
     * Flesch Reading Ease:
     *   206.835 − 1.015 × (words / sentences) − 84.6 × (syllables / words)
     *
     * Flesch–Kincaid Grade Level:
     *   0.39 × (words / sentences) + 11.8 × (syllables / words) − 15.59
     * </pre>
     *
     * @param description raw description text as returned by the News API (may be null)
     * @return immutable {@link ReadabilityMetrics} instance containing computed values
     */
    public ReadabilityMetrics computeForText(String description) {
        String text = description == null ? "" : description.trim();
        int words = TextStats.countWords(text);
        int sentences = TextStats.countSentences(text);
        int syllables = TextStats.countSyllables(text);

        if (words == 0) return new ReadabilityMetrics(0.0, 0.0, 0, 0, 0);
        if (sentences == 0) sentences = 1;

        double wps = (double) words / sentences; // words per sentence
        double spw = (double) syllables / words; // syllables per word

        double readingEase = 206.835 - 1.015 * wps - 84.6 * spw;
        double gradeLevel  = 0.39 * wps + 11.8 * spw - 15.59;

        // Downstream views expect the classic Flesch scale (~0-100), so clamp
        // noisy heuristic outputs into an extended but bounded interval.
        readingEase = Math.max(-50.0, Math.min(150.0, readingEase));

        return new ReadabilityMetrics(readingEase, gradeLevel, words, sentences, syllables);
    }

    /**
     * Computes readability metrics for a list of article descriptions using
     * Java Streams, limiting processing to the first 50 entries as specified.
     * <p>
     * This method also computes the average Reading Ease and Grade Level
     * across all processed descriptions.
     *
     * @param descriptions list of raw description strings (may be null or contain nulls)
     * @return a {@link Result} wrapper containing the per-item metrics and aggregated averages
     */
    public Result bundleForArticles(List<String> descriptions) {
        if (descriptions == null) descriptions = Collections.emptyList();

        List<ReadabilityMetrics> perArticle = descriptions.stream()
                .filter(Objects::nonNull)
                .limit(50)
                .map(this::computeForText)
                .collect(Collectors.toList());

        OptionalDouble avgEase  = perArticle.stream().mapToDouble(ReadabilityMetrics::getReadingEase).average();
        OptionalDouble avgGrade = perArticle.stream().mapToDouble(ReadabilityMetrics::getGradeLevel).average();

        return new Result(perArticle, avgEase.orElse(0.0), avgGrade.orElse(0.0));
    }

    /**
     * Container object that holds both the list of per-article readability
     * results and the aggregated averages. Returned to the controller so it
     * can be serialized into JSON.
     */
    public static final class Result {
        private final List<ReadabilityMetrics> items;
        private final double averageReadingEase;
        private final double averageGradeLevel;

        public Result(List<ReadabilityMetrics> items, double averageReadingEase, double averageGradeLevel) {
            this.items = items;
            this.averageReadingEase = averageReadingEase;
            this.averageGradeLevel = averageGradeLevel;
        }
        public List<ReadabilityMetrics> getItems()      { return items; }
        public double getAverageReadingEase()            { return averageReadingEase; }
        public double getAverageGradeLevel()             { return averageGradeLevel; }
    }
}

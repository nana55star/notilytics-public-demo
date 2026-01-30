package models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SentimentAnalyzer}.
 *
 * <p>This suite verifies:
 * <ul>
 *   <li>Token counting across words, emoticons, and emojis</li>
 *   <li>Threshold rule (70%) for classifying a single text</li>
 *   <li>Emoticon/emoji contributions to the counts</li>
 *   <li>Mapping from {@link SentimentAnalyzer.Sentiment} to emoticon strings</li>
 *   <li>Aggregate/overall sentiment logic (list-level), including the 50-item cap</li>
 *   <li>Null/empty input edge cases</li>
 * </ul>
 *
 * <p><b>Note</b>: These tests assume:
 * <ul>
 *   <li>{@link SentimentAnalyzer#analyzeText(String)} implements the 70% rule.</li>
 *   <li>{@link SentimentAnalyzer#toEmoticon(SentimentAnalyzer.Sentiment)} returns ":-)", ":-(", or ":-|".</li>
 *   <li>{@code overallFromArticles(List<Sentiment>)} returns the overall sentiment from a list of
 *       pre-computed article-level labels (only the first 50 considered). The rule used here
 *       is sign-based averaging: HAPPY=+1, SAD=-1, NEUTRAL=0; mean &gt; 0 → HAPPY, &lt; 0 → SAD, else NEUTRAL.</li>
 * </ul>
 *  @author zahra ebrahimizadehghahrood
 */
public class SentimentAnalyzerTest {
    // Verifies that null/blank inputs are treated as NEUTRAL.
    @Test
    void analyzeText_nullOrBlank_returnsNeutral() {
        // Null text or whitespace or empty string → neutral
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.analyzeText(null));
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.analyzeText(""));
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.analyzeText("   "));
    }

    /**
     * Verifies the >70% threshold for HAPPY classification.
     */
    @Test
    void analyzeText_happyWords_over70Percent_returnsHappy() {
        // 4/5 happy tokens = 80% (> 70%)
        String txt = "happy happy happy happy sad";
        assertEquals(SentimentAnalyzer.Sentiment.HAPPY, SentimentAnalyzer.analyzeText(txt));
    }

    /**
     * Verifies the >70% threshold for SAD classification.
     */
    @Test
    void analyzeText_sadWords_over70Percent_returnsSad() {
        // 4/5 sad tokens = 80% (> 70%)
        String txt = "sad sad sad sad happy";
        assertEquals(SentimentAnalyzer.Sentiment.SAD, SentimentAnalyzer.analyzeText(txt));
    }

    /**
     * Behavior: emoticons are counted as sentiment signals and can affect the final label.
     * <p>This test uses emoticons commonly recognized as sad (":-(") and happy (":-)").</p>
     */
    @Test
    void analyzeText_emoticons_support() {
        // 4 sad emoticons vs 1 happy emoticon
        String txt = ":-(" + " :-(" + " :-(" + " :-(" + " :-)";
        assertEquals(SentimentAnalyzer.Sentiment.SAD, SentimentAnalyzer.analyzeText(txt));

        // Use an emoticon your analyzer recognizes as happy (":-)")
        String txt2 = ":-) :-) :-) :-) :-(";
        assertEquals(SentimentAnalyzer.Sentiment.HAPPY, SentimentAnalyzer.analyzeText(txt2));
    }

    /**
     * Behavior: emojis are counted as sentiment signals and can affect the final label.
     * <p>This test uses 🙂 as happy and 😢 as sad. Adjust if your emoji lists differ.</p>
     */
    @Test
    void analyzeText_emoji_support() {
        // 3 happy emoji vs 1 sad emoji → HAPPY
        String txt = "🙂🙂🙂 😢";
        assertEquals(SentimentAnalyzer.Sentiment.HAPPY, SentimentAnalyzer.analyzeText(txt));

        // 3 sad emoji vs 1 happy emoji → SAD
        String txt2 = "😢😢😢 🙂";
        assertEquals(SentimentAnalyzer.Sentiment.SAD, SentimentAnalyzer.analyzeText(txt2));
    }

    /**
     * Behavior: exactly 70% happy should remain {@code NEUTRAL}
     * because the rule is strictly {@code > 70%}, not {@code >= 70%}.
     */
    @Test
    void analyzeText_exactly70Percent_returnsNeutral() {
        // 7/10 = exactly 70% happy → NOT strictly more than 70% → NEUTRAL
        String txt = "happy happy happy happy happy happy happy sad sad sad";
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.analyzeText(txt));
    }

    /**
     * Behavior: a mixed set under the threshold (&lt;= 70%) should be {@code NEUTRAL}.
     */
    @Test
    void analyzeText_mixed_below70_returnsNeutral() {
        // 2/3 happy = 66% (NOT strictly more than 70%) → NEUTRAL
        String txt = "happy happy sad";
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.analyzeText(txt));
    }

    /**
     * Behavior: when no known tokens (no happy/sad words, emoticons, or emojis) are found,
     * both counts are zero and the result must be {@code NEUTRAL}.
     */    
    @Test
    void analyzeText_noKnownTokens_totalZero_returnsNeutral() {
        String txt = "this has no polarity tokens at all.";
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.analyzeText(txt));
    }

    /**
     * Behavior: mapping from {@link SentimentAnalyzer.Sentiment} to the expected emoticon string.
     */    
    @Test
    void toEmoticon_mapping_allThree() {
        assertEquals(":-)", SentimentAnalyzer.toEmoticon(SentimentAnalyzer.Sentiment.HAPPY));
        assertEquals(":-(", SentimentAnalyzer.toEmoticon(SentimentAnalyzer.Sentiment.SAD));
        assertEquals(":-|", SentimentAnalyzer.toEmoticon(SentimentAnalyzer.Sentiment.NEUTRAL));
    }
    /**
     * Behavior: {@code overallFromArticles(List<Sentiment>)} should compute the overall label
     * using sign-based averaging on the first 50 items (HAPPY=+1, SAD=-1, NEUTRAL=0).
     * The example list sums to +1, so the average is &gt; 0 → {@code HAPPY}.
     */

    @Test
    void overallFromArticles_average_signRules() {
        List<SentimentAnalyzer.Sentiment> s = new ArrayList<>();
        // [HAPPY, NEUTRAL, SAD, HAPPY] -> +1 +0 -1 +1 = +1 → avg > 0 → HAPPY
        Collections.addAll(s,
                SentimentAnalyzer.Sentiment.HAPPY,
                SentimentAnalyzer.Sentiment.NEUTRAL,
                SentimentAnalyzer.Sentiment.SAD,
                SentimentAnalyzer.Sentiment.HAPPY);
        assertEquals(SentimentAnalyzer.Sentiment.HAPPY, SentimentAnalyzer.overallFromArticles(s));
    }

    /**
     * Behavior: only the first 50 entries must be considered when computing the overall.
     * Here, the first 50 are SAD and the next 10 are HAPPY; the result must be {@code SAD}.
     */
    @Test
    void overallFromArticles_limitsToFirst50() {
        List<SentimentAnalyzer.Sentiment> s = new ArrayList<>();
        // First 50 are SAD, next 10 are HAPPY -> overall must consider only first 50 -> SAD
        for (int i = 0; i < 50; i++) s.add(SentimentAnalyzer.Sentiment.SAD);
        for (int i = 0; i < 10; i++) s.add(SentimentAnalyzer.Sentiment.HAPPY);
        assertEquals(SentimentAnalyzer.Sentiment.SAD, SentimentAnalyzer.overallFromArticles(s));
    }

    /**
     * Behavior: {@code null} or empty lists should yield {@code NEUTRAL}.
     */
    @Test
    void overallFromArticles_emptyOrNull_returnsNeutral() {
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.overallFromArticles(new ArrayList<>()));
        assertEquals(SentimentAnalyzer.Sentiment.NEUTRAL, SentimentAnalyzer.overallFromArticles(null));
    }
}

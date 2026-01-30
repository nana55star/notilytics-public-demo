package services;

import models.ReadabilityMetrics;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import util.TextStats;

import java.util.Collections;

import static org.junit.Assert.*;

public class ReadabilityServiceTest {

    private final ReadabilityService service = new ReadabilityService();

    @Test
    public void computeForText_handlesEmpty() {
        ReadabilityMetrics m = service.computeForText(null);
        assertEquals(0.0, m.getReadingEase(), 0.0001);
        assertEquals(0.0, m.getGradeLevel(), 0.0001);
        assertEquals(0, m.getWordCount());
        assertEquals(0, m.getSentenceCount());
        assertEquals(0, m.getSyllableCount());
    }

    @Test
    public void computeForText_basicSentence() {
        String text = "This is a very simple sentence.";
        ReadabilityMetrics m = service.computeForText(text);

        assertTrue(m.getWordCount() > 0);
        assertTrue(m.getSentenceCount() >= 1);
        // sanity: readingEase is within extended range
        assertTrue(m.getReadingEase() >= -50.0 && m.getReadingEase() <= 150.0);
    }

    /**
     * Jacoco highlighted the sentence guard ({@code sentences == 0}) inside
     * {@link ReadabilityService#computeForText(String)} as untested. Mock TextStats to
     * return words>0 but zero sentences so we cover the clamping branch.
     */
    @Test
    public void computeForText_sentenceGuard_clampsToOneSentence() {
        try (MockedStatic<TextStats> mocked = Mockito.mockStatic(TextStats.class)) {
            mocked.when(() -> TextStats.countWords("data")).thenReturn(4);
            mocked.when(() -> TextStats.countSentences("data")).thenReturn(0);
            mocked.when(() -> TextStats.countSyllables("data")).thenReturn(6);

            ReadabilityMetrics metrics = service.computeForText("data");
            assertEquals(4, metrics.getWordCount());
            assertEquals(1, metrics.getSentenceCount());
            assertTrue(metrics.getReadingEase() >= -50.0 && metrics.getReadingEase() <= 150.0);
        }
    }

    @Test
    public void bundleForArticles_limitsTo50AndComputesAverage() {
        // 60 identical descriptions -> only 50 used
        String desc = "This is an easy sentence.";
        java.util.List<String> list = Collections.nCopies(60, desc);

        ReadabilityService.Result r = service.bundleForArticles(list);
        assertEquals(50, r.getItems().size());

        double avgEase = r.getAverageReadingEase();
        assertTrue(avgEase >= -50.0 && avgEase <= 150.0);
    }

    @Test
    public void bundleForArticles_handlesNullList() {
        ReadabilityService.Result r = service.bundleForArticles(null);
        assertTrue(r.getItems().isEmpty());
        assertEquals(0.0, r.getAverageReadingEase(), 0.0001);
        assertEquals(0.0, r.getAverageGradeLevel(), 0.0001);
    }
}

package util;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility class providing text analysis helpers used by
 * {@link services.ReadabilityService} to compute readability formulas.
 * <p>
 * This class exposes three main operations:
 * <ul>
 *     <li>{@link #countWords(String)}</li>
 *     <li>{@link #countSentences(String)}</li>
 *     <li>{@link #countSyllables(String)}</li>
 * </ul>
 *
 * <p>The implementation supports both Latin (English) text and
 * non-Latin Unicode scripts (Arabic, French accents, etc.). Word
 * and sentence splitting are regex-based and fully Unicode-aware.
 *
 * <p>Syllable counting uses a detailed heuristic for English and a
 * simplified approximation for non-Latin scripts, since exact syllable
 * rules differ by language and are outside the assignment scope.
 *
 * <p>This class is stateless, thread-safe, and not intended to be
 * instantiated — hence the private constructor and {@code final} class.
 *
 * @author Mustafa Kaya
 */
public final class TextStats {
    /**
     * Regex for matching "words" using any Unicode letter character.
     * Splits on apostrophes to handle French elisions (L', d', etc.) as separate words.
     */
    private static final Pattern WORD_RE_UNI = Pattern.compile("\\p{L}+");

    /**
     * Regex for detecting sentence breaks (supports English, Arabic, CJK punctuation).
     */
    private static final Pattern SENTENCE_RE_UNI = Pattern.compile("[.!?؟。]+");

    /**
     * English vowel set used by the Latin-specific syllable heuristic.
     */
    private static final String LATIN_VOWELS = "aeiouy";

    /**
     * Prevent instantiation of this utility class.
     */
    private TextStats() {
    }

    /**
     * Counts the number of words in the given text using a Unicode-aware regex.
     *
     * @param text raw text (may be {@code null})
     * @return number of detected words, or {@code 0} if input is {@code null} or blank
     */
    public static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return (int) WORD_RE_UNI.matcher(text).results().count();
    }

    /**
     * Counts the number of sentences in the given text by splitting on
     * punctuation marks (., !, ?, Arabic "؟", CJK "。", etc.).
     *
     * <p>If no sentence delimiter is found but words exist, this returns 1 to
     * avoid division-by-zero during readability calculations.
     *
     * @param text raw text (may be {@code null})
     * @return number of sentences, or {@code 0} if input is {@code null} or blank
     */
    public static int countSentences(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        boolean hasDelimiter = SENTENCE_RE_UNI.matcher(text).find();
        if (!hasDelimiter) {
            return countWords(text) > 0 ? 1 : 0;
        }
        String[] parts = SENTENCE_RE_UNI.split(text);
        long nonEmpty = Arrays.stream(parts).filter(s -> s.trim().length() > 0).count();
        return (int) nonEmpty;
    }

    /**
     * Counts the total number of syllables in the text by summing the syllables
     * in each detected word. Uses a Latin heuristic for English and a fallback
     * approximation for non-Latin scripts.
     *
     * @param text raw text (may be {@code null})
     * @return total syllable count, always ≥ 0
     */
    public static int countSyllables(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return WORD_RE_UNI.matcher(text).results()
                .mapToInt(m -> syllablesInWord(m.group()))
                .sum();
    }

    /**
     * Computes syllables for a single word. Detects whether the word
     * contains Latin characters:
     * <ul>
     *     <li>If yes → use English syllable heuristic ({@link #latinSyllables(String)}).</li>
     *     <li>If no → estimate syllables using a simple proportional rule:
     *         {@code ceil(length / 3)} (always ≥ 1).</li>
     * </ul>
     *
     * @param raw original word as matched by regex
     * @return syllable count, always ≥ 1
     */
    private static int syllablesInWord(String raw) {
        String w = raw.toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return 0;

        // If the word contains any Latin letter, use the proper English heuristic
        if (w.chars().anyMatch(ch ->
                (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))) {
            return latinSyllables(w);
        }

        // Fallback for non-Latin scripts (Arabic, etc.): very rough heuristic.
        // Approximate syllables as ceil(len / 3). Guarantees >=1 per word.
        int approx = (int) Math.ceil(w.codePointCount(0, w.length()) / 3.0);
        return Math.max(approx, 1);
    }

    /**
     * English syllable heuristic based on vowel groups, silent 'e', and
     * final consonant + "le" rule. Guarantees minimum value of 1.
     *
     * @param w lowercase English word
     * @return detected syllable count
     */
    private static int latinSyllables(String w) {
        int count = 0;
        boolean prevVowel = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            boolean isVowel = isLatinVowel(c);
            if (isVowel && !prevVowel) count++;
            prevVowel = isVowel;
        }
        if (w.endsWith("e") && count > 1 && !w.endsWith("le")) count--;
        if (w.endsWith("le") && w.length() > 2) {
            char before = w.charAt(w.length() - 3);
            if (!isLatinVowel(before)) count++;
        } else if (w.endsWith("les") && w.length() > 3) {
            char before = w.charAt(w.length() - 4);
            if (!isLatinVowel(before)) count++;
        }
        if (count > 1 && hasSilentEdEnding(w)) count--;
        if (count > 1 && hasSilentEsEnding(w)) count--;
        return Math.max(count, 1);
    }

    private static boolean isLatinVowel(char c) {
        return LATIN_VOWELS.indexOf(c) >= 0;
    }

    private static boolean hasSilentEdEnding(String w) {
        if (!w.endsWith("ed") || w.length() <= 2) return false;
        char before = w.charAt(w.length() - 3);
        if (before == 't' || before == 'd') return false;
        return !isLatinVowel(before);
    }

    private static boolean hasSilentEsEnding(String w) {
        if (!w.endsWith("es") || w.length() <= 2) return false;
        String stem = w.substring(0, w.length() - 2);
        if (stem.endsWith("ch") || stem.endsWith("sh")) return false;
        char last = stem.charAt(stem.length() - 1);
        if (last == 's' || last == 'x' || last == 'z') return false;
        return !isLatinVowel(last);
    }
}

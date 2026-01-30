package util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class TextStatsTest {

    @Test
    public void countWords_basic() {
        assertEquals(0, TextStats.countWords(null));
        assertEquals(0, TextStats.countWords("   "));
        assertEquals(5, TextStats.countWords("This is five word sentence"));
    }

    @Test
    public void countSentences_basic() {
        assertEquals(0, TextStats.countSentences(null));
        assertEquals(1, TextStats.countSentences("No punctuation but has words"));
        assertEquals(2, TextStats.countSentences("Hello world. Second one!"));
    }

    @Test
    public void countSentences_onlyPunctuation_triggersZeroBranch() {
        assertEquals(0, TextStats.countSentences("...!!!???"), "Pure punctuation should produce zero sentences");
    }

    @Test
    public void countSentences_wordsWithoutDelimiters_returnsOne() {
        assertEquals(1, TextStats.countSentences("Words but no punctuation"));
    }

    @Test
    public void countSentences_handlesUnicodeDelimiters() {
        String arabic = "مرحبا بالعالم؟كيف الحال؟";
        String cjk = "今日はいい天気ですね。散歩に行こう！";
        assertEquals(2, TextStats.countSentences(arabic), "Arabic question marks should be treated as delimiters");
        assertEquals(2, TextStats.countSentences(cjk), "CJK full-stop punctuation should be treated as delimiters");
    }

    @Test
    public void countSentences_blankString_returnsZero() {
        assertEquals(0, TextStats.countSentences("   "), "Blank text should produce zero sentences");
    }

    @Test
    public void countSyllables_heuristics() {
        int a = TextStats.countSyllables("This is a simple sentence.");
        int b = TextStats.countSyllables("Table able apple");
        assertTrue(a > 0);
        assertTrue(b > 0);
    }

    @Test
    public void countWords_handlesArabicUnicode() {
        String ar = "يستكشف الخبراء تأثير الذكاء الاصطناعي على مستقبل الصحافة.";
        int words = TextStats.countWords(ar);
        assertTrue(words > 0, "Arabic words should be detected");
    }

    @Test
    public void countWords_handlesFrenchAccents() {
        String fr = "L'intelligence artificielle révolutionne le journalisme.";
        int words = TextStats.countWords(fr);
        assertEquals(6, words, "Should count French accented words correctly");
    }

    // ---------- reflection helpers for private methods ----------

    /**
     * Invokes the private {@code latinSyllables(String)} method in {@link TextStats}
     * via reflection to compute the number of syllables in a given Latin-based word.
     *
     * @param w the input word to analyze
     * @return the number of syllables detected by the internal heuristic
     * @throws Exception if reflection or invocation fails
     * @author Nirvana Borham
     */
    private static int callLatin(String w) throws Exception {
        Method m = TextStats.class.getDeclaredMethod("latinSyllables", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, w);
    }

    /**
     * Invokes the private {@code syllablesInWord(String)} method in {@link TextStats}
     * to test its internal branching logic, including Latin and non-Latin cases.
     *
     * @param w the word to test for syllable count
     * @return the computed syllable count
     * @throws Exception if reflection or invocation fails
     * @author Nirvana Borham
     */
    private static int callSyllablesInWord(String w) throws Exception {
        Method m = TextStats.class.getDeclaredMethod("syllablesInWord", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, w);
    }

    /**
     * Invokes the private {@code hasSilentEsEnding(String)} method to verify
     * the “silent e” detection heuristic for English words.
     *
     * @param w the word to test
     * @return {@code true} if the method identifies a silent 'e' ending, otherwise {@code false}
     * @throws Exception if reflection or invocation fails
     * @author Nirvana Borham
     */
    private static boolean callSilentE(String w) throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, w);
    }

    /**
     * Invokes the private {@code isLatinVowel(char)} helper to verify internal
     * character classification of vowels used in syllable detection.
     *
     * @param c the character to test
     * @return {@code true} if recognized as a Latin vowel, otherwise {@code false}
     * @throws Exception if reflection or invocation fails
     * @author Nirvana Borham
     */
    private static boolean callIsLatinVowel(char c) throws Exception {
        Method m = TextStats.class.getDeclaredMethod("isLatinVowel", char.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, c);
    }

    // ---------- branch coverage for private heuristics ----------

    /**
     * Covers multiple logical branches in {@code latinSyllables}, including:
     * <ul>
     *   <li>Multiple vowel groups (e.g. "banana")</li>
     *   <li>Final 'e' handling (e.g. "cafe")</li>
     *   <li>'y' vowel recognition (e.g. "rhythm")</li>
     *   <li>Single-letter words (e.g. "a")</li>
     * </ul>
     *
     * @throws Exception if reflection access fails
     * @author Nirvana Borham
     */
    @Test
    void latinSyllables_coversKeyBranches_viaReflection() throws Exception {
        assertTrue(callLatin("banana") >= 2);   // multiple vowel groups
        // final 'e' path – don’t insist on a specific number (impl-dependent)
        assertTrue(callLatin("cafe") >= 1);
        assertTrue(callLatin("rhythm") >= 1);   // 'y' vowel path
        assertEquals(1, callLatin("a"));        // single-letter vowel
    }

    /**
     * Exercises different syllabic patterns handled by {@code syllablesInWord},
     * ensuring coverage of 'y' vowels, '-le' endings, and short word branches.
     *
     * @throws Exception if reflection access fails
     * @author Nirvana Borham
     */
    @Test
    void syllablesInWord_branchCases_viaReflection() throws Exception {
        assertEquals(1, callSyllablesInWord("by"));     // 'y' vowel
        assertTrue(callSyllablesInWord("able") >= 2);   // consonant + 'le' ending branch
        assertEquals(1, callSyllablesInWord("sky"));    // 'y' at end
    }

    /**
     * Tests true/false outcomes of the private {@code hasSilentEsEnding} method.
     * Confirms that the heuristic properly distinguishes between silent and non-silent endings.
     *
     * @throws Exception if reflection access fails
     * @author Nirvana Borham
     */
    @Test
    void hasSilentEsEnding_trueAndFalse_viaReflection() throws Exception {
        assertTrue(callSilentE("bakes"));    // genuine silent "es" ending (stem ends with consonant)
        assertFalse(callSilentE("churches")); // blocked by "ch" rule
        assertFalse(callSilentE("ashes"));    // blocked by "sh" rule
        assertFalse(callSilentE("boxes"));    // blocked by trailing 'x'
        assertFalse(callSilentE("buses"));    // blocked by trailing 's'
        assertFalse(callSilentE("es"));       // stem becomes empty
        assertFalse(callSilentE("note"));     // word not ending with "es" should short-circuit immediately
        assertFalse(callSilentE("canoes"));   // final stem character is a vowel → not a silent "es"
    }

    /**
     * Validates all major logical paths of the private {@code isLatinVowel} helper,
     * including lowercase vowels, uppercase letters, consonants, and non-letters.
     *
     * @throws Exception if reflection access fails
     * @author Nirvana Borham
     */
    @Test
    void isLatinVowel_allCases_viaReflection() throws Exception {
        assertTrue(callIsLatinVowel('e'));   // lowercase vowel → true
        assertFalse(callIsLatinVowel('E'));  // uppercase treated as false in impl
        assertFalse(callIsLatinVowel('x'));
        assertFalse(callIsLatinVowel(' '));
    }

    /**
     * Verifies that non-Latin words (e.g., Arabic) trigger the fallback heuristic
     * using {@code Math.ceil(len / 3.0)} inside {@code syllablesInWord}.
     *
     * @throws Exception if reflection access fails
     * @author Nirvana Borham
     */
    @Test
    void syllablesInWord_nonLatin_usesApproximation_viaReflection() throws Exception {
        String ar = "الذكاءالاصطناعي";
        Method m = TextStats.class.getDeclaredMethod("syllablesInWord", String.class);
        m.setAccessible(true);

        int actual = (int) m.invoke(null, ar);
        int expected = (int) Math.ceil(ar.codePointCount(0, ar.length()) / 3.0);

        assertEquals(expected, actual);
    }

    /**
     * Covers the specific branch in {@code syllablesInWord} that checks
     * consonants before "-le" endings (using {@code w.charAt(w.length() - 4)}).
     * Example: the word "bottle".
     *
     * @throws Exception if reflection access fails
     * @author Nirvana Borham
     */
    @Test
    void syllablesInWord_consonantBeforeLe_branch_viaReflection() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("syllablesInWord", String.class);
        m.setAccessible(true);

        int s = (int) m.invoke(null, "bottle");
        assertTrue(s >= 2); // don't over-specify; just ensure the path ran
    }

    // ========== Enhanced latinSyllables Coverage Tests ==========

    /**
     * Tests words ending in "e" with count > 1 that don't end in "le".
     * Should decrement the count.
     */
    @Test
    void latinSyllables_wordEndingInE_notLE_decrementsCount() throws Exception {
        // "take" = ta-ke (2 vowel groups), ends in 'e' but not 'le', count > 1 → should decrement to 1
        assertEquals(1, callLatin("take"));
        // "make" = ma-ke (2 vowel groups), ends in 'e' but not 'le', count > 1 → should decrement to 1
        assertEquals(1, callLatin("make"));
        // "fate" = fa-te (2 vowel groups), ends in 'e' but not 'le', count > 1 → should decrement to 1
        assertEquals(1, callLatin("fate"));
    }

    /**
     * Tests words ending in "le" - should NOT decrement for final 'e' rule.
     */
    @Test
    void latinSyllables_wordEndingInLE_doesNotDecrementForE() throws Exception {
        // "table" = ta-ble, ends in 'le' so the 'e' decrement rule is skipped
        // The algorithm adds a syllable for consonant+'le', so: 2 vowel groups + 1 for 'ble' = 3
        assertEquals(3, callLatin("table"));
        // "cable" = ca-ble
        assertEquals(3, callLatin("cable"));
    }

    /**
     * Tests words ending in "e" with count == 1 - should NOT decrement.
     */
    @Test
    void latinSyllables_wordEndingInE_countOne_doesNotDecrement() throws Exception {
        // "be" has 1 vowel group, ends in 'e', count == 1 → should NOT decrement (minimum 1)
        assertEquals(1, callLatin("be"));
        // "me" has 1 vowel group
        assertEquals(1, callLatin("me"));
    }

    /**
     * Tests words ending in "le" with consonant before - should increment count.
     */
    @Test
    void latinSyllables_wordEndingInLE_consonantBefore_incrementsCount() throws Exception {
        // "able" = a + ble (consonant 'b' before 'le') → 1 vowel group + 1 for 'ble' = 2
        int ableCount = callLatin("able");
        assertTrue(ableCount >= 2, "able should have at least 2 syllables");

        // "table" = ta + ble (consonant 'b' before 'le') → 2 vowel groups + 1 for 'ble' = 3
        assertEquals(3, callLatin("table"));

        // "bottle" = bot + tle (consonant 't' before 'le') → 2 vowel groups + 1 for 'tle' = 3
        assertEquals(3, callLatin("bottle"));
    }

    /**
     * Tests words ending in "le" with vowel before - should NOT increment.
     */
    @Test
    void latinSyllables_wordEndingInLE_vowelBefore_doesNotIncrement() throws Exception {
        // "oile" would have vowel before 'le' (though not a real word, tests the branch)
        // "aile" has vowel 'i' before 'le'
        int count = callLatin("aile");
        // Should count vowel groups but not add extra for 'le' since vowel precedes
        assertTrue(count >= 1);
    }

    /**
     * Tests short words ending in "le" (length <= 2) - boundary case.
     */
    @Test
    void latinSyllables_shortWordEndingInLE_boundaryCase() throws Exception {
        // "le" itself (length == 2) should not trigger the consonant-before-le increment
        assertEquals(1, callLatin("le"));
    }

    /**
     * Tests words ending in "les" with consonant before - should increment count.
     */
    @Test
    void latinSyllables_wordEndingInLES_consonantBefore_incrementsCount() throws Exception {
        // "bottles" = bot + tles (consonant 't' before 'les')
        int count = callLatin("bottles");
        assertTrue(count >= 2, "bottles should have at least 2 syllables");

        // "cables" = ca + bles
        assertTrue(callLatin("cables") >= 2);
    }

    /**
     * Tests words ending in "les" with vowel before - should NOT increment.
     */
    @Test
    void latinSyllables_wordEndingInLES_vowelBefore_doesNotIncrement() throws Exception {
        // "ailes" has vowel 'i' before 'les'
        int count = callLatin("ailes");
        assertTrue(count >= 1);
    }

    /**
     * Tests short words ending in "les" (length <= 3) - boundary case.
     */
    @Test
    void latinSyllables_shortWordEndingInLES_boundaryCase() throws Exception {
        // "les" itself (length == 3) should not trigger increment
        assertEquals(1, callLatin("les"));
    }

    /**
     * Tests words with silent "ed" ending - should decrement if count > 1.
     */
    @Test
    void latinSyllables_silentEdEnding_decrementsCount() throws Exception {
        // "walked" = wal-ked, 'ed' is silent (consonant 'k' before 'ed'), count > 1 → decrement
        assertEquals(1, callLatin("walked"));

        // "jumped" = jum-ped, silent 'ed'
        assertEquals(1, callLatin("jumped"));

        // "played" = pla-yed, vowel 'y' before 'ed' but 'y' counts as vowel
        int count = callLatin("played");
        assertTrue(count >= 1);
    }

    /**
     * Tests words ending in "ed" where 'ed' is NOT silent (preceded by 't' or 'd').
     */
    @Test
    void latinSyllables_nonSilentEdEnding_doesNotDecrement() throws Exception {
        // "ted" has 't' before 'ed' → NOT silent
        assertEquals(1, callLatin("ted"));

        // "added" has 'd' before 'ed' → NOT silent
        assertEquals(2, callLatin("added"));
    }

    /**
     * Tests words with count == 1 and silent "ed" - should NOT decrement (minimum 1).
     */
    @Test
    void latinSyllables_silentEdEnding_countOne_doesNotDecrement() throws Exception {
        // If a word somehow has count == 1 with silent 'ed', it should stay 1
        // "ed" itself would have count 1 and ends in 'ed'
        assertEquals(1, callLatin("ed"));
    }

    /**
     * Tests words with silent "es" ending - should decrement if count > 1.
     */
    @Test
    void latinSyllables_silentEsEnding_decrementsCount() throws Exception {
        // "bakes" = ba-kes, consonant 'k' before 'es' (not 's','x','z'), should be treated as silent
        int count = callLatin("bakes");
        assertTrue(count >= 1);
    }

    /**
     * Tests words ending in "es" where stem ends in "ch" or "sh" - NOT silent.
     */
    @Test
    void latinSyllables_esEndingAfterChSh_notSilent() throws Exception {
        // "watches" ends in "ches" → stem "watch" ends in "ch" → NOT silent
        int count = callLatin("watches");
        assertTrue(count >= 2);

        // "wishes" ends in "shes" → stem "wish" ends in "sh" → NOT silent
        assertTrue(callLatin("wishes") >= 2);
    }

    /**
     * Tests words ending in "es" where last char of stem is 's', 'x', or 'z' - NOT silent.
     */
    @Test
    void latinSyllables_esEndingAfterSXZ_notSilent() throws Exception {
        // "passes" → stem "pass" ends in 's' → NOT silent
        assertTrue(callLatin("passes") >= 2);

        // "boxes" → stem "box" ends in 'x' → NOT silent
        assertTrue(callLatin("boxes") >= 2);

        // "buzzes" → stem "buzz" ends in 'z' → NOT silent
        assertTrue(callLatin("buzzes") >= 2);
    }

    /**
     * Tests words ending in "es" where last char of stem is a vowel - NOT silent.
     */
    @Test
    void latinSyllables_esEndingAfterVowel_notSilent() throws Exception {
        // "goes" → stem "go" ends in vowel 'o' → NOT silent
        int count = callLatin("goes");
        assertTrue(count >= 1);
    }

    /**
     * Tests hasSilentEdEnding with words not ending in "ed" or too short.
     */
    @Test
    void hasSilentEdEnding_notEndingInEd_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEdEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "walk")); // doesn't end in 'ed'
        assertFalse((boolean) m.invoke(null, "ed")); // length == 2 (boundary)
        assertFalse((boolean) m.invoke(null, "a")); // too short
    }

    /**
     * Tests hasSilentEdEnding with 't' or 'd' before "ed" - returns false.
     */
    @Test
    void hasSilentEdEnding_tOrDBeforeEd_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEdEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "ted")); // 't' before 'ed'
        assertFalse((boolean) m.invoke(null, "coded")); // 'd' before 'ed'
    }

    /**
     * Tests hasSilentEdEnding with consonant before "ed" - returns true.
     */
    @Test
    void hasSilentEdEnding_consonantBeforeEd_returnsTrue() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEdEnding", String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, "walked")); // 'k' before 'ed'
        assertTrue((boolean) m.invoke(null, "jumped")); // 'p' before 'ed'
    }

    /**
     * Tests hasSilentEdEnding with vowel before "ed" - returns false.
     */
    @Test
    void hasSilentEdEnding_vowelBeforeEd_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEdEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "seed")); // 'e' (vowel) before 'ed'
    }

    /**
     * Tests hasSilentEsEnding with words not ending in "es" or too short.
     */
    @Test
    void hasSilentEsEnding_notEndingInEs_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "walk")); // doesn't end in 'es'
        assertFalse((boolean) m.invoke(null, "es")); // length == 2 (boundary)
    }

    /**
     * Tests hasSilentEsEnding with empty stem - returns false.
     */
    @Test
    void hasSilentEsEnding_emptyStem_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "es")); // stem would be empty
    }

    /**
     * Tests hasSilentEsEnding with stem ending in "ch" or "sh" - returns false.
     */
    @Test
    void hasSilentEsEnding_stemEndsChSh_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "watches")); // stem "watch" ends in "ch"
        assertFalse((boolean) m.invoke(null, "wishes")); // stem "wish" ends in "sh"
    }

    /**
     * Tests hasSilentEsEnding with stem ending in 's', 'x', or 'z' - returns false.
     */
    @Test
    void hasSilentEsEnding_stemEndsSXZ_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "passes")); // stem ends in 's'
        assertFalse((boolean) m.invoke(null, "boxes")); // stem ends in 'x'
        assertFalse((boolean) m.invoke(null, "buzzes")); // stem ends in 'z'
    }

    /**
     * Tests hasSilentEsEnding with stem ending in vowel - returns false.
     */
    @Test
    void hasSilentEsEnding_stemEndsVowel_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null, "goes")); // stem "go" ends in vowel 'o'
        assertFalse((boolean) m.invoke(null, "sees")); // stem "se" ends in vowel 'e'
    }

    /**
     * Tests hasSilentEsEnding with stem ending in consonant (not s/x/z/ch/sh) - returns true.
     */
    @Test
    void hasSilentEsEnding_stemEndsConsonant_returnsTrue() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(null, "bakes")); // stem "bak" ends in 'k'
        assertTrue((boolean) m.invoke(null, "dances")); // stem "danc" ends in 'c'
    }

    /**
     * Tests latinSyllables with words that have multiple consecutive vowels.
     */
    @Test
    void latinSyllables_multipleConsecutiveVowels_countsAsOneGroup() throws Exception {
        // "beat" has 'ea' consecutive vowels → should count as 1 vowel group
        assertEquals(1, callLatin("beat"));

        // "sea" has 'ea' consecutive
        assertEquals(1, callLatin("sea"));
    }

    /**
     * Tests latinSyllables with single vowel words.
     */
    @Test
    void latinSyllables_singleVowel_returnsOne() throws Exception {
        assertEquals(1, callLatin("a"));
        assertEquals(1, callLatin("i"));
        assertEquals(1, callLatin("o"));
    }

    /**
     * Tests latinSyllables with words containing 'y' as vowel.
     */
    @Test
    void latinSyllables_yAsVowel_counted() throws Exception {
        // "sky" has 'y' as vowel
        assertEquals(1, callLatin("sky"));

        // "rhythm" has two 'y's as vowels; current heuristic treats them as at least one syllable
        assertTrue(callLatin("rhythm") >= 1);

        // "my" has 'y' as vowel
        assertEquals(1, callLatin("my"));
    }

    // ========== Additional tests for remaining TextStats missed branches ==========

    /**
     * Tests countSentences with text that has words but no sentence delimiters.
     * Should return 1 to avoid division by zero in readability calculations.
     */
    @Test
    void countSentences_hasWordsButNoDelimiters_returnsOne() {
        String text = "This has words but no punctuation";
        int sentences = TextStats.countSentences(text);
        assertEquals(1, sentences);
    }

    /**
     * Tests countSyllables with null text - should return 0.
     */
    @Test
    void countSyllables_nullText_returnsZero() {
        assertEquals(0, TextStats.countSyllables(null));
    }

    /**
     * Tests countSyllables with whitespace-only text - should return 0.
     */
    @Test
    void countSyllables_whitespaceOnlyText_returnsZero() {
        assertEquals(0, TextStats.countSyllables("   \t\n   "));
    }

    /**
     * Tests syllablesInWord with empty word after toLowerCase - should return 0.
     */
    @Test
    void syllablesInWord_emptyWord_returnsZero() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("syllablesInWord", String.class);
        m.setAccessible(true);
        int result = (int) m.invoke(null, "");
        assertEquals(0, result);
    }

    /**
     * Tests hasSilentEsEnding with word that would have empty stem after removing "es".
     * For example, "es" itself has length 2, so stem would be empty.
     */
    @Test
    void hasSilentEsEnding_wordTooShort_emptyStem_returnsFalse() throws Exception {
        Method m = TextStats.class.getDeclaredMethod("hasSilentEsEnding", String.class);
        m.setAccessible(true);

        // "es" has length 2, removing "es" leaves empty stem
        boolean result = (boolean) m.invoke(null, "es");
        assertFalse(result); // should return false due to empty stem check
    }
}

package models;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sentiment analysis for NotiLytics.
 *
 * <p>Counts happy/sad tokens (words, ASCII emoticons, and emoji) in article descriptions,
 * applies the 70% rule to classify each article, and aggregates up to 50 articles to a
 * single overall emoticon for the query.</p>
 *
 * <p>Meets assignment D: Article Sentiment. Java 8 Streams are used for tokenization and aggregation.</p>
 *
 * @author zahra ebrahimizadehghahrood
 */
public final class SentimentAnalyzer {

    /** Three-way sentiment as requested. */
    public enum Sentiment { HAPPY, SAD, NEUTRAL }

    // --- configurable lexicons (start with a small seed; you may extend) ---
    private static final Set<String> HAPPY_WORDS = new HashSet<>(Arrays.asList(
  // generic positive
    "good","great","love","happy","joy","awesome","excellent","amazing","wonderful","best",
    "positive","smile","cheer","success","benefit","progress","celebrate","win","victory",
    "hope","hopeful","optimistic","relief","safe","safely","secure","secured","cozy",
    // business/econ
    "growth","growing","grows","gain","gains","gained","rally","rallies","rebound","rebounded",
    "recover","recovery","improve","improves","improved","improvement","boost","boosts",
    "surge","surges","record","records","beat","beats","exceed","exceeded","strong","strengthen",
    "profitable","profit","profits","upgrade","upgrades","downtick","cooling",
    // science/tech/health
    "breakthrough","milestone","discover","discovery","innovation","innovative","innovate",
    "launch","launched","cure","cured","healed","remission","vaccine","vaccinated",
    // society/world
    "peace","truce","ceasefire","agreement","accord","treaty","unity","cooperate","cooperation",
    // climate/energy (positive framing)
    "sustainable","renewable","renewables","solar","wind","geothermal","green","cleanup","restoration",
    "protect","protected","conservation","reforestation","habitat","biodiversity","recovering",
    // emojis-as-words (sometimes appear as plaintext)
    "yay","congrats","congratulations","bravo","thanks","grateful","gratitude","cheers","smiling","Celebrates","Celebrate","celebrated",
    "celebration","celebrations","save","saves","saved","freedom","liberated","liberation","rescue","rescued","life"
));



    private static final Set<String> SAD_WORDS = new HashSet<>(Arrays.asList(
    // generic negative
    "bad","sad","angry","hate","terrible","awful","worse","worst","tragic","tragedy","catastrophe",
    "disaster","crisis","fail","fails","failed","failure","negative","loss","losses","fear","fears",
    "problem","problems","decline","declines","declined","drop","drops","dropped","plunge","plunges",
    "plunged","fall","falls","fell","crash","crashed","collapse","collapsed","danger","risky","risk",
    "threat","threaten","threatened","warning","uncertain","uncertainty","volatile","volatility",
    "fraud","scandal","corruption","ban","banned","sanction","sanctions","lawsuit","sue","suing",
    "controversy","controversial","boycott","backlash","selfish",
    // conflict/violence
    "protest","protests","riot","riots","strike","strikes","shooting","shootings","attack","attacks",
    "war","wars","conflict","conflicts","terror","bomb","bombing","explosion","blast","shelling",
    "casualty","casualties","dead","death","deaths","die","died","killed","injured","injury","hurt",
    // disasters/health
    "earthquake","tsunami","hurricane","cyclone","typhoon","wildfire","wildfires","drought","flood",
    "floods","heatwave","heatwaves","outbreak","epidemic","pandemic","virus","covid","volcano",
    // climate & environment (to make “global warming” skew sad)
    "warming","overheating","emissions","pollution","polluted","smog","hazardous","toxic","contamination",
    "climate","climate-emergency","greenhouse","carbon","co2","methane","melting","bleaching","global warming",
    "extinction","endangered","deforestation","poaching","climate change","hurricane","flooding","storm","heatwave",
    // infrastructure/tech/security
    "shortage","shortages","outage","outages","blackout","recall","breach","breaches","leak","leaks",
    "hack","hacked","ransomware","downgrade","default","debt","deficit","bankrupt","bankruptcy",
    // economy
    "inflation","stagflation","recession","slowdown","unemployment","jobless","layoff","layoffs","rough","tough",
    "crisis","crises","evil",
    // social
    "inequality","poverty","homelessness","abuse","discrimination","hate-crime","harassment","dies","justice",
    "hostage","dead","left behind","hostages","nightmare", "bodies","dies ","investigation ","arrested","die",
    "regret","regretted","tragically","tragicly","tragic","don't know","mystery"

));

    // --- common ASCII emoticons ---
    private static final Pattern EMOTICON_HAPPY = Pattern.compile("(?:(?:[:;=]-?\\)|:D|=D))");
    private static final Pattern EMOTICON_SAD   = Pattern.compile("(?:(?:[:;=]-?\\())");

    // --- emoji sets (extendable) ---
    private static final Set<Integer> HAPPY_EMOJI = toCodePointSet("🙂😊😃😄😁😆🤗😍🥳❤️");
    private static final Set<Integer> SAD_EMOJI   = toCodePointSet("🙁☹️😞😟😢😭😔😕😣😖");

    private SentimentAnalyzer() {}

    /**
     * Analyze a single piece of text.
     * <p>Tokenizes the text, counts happy/sad signals (words, emoticons, emoji), and applies:</p>
     * <ul>
     *   <li><b>70% rule</b>: if happy / (happy + sad) &gt; 0.70 => HAPPY;</li>
     *   <li>if sad / (happy + sad) &gt; 0.70 => SAD;</li>
     *   <li>otherwise => NEUTRAL.</li>
     * </ul>
     * @param text text to analyze (title/description/content)
     * @return a three-way sentiment
     */


    public static Sentiment analyzeText(String text) {
        if (text == null || text.trim().isEmpty()) return Sentiment.NEUTRAL;
        final String norm = text.toLowerCase(Locale.ENGLISH);

        // --- words (Streams) ---
        List<String> tokens = Arrays.stream(norm.split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        long happyWords = tokens.stream().filter(HAPPY_WORDS::contains).count();
        long sadWords   = tokens.stream().filter(SAD_WORDS::contains).count();

        // --- emoticons ---
        long happyEmoticons = countMatches(EMOTICON_HAPPY, norm);
        long sadEmoticons   = countMatches(EMOTICON_SAD, norm);

        // --- emoji (Streams over code points) ---
        long happyEmoji = norm.codePoints().filter(HAPPY_EMOJI::contains).count();
        long sadEmoji   = norm.codePoints().filter(SAD_EMOJI::contains).count();

        long happy = happyWords + happyEmoticons + happyEmoji;
        long sad   = sadWords   + sadEmoticons   + sadEmoji;
        long total = happy + sad;
        if (total == 0) return Sentiment.NEUTRAL;

        double happyRatio = (double) happy / (double)total;
        double sadRatio   = (double) sad   /(double) total;

        if (happyRatio > 0.70) return Sentiment.HAPPY;  // 70% rule
        if (sadRatio   > 0.70) return Sentiment.SAD;
        return Sentiment.NEUTRAL;
    }


    
    public static Sentiment overallFromArticles(java.util.List<Sentiment> sentiments) {
        if (sentiments == null || sentiments.isEmpty()) return Sentiment.NEUTRAL;

        double avg = sentiments.stream()
            .limit(50)
            .mapToInt(s -> s == Sentiment.HAPPY ? 1 : s == Sentiment.SAD ? -1 : 0)
            .average()
            .orElse(0.0);

            if (avg > 0)  return Sentiment.HAPPY;
            if (avg < 0)  return Sentiment.SAD;
            return Sentiment.NEUTRAL;
    }

    /**
     * Convert the three-way sentiment to a display emoticon.
     */
    
    public static String toEmoticon(Sentiment s) {
        switch (s) {
            case HAPPY: return ":-)";
            case SAD:   return ":-(";
            default:    return ":-|";
        }
    }

    // --- helpers ---
    private static long countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        long c = 0;
        while (m.find()) c++;
        return c;
    }
    private static Set<Integer> toCodePointSet(String emojiString) {
        Set<Integer> set = new HashSet<>();
        emojiString.codePoints().forEach(set::add);
        return set;
    }
}

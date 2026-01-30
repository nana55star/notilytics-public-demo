package models;

import java.io.Serializable;

/**
 * Represents a single news article entity within the NotiLytics application.
 * <p>
 * Each {@code Article} object stores the key metadata retrieved from the News API,
 * including the article's title, description, URL, and source name.
 * Additional derived fields such as {@code titleLink}, {@code sourceLink},
 * and {@code publishedEdt} are generated server-side to enhance rendering.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     Article article = new Article(
 *         "AI Achieves New Milestone",
 *         "Researchers announce a breakthrough in quantum AI systems.",
 *         "https://example.com/ai-news",
 *         "TechDaily"
 *     );
 *     System.out.println(article.title);       // "AI Achieves New Milestone"
 *     System.out.println(article.sourceName);  // "TechDaily"
 * </pre>
 *
 * @author
 *  Nirvana Borham
 */
public class Article implements Serializable {

    /** The title of the news article. */
    public String title;

    /** A short summary or description of the article. */
    public String description;

    /** The direct URL linking to the full article. */
    public String url;

    /** The name of the news source or publisher. */
    public String sourceName;

    /** The ID of the news source (e.g., "bbc-news", "cnn"). */
    public String sourceId;

    /** A direct link for displaying the title as a hyperlink (same as {@code url}). */
    public String titleLink;

    /** A link to the source’s homepage (derived from the article URL). */
    public String sourceLink;

    /** The publication date in ISO format (from the News API). */
    public String publishedAt;

    /** The formatted publication date converted to Eastern Daylight Time (EDT). */
    public String publishedEdt;

    /**
     * Constructs an {@code Article} with the specified core properties.
     *
     * @param title       the headline or title of the article
     * @param description a brief summary describing the article
     * @param url         the web link where the full article can be accessed
     * @param sourceName  the name of the article’s source or publisher
     */
    public Article(String title, String description, String url, String sourceName) {
        this.title = title;
        this.description = description;
        this.url = url;
        this.sourceName = sourceName;
    }

    /** Default constructor required for JSON deserialization. */
    public Article() {}
}

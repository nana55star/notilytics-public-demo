package services;

/**
 * Represents a standardized response object used throughout the {@code services} package.
 * <p>
 * This class is a simple data container (immutable) that stores both the HTTP status code
 * and the response body as a JSON string. It is typically used by the
 * {@link services.NewsService} to return the results of asynchronous API calls in a
 * uniform structure.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     NewsResponse response = new NewsResponse(200, "{\"status\":\"ok\"}");
 *     System.out.println(response.status); // 200
 *     System.out.println(response.body);   // {"status":"ok"}
 * </pre>
 *
 * @author Nirvana Borham
 */
public class NewsResponse {
    /** The HTTP status code returned from the News API (e.g., 200, 400, 502). */
    public final int status;

    /** The JSON-formatted body of the response, as a string. */
    public final String body;

    /**
     * Constructs a {@code NewsResponse} object containing both the HTTP status
     * and its corresponding response body.
     *
     * @param status the HTTP status code of the response
     * @param body   the response body as a JSON string
     */
    public NewsResponse(int status, String body) {
        this.status = status;
        this.body = body;
    }
}


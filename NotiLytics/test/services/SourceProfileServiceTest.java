package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.ErrorInfo;
import models.ServiceResult;
import models.SourceDetails;
import models.SourceProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.libs.Json;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SourceProfileService}.
 * @author Alex Sutherland
 */
public class SourceProfileServiceTest {

    private NewsService mockNewsService;
    private SourceProfileService service;

    /*
    Set up the mock service for testing.
    **/
    @BeforeEach
    void setUp() {
        mockNewsService = mock(NewsService.class);
        service = new SourceProfileService(mockNewsService);
    }

    /*
    Test that source profiles with valid ID returns profile (these can sometimes be null)
    **/
    @Test
    void fetchSourceProfile_withValidSourceId_returnsProfile() {
        // Mock listSources response
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "bbc-news");
        sourceNode.put("name", "BBC News");
        sourceNode.put("description", "British news");
        sourceNode.put("url", "https://bbc.com");
        sourceNode.put("category", "general");
        sourceNode.put("language", "en");
        sourceNode.put("country", "gb");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Mock searchEverything response
        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 10);
        ArrayNode articlesArray = Json.newArray();
        ObjectNode articleNode = Json.newObject();
        articleNode.put("title", "Breaking News");
        articleNode.put("description", "Important event");
        articleNode.put("url", "https://bbc.com/article");
        articleNode.put("publishedAt", "2024-03-15T10:00:00Z");
        ObjectNode articleSource = Json.newObject();
        articleSource.put("id", "bbc-news");
        articleSource.put("name", "BBC News");
        articleNode.set("source", articleSource);
        articlesArray.add(articleNode);
        articlesResponse.set("articles", articlesArray);

        when(mockNewsService.searchEverything(
                isNull(), eq("bbc-news"), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("bbc-news", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify
        assertTrue(profileResult.isSuccess());
        assertEquals(200, profileResult.getStatus());
        assertTrue(profileResult.getData().isPresent());
        SourceProfile profile = profileResult.getData().get();
        assertEquals("bbc-news", profile.getSource().id);
        assertEquals("BBC News", profile.getSource().name);
        assertEquals(1, profile.getArticles().size());
        assertEquals("Breaking News", profile.getArticles().get(0).title);
        assertEquals(10, profile.getTotalResults());
    }

    /*
    Test that source profiles not found, meaning both id and name fails (ie. NewsAPI doesnt have info) returns 404 response
    **/
    @Test
    void fetchSourceProfile_sourceNotFound_returns404() {
        // Mock listSources response with no matching source
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode otherSource = Json.newObject();
        otherSource.put("id", "cnn");
        otherSource.put("name", "CNN");
        sourcesArray.add(otherSource);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Execute
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("nonexistent-source", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify
        assertFalse(profileResult.isSuccess());
        assertEquals(404, profileResult.getStatus());
        assertTrue(profileResult.getError().isPresent());
        assertEquals("Source not found", profileResult.getError().get().getMessage());
    }

    /*
    Test that list sources properly surfaces error if fails
    **/
    @Test
    void fetchSourceProfile_listSourcesError_returnsError() {
        // Mock listSources failure
        ObjectNode errorResponse = Json.newObject();
        errorResponse.put("status", "error");
        errorResponse.put("message", "API error");

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(500, errorResponse.toString())
                ));

        // Execute
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("bbc-news", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify
        assertFalse(profileResult.isSuccess());
        assertEquals(500, profileResult.getStatus());
        assertTrue(profileResult.getError().isPresent());
        assertTrue(profileResult.getError().get().getMessage().contains("Failed to load the list of sources"));
    }

    /*
    Test that search articles error is surfaced if fails
    **/
    @Test
    void fetchSourceProfile_searchArticlesError_returnsError() {
        // Mock listSources success
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "bbc-news");
        sourceNode.put("name", "BBC News");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Mock searchEverything failure
        ObjectNode errorResponse = Json.newObject();
        errorResponse.put("status", "error");

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(500, errorResponse.toString())
        ));

        // Execute
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("bbc-news", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify
        assertFalse(profileResult.isSuccess());
        assertEquals(500, profileResult.getStatus());
        assertTrue(profileResult.getError().isPresent());
        assertTrue(profileResult.getError().get().getMessage().contains("Failed to load recent articles"));
    }

    /*
    Test successful fall back on Id search to use name
    **/
    @Test
    void fetchSourceProfile_findsBySourceName_whenIdNotMatched() {
        // Mock listSources response with source that has different casing in name
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "bbc-news");
        sourceNode.put("name", "BBC News");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Mock searchEverything response
        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 5);
        articlesResponse.set("articles", Json.newArray());

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute - search by name instead of ID
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("bbc news", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify - should find by name (case-insensitive)
        assertTrue(profileResult.isSuccess());
        assertTrue(profileResult.getData().isPresent());
        assertEquals("bbc-news", profileResult.getData().get().getSource().id);
    }

    /*
    Test that source profile is returned truncated to the appropriate page size
    **/
    @Test
    void fetchSourceProfile_withPageSize_limitsResults() {
        // Mock listSources
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test-source");
        sourceNode.put("name", "Test Source");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Mock searchEverything with 5 articles
        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 100);
        ArrayNode articlesArray = Json.newArray();
        for (int i = 0; i < 5; i++) {
            ObjectNode article = Json.newObject();
            article.put("title", "Article " + i);
            article.put("publishedAt", "2024-03-1" + i + "T10:00:00Z");
            ObjectNode source = Json.newObject();
            source.put("id", "test-source");
            source.put("name", "Test Source");
            article.set("source", source);
            articlesArray.add(article);
        }
        articlesResponse.set("articles", articlesArray);

        // "all" language gets normalized to null
        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), isNull(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute with pageSize=5
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("test-source", "all", "", "", "publishedAt", "5");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify
        assertTrue(profileResult.isSuccess());
        assertTrue(profileResult.getData().isPresent());
        assertEquals(5, profileResult.getData().get().getArticles().size());
        assertEquals(100, profileResult.getData().get().getTotalResults());
    }

    /*
    Test invalidPageSize falls back to default page size (10)
    **/
    @Test
    void fetchSourceProfile_invalidPageSize_usesDefault() {
        // This tests the sanitization of pageSize
        // Mock responses
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 0);
        articlesResponse.set("articles", Json.newArray());

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute with invalid pageSize
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("test", "en", "", "", "publishedAt", "invalid");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Should still succeed (uses default pageSize of 10)
        assertTrue(profileResult.isSuccess());
        verify(mockNewsService).searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), eq("publishedAt"), eq("10")
        );
    }

    /*
    Test sourceprofile with blank source id returns 404
    **/
    @Test
    void fetchSourceProfile_withBlankSourceId_returns404() {
        // Mock listSources (will still be called even with blank sourceId)
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Execute with blank sourceId
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Should return 404 because sourceId is blank (findSourceDetails returns null)
        assertFalse(profileResult.isSuccess());
        assertEquals(404, profileResult.getStatus());
    }

    /*
    Test source profile returne 404 on null source id
    **/
    @Test
    void fetchSourceProfile_withNullSourceId_returns404() {
        // Mock listSources (will still be called even with null sourceId)
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Execute with null sourceId
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile(null, "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Should return 404 because sourceId is null (findSourceDetails returns null)
        assertFalse(profileResult.isSuccess());
        assertEquals(404, profileResult.getStatus());
    }

    /*
    Test that source profile reduces large page size requests to the max of 100
    **/
    @Test
    void fetchSourceProfile_withVeryLargePageSize_clamps() {
        // Mock listSources
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 0);
        articlesResponse.set("articles", Json.newArray());

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute with pageSize > 100 (should be clamped to 100)
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("test", "en", "", "", "publishedAt", "200");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Should succeed and clamp to 100
        assertTrue(profileResult.isSuccess());
        verify(mockNewsService).searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), eq("publishedAt"), eq("100")
        );
    }

    /*
    Test that source profile falls back to floor value on negative pagesize inputs
    **/
    @Test
    void fetchSourceProfile_withNegativePageSize_usesMinimum() {
        // Mock responses
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 0);
        articlesResponse.set("articles", Json.newArray());

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute with negative pageSize (should be clamped to 1)
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("test", "en", "", "", "publishedAt", "-5");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Should succeed and clamp to 1
        assertTrue(profileResult.isSuccess());
        verify(mockNewsService).searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), eq("publishedAt"), eq("1")
        );
    }

    /*
    Test that source profile with empty sort by uses default (published at)
    **/
    @Test
    void fetchSourceProfile_withBlankSortBy_usesDefault() {
        // Mock responses
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 0);
        articlesResponse.set("articles", Json.newArray());

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute with blank sortBy (should default to publishedAt)
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("test", "en", "", "", "", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Should succeed with default sortBy
        assertTrue(profileResult.isSuccess());
        verify(mockNewsService).searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), eq("publishedAt"), eq("10")
        );
    }

    /*
    Test safeParse with invalid JSON returns fallback object with raw and parseError
    */
    @Test
    void safeParse_withInvalidJson_returnsFallbackObject() throws Exception {
        // Use reflection to access private method
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("safeParse", String.class);
        method.setAccessible(true);

        String invalidJson = "{invalid json}";
        Object result = method.invoke(service, invalidJson);

        // Verify it's a JsonNode with raw and parseError fields
        assertNotNull(result);
        assertTrue(result instanceof com.fasterxml.jackson.databind.JsonNode);
        com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) result;
        assertTrue(node.has("raw"));
        assertTrue(node.has("parseError"));
        assertEquals(invalidJson, node.get("raw").asText());
    }

    /*
    Test safeParse with null returns empty object
    */
    @Test
    void safeParse_withNull_returnsEmptyObject() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("safeParse", String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, (String) null);

        assertNotNull(result);
        assertTrue(result instanceof com.fasterxml.jackson.databind.JsonNode);
    }

    /*
    Test safeParse with blank string returns empty object
    */
    @Test
    void safeParse_withBlankString_returnsEmptyObject() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("safeParse", String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, "   ");

        assertNotNull(result);
        assertTrue(result instanceof com.fasterxml.jackson.databind.JsonNode);
    }

    /*
    Test parsePublishedAt with null returns Long.MIN_VALUE
    */
    @Test
    void parsePublishedAt_withNull_returnsMinValue() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("parsePublishedAt", String.class);
        method.setAccessible(true);

        long result = (long) method.invoke(service, (String) null);

        assertEquals(Long.MIN_VALUE, result);
    }

    /*
    Test parsePublishedAt with blank string returns Long.MIN_VALUE
    */
    @Test
    void parsePublishedAt_withBlankString_returnsMinValue() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("parsePublishedAt", String.class);
        method.setAccessible(true);

        long result = (long) method.invoke(service, "  ");

        assertEquals(Long.MIN_VALUE, result);
    }

    /*
    Test parsePublishedAt with invalid format returns Long.MIN_VALUE
    */
    @Test
    void parsePublishedAt_withInvalidFormat_returnsMinValue() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("parsePublishedAt", String.class);
        method.setAccessible(true);

        long result = (long) method.invoke(service, "invalid-date");

        assertEquals(Long.MIN_VALUE, result);
    }

    /*
    Test parsePublishedAt with valid ISO8601 date returns epoch milliseconds
    */
    @Test
    void parsePublishedAt_withValidDate_returnsEpochMillis() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("parsePublishedAt", String.class);
        method.setAccessible(true);

        long result = (long) method.invoke(service, "2024-03-15T10:00:00Z");

        assertTrue(result > 0);
        assertTrue(result != Long.MIN_VALUE);
    }

    /*
    Test safePageSize with null returns fallback
    */
    @Test
    void safePageSize_withNull_returnsFallback() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("safePageSize", String.class, int.class);
        method.setAccessible(true);

        int result = (int) method.invoke(service, null, 20);

        assertEquals(20, result);
    }

    /*
    Test safePageSize with blank input returns fallback
    */
    @Test
    void safePageSize_withBlankString_returnsFallback() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("safePageSize", String.class, int.class);
        method.setAccessible(true);

        int result = (int) method.invoke(service, "   ", 15);

        assertEquals(15, result);
    }

    /*
    Test safePageSize with zero returns 1 (minimum)
    */
    @Test
    void safePageSize_withZero_returnsMinimum() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("safePageSize", String.class, int.class);
        method.setAccessible(true);

        int result = (int) method.invoke(service, "0", 10);

        assertEquals(1, result);
    }

    /*
    Test normalizeLanguage with "all" returns null
    */
    @Test
    void normalizeLanguage_withAll_returnsNull() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("normalizeLanguage", String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, "all");

        assertNull(result);
    }

    /*
    Test normalizeLanguage with "ALL" (uppercase) returns null
    */
    @Test
    void normalizeLanguage_withALL_returnsNull() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("normalizeLanguage", String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, "ALL");

        assertNull(result);
    }

    /*
    Test normalizeLanguage with specific language returns same value
    */
    @Test
    void normalizeLanguage_withSpecificLanguage_returnsSameValue() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("normalizeLanguage", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service, "en");

        assertEquals("en", result);
    }

    /*
    Test normalizeLanguage with null returns null
    */
    @Test
    void normalizeLanguage_withNull_returnsNull() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("normalizeLanguage", String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, (String) null);

        assertNull(result);
    }

    /*
    Test extractArray with non-existent field returns empty array
    */
    @Test
    void extractArray_withNonExistentField_returnsEmptyArray() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("extractArray",
            com.fasterxml.jackson.databind.JsonNode.class, String.class);
        method.setAccessible(true);

        ObjectNode parent = Json.newObject();
        parent.put("otherField", "value");

        Object result = method.invoke(service, parent, "nonExistent");

        assertNotNull(result);
        assertTrue(result instanceof ArrayNode);
        assertEquals(0, ((ArrayNode) result).size());
    }

    /*
    Test extractArray with null parent returns empty array
    */
    @Test
    void extractArray_withNullParent_returnsEmptyArray() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("extractArray",
            com.fasterxml.jackson.databind.JsonNode.class, String.class);
        method.setAccessible(true);

        Object result = method.invoke(service, null, "field");

        assertNotNull(result);
        assertTrue(result instanceof ArrayNode);
        assertEquals(0, ((ArrayNode) result).size());
    }

    /*
    Test extractArray with non-array field returns empty array
    */
    @Test
    void extractArray_withNonArrayField_returnsEmptyArray() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("extractArray",
            com.fasterxml.jackson.databind.JsonNode.class, String.class);
        method.setAccessible(true);

        ObjectNode parent = Json.newObject();
        parent.put("field", "not an array");

        Object result = method.invoke(service, parent, "field");

        assertNotNull(result);
        assertTrue(result instanceof ArrayNode);
        assertEquals(0, ((ArrayNode) result).size());
    }

    /*
    Test applyArticleOrdering with non-publishedAt sortBy returns unsorted
    */
    @Test
    void applyArticleOrdering_withNonPublishedAtSort_returnsUnsorted() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("applyArticleOrdering",
            java.util.List.class, String.class, int.class);
        method.setAccessible(true);

        java.util.List<models.ArticleSummary> articles = new java.util.ArrayList<>();
        articles.add(new models.ArticleSummary("Title 1", null, null, "2024-03-01T00:00:00Z", null, null));
        articles.add(new models.ArticleSummary("Title 2", null, null, "2024-01-01T00:00:00Z", null, null));
        articles.add(new models.ArticleSummary("Title 3", null, null, "2024-02-01T00:00:00Z", null, null));

        @SuppressWarnings("unchecked")
        java.util.List<models.ArticleSummary> result = (java.util.List<models.ArticleSummary>) method.invoke(service, articles, "relevancy", 10);

        // Should maintain original order when not sorting by publishedAt
        assertEquals(3, result.size());
        assertEquals("Title 1", result.get(0).title);
    }

    /*
    Test applyArticleOrdering with limit less than list size
    */
    @Test
    void applyArticleOrdering_withSmallLimit_truncatesResults() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("applyArticleOrdering",
            java.util.List.class, String.class, int.class);
        method.setAccessible(true);

        java.util.List<models.ArticleSummary> articles = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            articles.add(new models.ArticleSummary("Title " + i, null, null, "2024-03-0" + i + "T00:00:00Z", null, null));
        }

        @SuppressWarnings("unchecked")
        java.util.List<models.ArticleSummary> result = (java.util.List<models.ArticleSummary>) method.invoke(service, articles, "publishedAt", 5);

        assertEquals(5, result.size());
    }

    /*
    Test findSourceDetails with case-insensitive ID match
    */
    @Test
    void findSourceDetails_withMixedCaseId_findsMatch() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("findSourceDetails",
            ArrayNode.class, String.class);
        method.setAccessible(true);

        ArrayNode sources = Json.newArray();
        ObjectNode source = Json.newObject();
        source.put("id", "BBC-News");
        source.put("name", "BBC News");
        sources.add(source);

        Object result = method.invoke(service, sources, "bbc-news");

        assertNotNull(result);
        assertTrue(result instanceof models.SourceDetails);
        assertEquals("BBC-News", ((models.SourceDetails) result).id);
    }

    /*
    Test findSourceDetails with empty ID returns null
    */
    @Test
    void findSourceDetails_withEmptySourceIdOrName_returnsNull() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("findSourceDetails",
            ArrayNode.class, String.class);
        method.setAccessible(true);

        ArrayNode sources = Json.newArray();
        ObjectNode source = Json.newObject();
        source.put("id", "test");
        source.put("name", "Test");
        sources.add(source);

        Object result = method.invoke(service, sources, "");

        assertNull(result);
    }

    /*
    Test findSourceDetails falls back to case-insensitive name matching when IDs are blank
    */
    @Test
    void findSourceDetails_withNameMatch_returnsSource() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("findSourceDetails",
            ArrayNode.class, String.class);
        method.setAccessible(true);

        ArrayNode sources = Json.newArray();
        ObjectNode source = Json.newObject();
        source.put("id", "");
        source.put("name", "Global Times");
        sources.add(source);

        Object result = method.invoke(service, sources, "global times");

        assertNotNull(result);
        assertTrue(result instanceof models.SourceDetails);
        assertEquals("Global Times", ((models.SourceDetails) result).name);
    }

    /*
    Test findSourceDetails returns match by name even when preceding entries have non-matching IDs.
    */
    @Test
    void findSourceDetails_nameMatchAfterIdMismatch_returnsSource() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("findSourceDetails",
            ArrayNode.class, String.class);
        method.setAccessible(true);

        ArrayNode sources = Json.newArray();
        ObjectNode first = Json.newObject();
        first.put("id", "other-id");
        first.put("name", "Other Source");
        sources.add(first);

        ObjectNode second = Json.newObject();
        second.put("id", "alias-id");
        second.put("name", "Case Match Source");
        sources.add(second);

        Object result = method.invoke(service, sources, "case match source");

        assertNotNull(result);
        assertTrue(result instanceof models.SourceDetails);
        assertEquals("Case Match Source", ((models.SourceDetails) result).name);
    }

    /*
    Test findSourceDetails returns null when neither ID nor name matches the target.
    */
    @Test
    void findSourceDetails_withoutMatch_returnsNull() throws Exception {
        java.lang.reflect.Method method = SourceProfileService.class.getDeclaredMethod("findSourceDetails",
            ArrayNode.class, String.class);
        method.setAccessible(true);

        ArrayNode sources = Json.newArray();
        ObjectNode source = Json.newObject();
        source.put("id", "unrelated");
        source.put("name", "Unrelated Source");
        sources.add(source);

        Object result = method.invoke(service, sources, "missing");

        assertNull(result);
    }

    /*
    Test that articles are sorted correctly before displaying to user
    **/
    @Test
    void fetchSourceProfile_articlesWithSorting_ordersCorrectly() {
        // Mock listSources
        ObjectNode sourcesResponse = Json.newObject();
        sourcesResponse.put("status", "ok");
        ArrayNode sourcesArray = Json.newArray();
        ObjectNode sourceNode = Json.newObject();
        sourceNode.put("id", "test");
        sourceNode.put("name", "Test");
        sourcesArray.add(sourceNode);
        sourcesResponse.set("sources", sourcesArray);

        when(mockNewsService.listSources(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NewsResponse(200, sourcesResponse.toString())
                ));

        // Create articles with different dates
        ObjectNode articlesResponse = Json.newObject();
        articlesResponse.put("status", "ok");
        articlesResponse.put("totalResults", 3);
        ArrayNode articlesArray = Json.newArray();

        // Add articles in non-chronological order
        ObjectNode article1 = Json.newObject();
        article1.put("title", "Oldest");
        article1.put("publishedAt", "2024-01-01T10:00:00Z");
        ObjectNode source1 = Json.newObject();
        source1.put("id", "test");
        source1.put("name", "Test");
        article1.set("source", source1);

        ObjectNode article2 = Json.newObject();
        article2.put("title", "Newest");
        article2.put("publishedAt", "2024-03-01T10:00:00Z");
        ObjectNode source2 = Json.newObject();
        source2.put("id", "test");
        source2.put("name", "Test");
        article2.set("source", source2);

        ObjectNode article3 = Json.newObject();
        article3.put("title", "Middle");
        article3.put("publishedAt", "2024-02-01T10:00:00Z");
        ObjectNode source3 = Json.newObject();
        source3.put("id", "test");
        source3.put("name", "Test");
        article3.set("source", source3);

        articlesArray.add(article1);
        articlesArray.add(article2);
        articlesArray.add(article3);
        articlesResponse.set("articles", articlesArray);

        when(mockNewsService.searchEverything(
                isNull(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new NewsResponse(200, articlesResponse.toString())
        ));

        // Execute
        CompletionStage<ServiceResult<SourceProfile>> result =
                service.fetchSourceProfile("test", "en", "", "", "publishedAt", "10");

        ServiceResult<SourceProfile> profileResult = result.toCompletableFuture().join();

        // Verify sorting - should be newest first
        assertTrue(profileResult.isSuccess());
        assertTrue(profileResult.getData().isPresent());
        assertEquals(3, profileResult.getData().get().getArticles().size());
        assertEquals("Newest", profileResult.getData().get().getArticles().get(0).title);
        assertEquals("Middle", profileResult.getData().get().getArticles().get(1).title);
        assertEquals("Oldest", profileResult.getData().get().getArticles().get(2).title);
    }

    /**
     * Test findSourceDetails when name is blank.
     * Should skip the blank name comparison.
     */
    @Test
    void findSourceDetails_withBlankName_skipsBlankComparison() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode sourcesArray = mapper.createArrayNode();

        // Add source with blank name
        ObjectNode source1 = mapper.createObjectNode();
        source1.put("id", "other-id");
        source1.put("name", "  "); // blank name
        sourcesArray.add(source1);

        // Add source with valid name
        ObjectNode source2 = mapper.createObjectNode();
        source2.put("id", "test-id");
        source2.put("name", "Test Name");
        sourcesArray.add(source2);

        Method m = SourceProfileService.class.getDeclaredMethod("findSourceDetails", ArrayNode.class, String.class);
        m.setAccessible(true);

        SourceDetails result = (SourceDetails) m.invoke(service, sourcesArray, "test name");

        // Should find the second source by name match (not the blank one)
        assertNotNull(result);
        assertEquals("test-id", result.id);
    }
}

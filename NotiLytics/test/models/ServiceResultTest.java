package models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import play.libs.Json;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ServiceResult}.
 * @author Alex Sutherland
 */
public class ServiceResultTest {

    /*
    Test that the ServiceResult properly acknowledges success
    **/
    @Test
    void success_createsSuccessfulResult() {
        String data = "test data";
        ServiceResult<String> result = ServiceResult.success(200, data);

        assertTrue(result.isSuccess());
        assertEquals(200, result.getStatus());
        assertTrue(result.getData().isPresent());
        assertEquals("test data", result.getData().get());
        assertFalse(result.getError().isPresent());
    }

    /*
    Test that ServiceResult properly acknowledges failure
    **/
    @Test
    void failure_createsFailedResult() {
        ErrorInfo error = new ErrorInfo("Something went wrong", null);
        ServiceResult<String> result = ServiceResult.failure(500, error);

        assertFalse(result.isSuccess());
        assertEquals(500, result.getStatus());
        assertFalse(result.getData().isPresent());
        assertTrue(result.getError().isPresent());
        assertEquals("Something went wrong", result.getError().get().getMessage());
    }

    /*
    Test that Result is captured and saved
    **/
    @Test
    void success_withComplexObject_storesData() {
        ObjectNode data = Json.newObject();
        data.put("key", "value");
        data.put("count", 42);

        ServiceResult<ObjectNode> result = ServiceResult.success(201, data);

        assertTrue(result.isSuccess());
        assertEquals(201, result.getStatus());
        Optional<ObjectNode> retrievedData = result.getData();
        assertTrue(retrievedData.isPresent());
        assertEquals("value", retrievedData.get().get("key").asText());
        assertEquals(42, retrievedData.get().get("count").asInt());
    }

    /*
    Test that error is stored in ServiceResult.
    **/
    @Test
    void failure_withDetails_storesErrorInfo() {
        ObjectNode details = Json.newObject();
        details.put("field", "email");
        details.put("reason", "invalid format");

        ErrorInfo error = new ErrorInfo("Validation error", details);
        ServiceResult<String> result = ServiceResult.failure(400, error);

        assertFalse(result.isSuccess());
        assertEquals(400, result.getStatus());
        assertTrue(result.getError().isPresent());
        ErrorInfo retrievedError = result.getError().get();
        assertEquals("Validation error", retrievedError.getMessage());
        assertEquals("email", retrievedError.getDetails().get("field").asText());
    }

    /*
    Test that success never returns null (some data is present)
    **/
    @Test
    void success_dataIsNeverNull_whenSuccess() {
        ServiceResult<String> result = ServiceResult.success(200, "data");
        assertTrue(result.getData().isPresent());
    }

    /*
    Test that error is never null when a failure is hit
    **/
    @Test
    void failure_errorIsNeverNull_whenFailure() {
        ErrorInfo error = new ErrorInfo("Error", null);
        ServiceResult<String> result = ServiceResult.failure(500, error);
        assertTrue(result.getError().isPresent());
    }

    /*
    Test that True only returned on success
    **/
    @Test
    void isSuccess_returnsTrueOnlyForSuccess() {
        ServiceResult<String> success = ServiceResult.success(200, "ok");
        ServiceResult<String> failure = ServiceResult.failure(400, new ErrorInfo("bad", null));

        assertTrue(success.isSuccess());
        assertFalse(failure.isSuccess());
    }

    /*
    Test that proper status code is returned based on success or failure
    **/
    @Test
    void getStatus_returnsCorrectStatusCode() {
        ServiceResult<String> result1 = ServiceResult.success(201, "created");
        ServiceResult<String> result2 = ServiceResult.failure(404, new ErrorInfo("not found", null));

        assertEquals(201, result1.getStatus());
        assertEquals(404, result2.getStatus());
    }

    /*
    Test that success with null is handled correctly
    **/
    @Test
    void success_withNullData_handledAsEmpty() {
        ServiceResult<String> result = ServiceResult.success(204, null);

        assertTrue(result.isSuccess());
        assertEquals(204, result.getStatus());
        assertFalse(result.getData().isPresent());
    }

    /*
    Test failed data request returns a 500 and no data
    **/
    @Test
    void getData_returnsEmptyOnFailure() {
        ErrorInfo error = new ErrorInfo("Failed", null);
        ServiceResult<String> result = ServiceResult.failure(500, error);

        assertFalse(result.getData().isPresent());
    }

    /*
    Test that error field is empty on a success (200)
    **/
    @Test
    void getError_returnsEmptyOnSuccess() {
        ServiceResult<String> result = ServiceResult.success(200, "success");

        assertFalse(result.getError().isPresent());
    }
}

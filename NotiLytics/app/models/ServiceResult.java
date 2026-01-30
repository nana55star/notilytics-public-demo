package models;

import java.util.Optional;

/**
 * Generic wrapper capturing either a successful payload or an application error.
 */
public class ServiceResult<T> {
    private final boolean success;
    private final int status;
    private final T data;
    private final ErrorInfo error;

    private ServiceResult(boolean success, int status, T data, ErrorInfo error) {
        this.success = success;
        this.status = status;
        this.data = data;
        this.error = error;
    }

    public static <T> ServiceResult<T> success(int status, T data) {
        return new ServiceResult<>(true, status, data, null);
    }

    public static <T> ServiceResult<T> failure(int status, ErrorInfo error) {
        return new ServiceResult<>(false, status, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getStatus() {
        return status;
    }

    public Optional<T> getData() {
        return Optional.ofNullable(data);
    }

    public Optional<ErrorInfo> getError() {
        return Optional.ofNullable(error);
    }
}

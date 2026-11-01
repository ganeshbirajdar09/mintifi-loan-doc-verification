package com.loanverify.api;

/** A business error with an HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", what + " not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }
}

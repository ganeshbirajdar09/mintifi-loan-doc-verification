package com.loanverify.api.dto;

import java.util.List;

public record ErrorResponse(int status, String code, String message, List<FieldError> fieldErrors) {

    public ErrorResponse(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public record FieldError(String field, String message) {
    }
}

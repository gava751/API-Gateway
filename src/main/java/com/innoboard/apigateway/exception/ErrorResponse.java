package com.innoboard.apigateway.exception;

public record ErrorResponse(
        int status,
        String error,
        String message
) {
}

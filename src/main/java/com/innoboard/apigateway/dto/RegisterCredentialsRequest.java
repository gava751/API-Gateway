package com.innoboard.apigateway.dto;

public record RegisterCredentialsRequest(
        Long userId,
        String login,
        String password,
        UserRole role
) {
}

package com.innoboard.apigateway.dto;

public record UserCreatedResponse(
        Long id,
        String name,
        String surname,
        String birthDate,
        String email,
        Boolean active
) {
}

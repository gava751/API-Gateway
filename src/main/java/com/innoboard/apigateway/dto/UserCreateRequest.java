package com.innoboard.apigateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

public record UserCreateRequest(
        @NotBlank(message = "Name must not be blank")
        String name,

        @NotBlank(message = "Surname must not be blank")
        String surname,

        @NotNull(message = "Birth date is required")
        @Past(message = "Birth date must be in the past")
        LocalDate birthDate,

        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be valid")
        String email
) {
}

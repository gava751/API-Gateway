package com.innoboard.apigateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RegisterViaGatewayRequest(
        @NotNull(message = "User data must not be null")
        @Valid
        UserCreateRequest user,

        @NotNull(message = "Credentials must not be null")
        @Valid
        CredentialsDto credentials
) {
}

package com.innoboard.apigateway.service;

import com.innoboard.apigateway.dto.CredentialsDto;
import com.innoboard.apigateway.dto.RegisterCredentialsRequest;
import com.innoboard.apigateway.dto.RegisterViaGatewayRequest;
import com.innoboard.apigateway.dto.RegistrationResponse;
import com.innoboard.apigateway.dto.UserCreatedResponse;
import com.innoboard.apigateway.dto.UserCreateRequest;
import com.innoboard.apigateway.exception.DownstreamServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationOrchestrationService {

    private final WebClient userServiceWebClient;
    private final WebClient authServiceWebClient;

    public Mono<RegistrationResponse> register(RegisterViaGatewayRequest request) {
        return createUser(request.user())
                .flatMap(userId -> registerCredentials(userId, request.credentials())
                        .onErrorResume(ex -> rollbackUser(userId, ex)))
                .map(id -> new RegistrationResponse(id, "User registered successfully"));
    }

    private Mono<Long> createUser(UserCreateRequest user) {
        var payload = new UserPayload(user.name(), user.surname(), user.birthDate().toString(),
                user.email(), true);
        return userServiceWebClient.post()
                .uri("/users")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(UserCreatedResponse.class)
                .map(UserCreatedResponse::id)
                .onErrorMap(WebClientResponseException.class, ex ->
                        new DownstreamServiceException("User Service failed: " + ex.getResponseBodyAsString(), ex))
                .onErrorMap(e -> !(e instanceof DownstreamServiceException),
                        ex -> new DownstreamServiceException("User Service is unavailable", ex));
    }

    private Mono<Long> registerCredentials(Long userId, CredentialsDto credentials) {
        return authServiceWebClient.post()
                .uri("/auth/register")
                .bodyValue(new RegisterCredentialsRequest(userId, credentials.login(),
                        credentials.password(), credentials.role()))
                .retrieve()
                .toBodilessEntity()
                .thenReturn(userId)
                .onErrorMap(WebClientResponseException.class, ex ->
                        new DownstreamServiceException("Authentication Service failed: " + ex.getResponseBodyAsString(), ex))
                .onErrorMap(e -> !(e instanceof DownstreamServiceException),
                        ex -> new DownstreamServiceException("Authentication Service is unavailable", ex));
    }

    private Mono<Long> rollbackUser(Long userId, Throwable cause) {
        log.error("Registration failed for userId={}, rolling back user creation", userId, cause);
        return userServiceWebClient.delete()
                .uri("/users/{id}", userId)
                .retrieve()
                .toBodilessEntity()
                .doOnError(rollbackEx ->
                        log.error("Rollback failed for userId={} — manual cleanup required", userId, rollbackEx))
                .onErrorResume(rollbackEx -> Mono.empty())
                .then(Mono.error(cause));
    }

    /**
     * Outbound payload for user-service POST /users.
     */
    record UserPayload(String name, String surname, String birthDate, String email, Boolean active) {
    }
}

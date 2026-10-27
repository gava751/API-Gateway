package com.innoboard.apigateway.service;

import com.innoboard.apigateway.config.ServicesProperties;
import com.innoboard.apigateway.dto.CredentialsDto;
import com.innoboard.apigateway.dto.RegisterViaGatewayRequest;
import com.innoboard.apigateway.dto.UserCreateRequest;
import com.innoboard.apigateway.dto.UserRole;
import com.innoboard.apigateway.exception.DownstreamServiceException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationOrchestrationServiceTest {

    private MockWebServer userService;
    private MockWebServer authService;
    private RegistrationOrchestrationService service;

    @BeforeEach
    void setUp() throws IOException {
        userService = new MockWebServer();
        authService = new MockWebServer();
        userService.start();
        authService.start();
        ServicesProperties properties = new ServicesProperties(
                authService.url("/").toString(),
                userService.url("/").toString());
        service = new RegistrationOrchestrationService(
                WebClient.builder().baseUrl(properties.userUrl()).build(),
                WebClient.builder().baseUrl(properties.authUrl()).build());
    }

    @AfterEach
    void tearDown() throws IOException {
        userService.shutdown();
        authService.shutdown();
    }

    private RegisterViaGatewayRequest request() {
        return new RegisterViaGatewayRequest(
                new UserCreateRequest("Ivan", "Ivanov", LocalDate.of(1990, 1, 1), "ivan@example.com"),
                new CredentialsDto("ivan", "password123", UserRole.USER));
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(body);
    }

    @Test
    void registersUserThenCredentials() throws Exception {
        userService.enqueue(jsonResponse(201, "{\"id\":5}"));
        authService.enqueue(jsonResponse(201, "{\"login\":\"ivan\"}"));

        StepVerifier.create(service.register(request()))
                .assertNext(response -> {
                    assertThat(response.userId()).isEqualTo(5L);
                    assertThat(response.message()).isEqualTo("User registered successfully");
                })
                .verifyComplete();

        RecordedRequest createUser = userService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(createUser).isNotNull();
        assertThat(createUser.getMethod()).isEqualTo("POST");
        assertThat(createUser.getPath()).isEqualTo("/users");
        String createBody = createUser.getBody().readUtf8();
        assertThat(createBody).contains("\"name\":\"Ivan\"");
        assertThat(createBody).contains("\"surname\":\"Ivanov\"");
        assertThat(createBody).contains("\"email\":\"ivan@example.com\"");
        assertThat(createBody).contains("\"active\":true");

        RecordedRequest registerCredentials = authService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(registerCredentials).isNotNull();
        assertThat(registerCredentials.getMethod()).isEqualTo("POST");
        assertThat(registerCredentials.getPath()).isEqualTo("/auth/register");
        String credentialsBody = registerCredentials.getBody().readUtf8();
        assertThat(credentialsBody).contains("\"userId\":5");
        assertThat(credentialsBody).contains("\"login\":\"ivan\"");
        assertThat(credentialsBody).contains("\"password\":\"password123\"");
        assertThat(credentialsBody).contains("\"role\":\"USER\"");
    }

    @Test
    void rollsBackUserWhenAuthServiceFails() throws Exception {
        userService.enqueue(jsonResponse(201, "{\"id\":7}"));
        authService.enqueue(jsonResponse(409, "{\"message\":\"Login already exists\"}"));
        userService.enqueue(new MockResponse().setResponseCode(204));

        StepVerifier.create(service.register(request()))
                .expectError(DownstreamServiceException.class)
                .verify();

        RecordedRequest create = userService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(create).isNotNull();
        assertThat(create.getPath()).isEqualTo("/users");

        RecordedRequest rollback = userService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(rollback).isNotNull();
        assertThat(rollback.getMethod()).isEqualTo("DELETE");
        assertThat(rollback.getPath()).isEqualTo("/users/7");

        assertThat(authService.getRequestCount()).isEqualTo(1);
    }

    @Test
    void stillFailsWhenRollbackAlsoFails() throws Exception {
        userService.enqueue(jsonResponse(201, "{\"id\":9}"));
        authService.enqueue(jsonResponse(409, "{\"message\":\"Login already exists\"}"));
        userService.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        StepVerifier.create(service.register(request()))
                .expectError(DownstreamServiceException.class)
                .verify();

        RecordedRequest create = userService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(create).isNotNull();
        assertThat(create.getPath()).isEqualTo("/users");

        RecordedRequest rollback = userService.takeRequest(1, TimeUnit.SECONDS);
        assertThat(rollback).isNotNull();
        assertThat(rollback.getMethod()).isEqualTo("DELETE");
        assertThat(rollback.getPath()).isEqualTo("/users/9");
    }

    @Test
    void failsWhenUserServiceFails() {
        userService.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        StepVerifier.create(service.register(request()))
                .expectError(DownstreamServiceException.class)
                .verify();

        assertThat(userService.getRequestCount()).isEqualTo(1);
        assertThat(authService.getRequestCount()).isZero();
    }
}

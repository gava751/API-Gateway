package com.innoboard.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RouteAndSecurityIntegrationTest {

    private static final String KID = "test-key-1";

    private static MockWebServer downstream;
    private static KeyPair keyPair;

    private static final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        keyPair = generateKeyPair();

        downstream = new MockWebServer();
        downstream.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                received.add(request);
                String body = "/.well-known/jwks.json".equals(request.getPath()) ? jwksJson() : "{\"ok\":true}";
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
        try {
            downstream.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        String baseUrl = downstream.url("/downstream").toString();
        String gatewayBaseUrl = baseUrl.substring(0, baseUrl.length() - "/downstream".length());

        registry.add("AUTH_SERVICE_URL", () -> gatewayBaseUrl);
        registry.add("USER_SERVICE_URL", () -> gatewayBaseUrl);
        registry.add("ORDER_SERVICE_URL", () -> gatewayBaseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> downstream.url("/.well-known/jwks.json").toString());
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jwksJson() {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID(KID).build();
        return "{\"keys\":[" + jwk.toJSONString() + "]}";
    }

    private static String accessToken() throws Exception {
        return tokenWithType("access");
    }

    private static String tokenWithType(String type) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .claim("user_id", 1)
                .claim("role", "USER")
                .claim("type", type)
                .expirationTime(Date.from(java.time.Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return jwt.serialize();
    }

    private static long forwardedCount() {
        return received.stream().filter(r -> r.getPath() != null && r.getPath().startsWith("/api/")).count();
    }

    private static RecordedRequest lastForwarded() {
        List<RecordedRequest> forwarded = received.stream()
                .filter(r -> r.getPath() != null && r.getPath().startsWith("/api/"))
                .toList();
        return forwarded.get(forwarded.size() - 1);
    }

    @Test
    void loginAndRegisterArePublicAndForwardedUnchanged() throws Exception {
        client().post().uri("/api/auth/login")
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest login = lastForwarded();
        assertThat(login.getPath()).isEqualTo("/api/auth/login");
        assertThat(login.getMethod()).isEqualTo(HttpMethod.POST.name());
        assertThat(login.getHeader("Authorization")).isNull();

        client().post().uri("/api/auth/register")
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus().isOk();

        assertThat(lastForwarded().getPath()).isEqualTo("/api/auth/register");
    }

    @Test
    void refreshIsPublicAndForwardedUnchanged() {
        client().post().uri("/api/auth/refresh")
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus().isOk();

        assertThat(lastForwarded().getPath()).isEqualTo("/api/auth/refresh");
    }

    @Test
    void protectedRequestsAreRejectedWithoutToken() {
        long before = forwardedCount();

        client().get().uri("/api/users/1")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(forwardedCount()).isEqualTo(before);
    }

    @Test
    void refreshTokenIsRejectedAsAccessToken() throws Exception {
        long before = forwardedCount();

        client().get().uri("/api/users/1")
                .header("Authorization", "Bearer " + tokenWithType("refresh"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(forwardedCount()).isEqualTo(before);
    }

    @Test
    void tokenWithoutTypeClaimIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .claim("user_id", 1)
                .claim("role", "USER")
                .expirationTime(Date.from(java.time.Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));

        client().get().uri("/api/users/1")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenSignedByForeignKeyIsRejected() throws Exception {
        KeyPair foreign = generateKeyPair();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .claim("user_id", 1)
                .claim("role", "ADMIN")
                .claim("type", "access")
                .expirationTime(Date.from(java.time.Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(foreign.getPrivate()));

        client().get().uri("/api/users/1")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void clientSuppliedIdentityHeadersAreStrippedOnPublicRoutes() {
        client().post().uri("/api/auth/login")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest request = lastForwarded();
        assertThat(request.getHeader("X-User-Id")).isNull();
        assertThat(request.getHeader("X-User-Role")).isNull();
    }

    @Test
    void clientSuppliedIdentityHeadersAreOverriddenOnProtectedRoutes() throws Exception {
        client().get().uri("/api/users/1")
                .header("Authorization", "Bearer " + accessToken())
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest request = lastForwarded();
        assertThat(request.getHeader("X-User-Id")).isEqualTo("1");
        assertThat(request.getHeader("X-User-Role")).isEqualTo("USER");
    }

    @Test
    void unknownPathReturnsReal404() throws Exception {
        client().get().uri("/api/does-not-exist")
                .header("Authorization", "Bearer " + accessToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Resource not found");
    }

    @Test
    void invalidTokenIsRejected() {
        long before = forwardedCount();

        client().get().uri("/api/users/1")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(forwardedCount()).isEqualTo(before);
    }

    @Test
    void validTokenIsForwardedWithClaimsAndUnchangedPath() throws Exception {
        client().get().uri("/api/users/1")
                .header("Authorization", "Bearer " + accessToken())
                .exchange()
                .expectStatus().isOk();

        RecordedRequest request = lastForwarded();
        assertThat(request.getPath()).isEqualTo("/api/users/1");
        assertThat(request.getHeader("Authorization")).isNotBlank();
        assertThat(request.getHeader("X-User-Id")).isEqualTo("1");
        assertThat(request.getHeader("X-User-Role")).isEqualTo("USER");
    }

    @Test
    void cardOrderAndItemRoutesAreForwardedUnchanged() throws Exception {
        String token = accessToken();

        client().get().uri("/api/cards/1").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(lastForwarded().getPath()).isEqualTo("/api/cards/1");

        client().get().uri("/api/orders/5").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(lastForwarded().getPath()).isEqualTo("/api/orders/5");

        client().get().uri("/api/items").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(lastForwarded().getPath()).isEqualTo("/api/items");
    }

    @Test
    void healthEndpointIsPublicAndReachableWithoutToken() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unsupportedMethodOnActuatorIsNeutral405() {
        client().delete().uri("/actuator/health")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Method not allowed");
    }

    @Test
    void mainStartsAndStopsApplicationContext() {
        try (ConfigurableApplicationContext context = SpringApplication.from(ApiGatewayApplication::main)
                .run("--server.port=0").getApplicationContext()) {
            assertThat(context.isActive()).isTrue();
        }
    }
}

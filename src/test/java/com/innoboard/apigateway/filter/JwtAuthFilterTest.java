package com.innoboard.apigateway.filter;

import com.innoboard.apigateway.config.JwtProperties;
import com.innoboard.apigateway.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-key-that-is-at-least-32-bytes!!";

    private static SecretKey key;

    private JwtAuthFilter filter;
    private GatewayFilterChain chain;
    private ArgumentCaptor<ServerWebExchange> exchangeCaptor;

    @BeforeAll
    static void initKey() {
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(new JwtService(new JwtProperties(SECRET)));
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
    }

    private String validToken() {
        return token(Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private String expiredToken() {
        return token(Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    private String token(Instant expiration) {
        return Jwts.builder()
                .subject("42")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(Date.from(expiration))
                .signWith(key)
                .compact();
    }

    private MockServerWebExchange exchange(String path, String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (authHeader != null) {
            return MockServerWebExchange.from(
                    builder.header(HttpHeaders.AUTHORIZATION, authHeader).build());
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void loginAndRegisterArePublic() {
        for (String path : List.of("/auth/login", "/auth/register")) {
            MockServerWebExchange testExchange = exchange(path, null);
            filter.filter(testExchange, chain).block();
            verify(chain).filter(testExchange);
            assertThat(testExchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void missingAuthorizationHeaderReturns401() {
        MockServerWebExchange testExchange = exchange("/users", null);

        filter.filter(testExchange, chain).block();

        verifyNoInteractions(chain);
        assertThat(testExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedAuthorizationHeaderReturns401() {
        MockServerWebExchange testExchange = exchange("/users", "Token some-value");

        filter.filter(testExchange, chain).block();

        verifyNoInteractions(chain);
        assertThat(testExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidTokenReturns401() {
        MockServerWebExchange testExchange = exchange("/users", "Bearer not-a-jwt");

        filter.filter(testExchange, chain).block();

        verifyNoInteractions(chain);
        assertThat(testExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenReturns401() {
        MockServerWebExchange testExchange = exchange("/users", "Bearer " + expiredToken());

        filter.filter(testExchange, chain).block();

        verifyNoInteractions(chain);
        assertThat(testExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTokenIsForwardedWithUserHeaders() {
        String token = validToken();
        MockServerWebExchange testExchange = exchange("/users/1", "Bearer " + token);

        filter.filter(testExchange, chain).block();

        verify(chain).filter(exchangeCaptor.capture());
        var forwarded = exchangeCaptor.getValue().getRequest();
        assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo("42");
        assertThat(forwarded.getHeaders().getFirst("X-User-Role")).isEqualTo("USER");
        assertThat(forwarded.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
    }

    @Test
    void validTokenWithoutRoleClaimStillForwardsUserId() {
        String token = Jwts.builder()
                .subject("7")
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
        MockServerWebExchange testExchange = exchange("/users/1", "Bearer " + token);

        filter.filter(testExchange, chain).block();

        verify(chain).filter(exchangeCaptor.capture());
        var forwarded = exchangeCaptor.getValue().getRequest();
        assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo("7");
        assertThat(forwarded.getHeaders().getFirst("X-User-Role")).isNull();
    }

    @Test
    void filterRunsBeforeRouting() {
        assertThat(filter.getOrder()).isEqualTo(-1);
    }
}

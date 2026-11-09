package com.innoboard.apigateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class UserPropagationWebFilter implements WebFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange sanitized = stripClientIdentityHeaders(exchange);

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt)
                .map(auth -> (Jwt) auth.getPrincipal())
                .map(jwt -> addVerifiedClaims(sanitized, jwt))
                .defaultIfEmpty(sanitized)
                .flatMap(chain::filter);
    }

    private ServerWebExchange stripClientIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(ROLE_HEADER);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange addVerifiedClaims(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> applyClaims(jwt, headers)))
                .build();
    }

    private void applyClaims(Jwt jwt, HttpHeaders headers) {
        if (jwt.getClaim("user_id") instanceof Number userId) {
            headers.set(USER_ID_HEADER, String.valueOf(userId.longValue()));
        }
        String role = jwt.getClaimAsString("role");
        if (role != null && !role.isBlank()) {
            headers.set(ROLE_HEADER, role);
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}

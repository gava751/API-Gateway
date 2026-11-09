# API Gateway

Spring Cloud Gateway (WebFlux) service for the InnoBoard microservice system.

## Responsibilities

- Single entry point (port **8080**) for all downstream services. Routes follow the
  platform contract (`/api/...` prefixes, forwarded unchanged, no path rewrite):
  - `auth-service` → `/api/auth/**` (port 8081)
  - `user-service` → `/api/users/**`, `/api/cards/**` (port 8082)
  - `order-service` → `/api/orders/**`, `/api/items/**` (port 8083)
- Token verification happens in Spring Security (`@EnableWebFluxSecurity`,
  `SecurityWebFilterChain`): `POST /api/auth/login`, `POST /api/auth/register` and
  `POST /api/auth/refresh` are public, everything else requires authentication. Access
  tokens (RS256) are validated by `oauth2ResourceServer().jwt()` with
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` pointing at the Auth Service
  `/.well-known/jwks.json` endpoint, so keys can rotate. Only tokens with
  `type=access` are accepted — refresh tokens are rejected even though they are signed
  with the same key. `user_id` and `role` claims are propagated downstream via
  `X-User-Id` / `X-User-Role` headers; any client-supplied values of these headers are
  stripped, so downstream services can trust them.

## Running

Local (expects auth-service on 8081, user-service on 8082 and order-service on 8083):

```
./mvnw spring-boot:run
```

Full stack with Docker Compose lives in the separate **infra** repository
(`../infra`): it builds images of all services, launches their databases and Redis
on an internal Docker network and publishes only the gateway on **8080**.
Authentication keys for the stack are mounted from `../auth-service/keys`; generate
the key pair first (see auth-service README).

## Configuration

| Variable | Default (local) | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Gateway port |
| `AUTH_SERVICE_URL` | `http://localhost:8081` | auth-service base URL (also the JWKS source) |
| `USER_SERVICE_URL` | `http://localhost:8082` | user-service base URL |
| `ORDER_SERVICE_URL` | `http://localhost:8083` | order-service base URL |

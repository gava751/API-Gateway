# API Gateway

Spring Cloud Gateway (WebFlux) service for the InnoBoard microservice system.

## Responsibilities

- Single entry point (port **8084**) for all downstream services:
  - `auth-service` → `/auth/**` (port 8081)
  - `user-service` → `/users/**` (port 8080)
- JWT validation on all endpoints except `POST /auth/login` and `POST /auth/register`
  (validated locally with the shared `JWT_SECRET`; user data is propagated via
  `X-User-Id` / `X-User-Role` headers).
- Custom registration flow (`POST /auth/register`):
  1. Create user data in **user-service**
  2. Create credentials in **auth-service** using the generated `userId`
  3. If step 2 fails → compensating rollback (delete the created user from user-service)

## Running

Local (expects auth-service on 8081 and user-service on 8080):

```
./mvnw spring-boot:run
```

Full stack with Docker Compose (gateway + all services + all DBs + Redis):

```
docker compose up --build
```

## Configuration

| Variable | Default (local) | Description |
|---|---|---|
| `SERVER_PORT` | `8084` | Gateway port |
| `JWT_SECRET` | dev fallback | Must match auth-service `JWT_SECRET` |
| `AUTH_SERVICE_URL` | `http://localhost:8081` | auth-service base URL |
| `USER_SERVICE_URL` | `http://localhost:8080` | user-service base URL |

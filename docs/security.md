# Security

How the system authenticates callers, decides what they may do, protects service-to-service calls, and how
that is tested. The request and response contract (headers, error codes, each endpoint's "Auth required")
is in [api.md](api.md) §1 and §10; this page explains the design behind it and its limits.

## 1. Overview

```
client ──► api-gateway :8080 ──► identity / driver / trip / matching / pricing / payment / notification
            verifies JWT              identity and driver verify the JWT again
            checks the role           the other five trust the gateway
            sets X-User-Id and X-User-Role
```

| Layer | What it enforces |
|---|---|
| api-gateway | Token signature, expiry and shape; role per endpoint; replaces `X-User-Id` and `X-User-Role`; does not route service-to-service paths; refuses paths that are not in normal form |
| identity-service | Issues tokens; BCrypt password hashes; verifies the JWT on `/api/users/**` |
| driver-service | Verifies the JWT, role rules, "own resource" rule (`{id}` must be the caller's user id); `X-Internal-Api-Key` on service-to-service endpoints |
| trip-service | Uses gateway-provided `X-User-Id` and `X-User-Role` for client POST requests; requires `X-Internal-Api-Key` for internal calls |
| matching-service | Requires `X-Internal-Api-Key` on its HTTP endpoints; consumes trip events from Kafka |
| pricing, payment, notification | No authentication of their own: they rely on the gateway |

A request is rejected at the first layer that fails it, and a rejected request never reaches a backend.

## 2. Authentication

**Accounts.** `POST /api/auth/register/passenger` and `POST /api/auth/register/driver` create the two
kinds of account. The role comes from the endpoint, a `role` in the body is ignored, so `ADMIN` cannot be
self-registered. Passwords are 8–72 characters, stored as BCrypt hashes, excluded from logs and never
returned.

**Login.** `POST /api/auth/login` returns an access token. An unknown email and a wrong password give the
same `401 INVALID_CREDENTIALS`, so the endpoint does not reveal which emails exist.

**Token.** JWT signed with HS256.

| Claim | Value |
|---|---|
| `sub` | User id (UUID); for a driver also the driver id |
| `role` | `PASSENGER`, `DRIVER` or `ADMIN` |
| `iat`, `exp` | Issued at, expiry. Lifetime is `JWT_EXPIRATION_MS`, 24 hours by default |

The signing key is `JWT_SECRET` (Base64, at least 256 bits), shared by identity-service, api-gateway and
driver-service. Each of them refuses to start with a key that is not Base64 or is too short.

**Verification.** A token is accepted only from `Authorization: Bearer <token>`. The verifier checks the
signature, the expiry, that `sub` is a UUID and that `role` is a known role.

| Case | Answer |
|---|---|
| No `Authorization: Bearer` header (also: token in the query string or a cookie, `Basic`, no scheme) | `401 MISSING_TOKEN` |
| Wrong signature, unsigned (`alg: none`), payload changed after signing, malformed, no `sub` or `role`, unknown role | `401 INVALID_TOKEN` |
| Expired | `401 TOKEN_EXPIRED` |

The answer says which of the three it is and nothing more: it does not echo the token or name the
library or the exception. The gateway is stateless (no session, no cookie), so there is nothing to
hijack besides the token itself.

## 3. Authorization

The role rules are applied at the gateway (full table in [api.md](api.md) §10) and again in driver-service.

| Who | Endpoints |
|---|---|
| Public | The two register endpoints, login, `GET /api/drivers/available`, `/actuator/**` |
| `PASSENGER` | `/api/drivers/nearby`, `POST /api/trips`, `POST /api/trips/{id}/cancel` |
| `DRIVER` | `PATCH /api/drivers/{id}/status` and `/location`, `POST /api/trips/{id}/accept`, `/start`, `/complete` |
| `ADMIN` | `POST /api/matching/{requestId}/match`; the rule covers every `/api/matching/**` path and method |
| Any valid token | Every other routed path |
| Nobody (not routed, `404`) | `/internal/**`, `/api/drivers/{id}/reserve`, `POST /api/matching/{requestId}/rematch`, `POST /api/pricing/final`, `POST /api/payments/{tripId}/charge` and `/refund` |

A valid token with the wrong role gets `403 ACCESS_DENIED`. `ADMIN` is not a wildcard: it gets `403` on
the PASSENGER-only and DRIVER-only endpoints.

**Own resource.** The gateway does not know who owns what. driver-service compares the path id with the
token's user id and answers `403 NOT_OWN_RESOURCE` when a driver tries to change another driver's status
or location. An off-duty driver's last position is shown only to that driver.

**Caller identity for the other services.** For an authenticated request the gateway sets `X-User-Id` to
the token's `sub` and `X-User-Role` to the token's `role`. Whatever the client sent in either header is
dropped, on public routes too, so behind the gateway the headers are either absent or verified values.

## 4. Service-to-service calls

| Call | Protection |
|---|---|
| identity-service → driver-service `/internal/drivers` | Header `X-Internal-Api-Key` |
| matching-service → driver-service `POST`/`DELETE /api/drivers/{id}/reserve` | Header `X-Internal-Api-Key` |
| matching-service → trip-service internal GET/cancel | Header `X-Internal-Api-Key` |
| trip-service → driver-service `DELETE /api/drivers/{id}/reserve` | Header `X-Internal-Api-Key` |
| Everything on Kafka | None: plaintext listener, reachable only inside the Docker network |

`X-Internal-Api-Key` carries the shared secret `INTERNAL_API_KEY`. driver-service compares it in constant
time, answers `401 INVALID_INTERNAL_API_KEY` when it is missing or wrong, and does not start when the key
is blank. A JWT does not replace the key. The gateway has no route for `/internal/**` or `/reserve` and
answers `404`, so these endpoints cannot be reached through port 8080 even by a caller who knows the key.

The same holds for the "internal" endpoints of matching, pricing and payment: the gateway routes only
`/api/matching/*/match`, `/api/pricing/quote` and `/api/payments/{tripId}`, so `rematch`, `POST /api/pricing/final`
and `POST /api/payments/{tripId}/charge` and `/refund` answer `404` at port 8080 for every caller. trip-service
calls `/api/pricing/final` directly. The manual trigger `POST /api/matching/{requestId}/match` stays routed for
an `ADMIN` token, and matching-service still asks for `X-Internal-Api-Key` behind it.

## 5. Input handling

The first three points describe identity-service and driver-service.

- Request bodies and parameters are validated before any work is done: `400 VALIDATION_ERROR`,
  `400 MALFORMED_REQUEST` (not JSON, unknown enum value), `400 INVALID_PARAMETER` (id is not a UUID).
- Database access uses JPA with bound parameters only; no query is built from request text.
- Error bodies have the fixed format of [api.md](api.md) §8. An unexpected error returns
  `500 INTERNAL_SERVER_ERROR` with a generic message; the details go to the log with the correlation id.
- The gateway refuses a path that is not in normal form (`..`, `//`, `;`, encoded `/` or `.`) with `400`,
  so a public prefix cannot be used to reach a protected or internal path.
- Kafka consumers validate each event; a malformed event goes to the DLQ instead of being processed
  ([events.md](events.md) §8).

## 6. Secrets and configuration

All secrets come from environment variables (`.env`, not committed; `.env.example` lists them).

| Variable | Used by | Purpose |
|---|---|---|
| `JWT_SECRET` | identity-service, api-gateway, driver-service | Signs and verifies tokens; must be identical in all three |
| `JWT_EXPIRATION_MS` | identity-service | Token lifetime |
| `INTERNAL_API_KEY` | identity-service, driver-service, trip-service, matching-service | Service-to-service secret; must be identical |
| `POSTGRES_PASSWORD` | All databases and their services | Database password |
| `SEED_DATA_ENABLED`, `SEED_USER_PASSWORD` | identity-service, driver-service | Demo accounts and their shared password |
| `GRAFANA_ADMIN_PASSWORD` | Grafana | Dashboard login |

The repository contains default values so the system starts with no setup. They are public: set your own
`JWT_SECRET`, `INTERNAL_API_KEY` and passwords, and `SEED_DATA_ENABLED=false`, for anything that is not a
local demo.

## 7. Security tests

Run with `./gradlew test` in the service's directory.

| Test class | Service | Covers | Tests |
|---|---|---|---|
| `GatewaySecurityTest` | api-gateway | Missing, invalid and expired token; token without role, with unknown role, with a non-UUID subject; `403` for each wrong role; public routes; `X-User-Id` and `X-User-Role` set from the token and forged values replaced | 45 |
| `GatewaySecurityRegressionTest` | api-gateway | Attempts to get around the rules: `alg: none`, tampered role, cut-off signature; token in query string or cookie; `ADMIN` on role-restricted endpoints; the `ADMIN` rule on matching for other methods and spellings; `HEAD` on a GET-only rule; other methods and spellings of public paths; forged `X-User-Id` on public routes; path traversal and encoding tricks; internal paths and the internal endpoints of matching, pricing and payment with a valid token (also `ADMIN`) and the internal key; no session; error bodies that reveal nothing; actuator endpoints; CORS | 90 |
| `GatewayRoutingTest` | api-gateway | Which paths are routed; internal and unknown paths answer `404` | 32 |
| `AuthRegisterSecurityTest`, `AuthLoginSecurityTest`, `UserSecurityTest` | identity-service | Register and login are public; a `role` in the body is ignored; wrong password; missing, malformed, expired and wrongly signed token on `/api/users/{id}` | 19 |
| `AuthControllerTest` | identity-service | Invalid and malformed register and login bodies, duplicate email, wrong credentials | 28 |
| `DriverSecurityTest` | driver-service | Token and role rules; own-resource rule; internal API key required on `/reserve`, a JWT does not replace it | 22 |
| `InternalApiKeyInterceptorTest` | driver-service | Missing, wrong and correct internal API key on `/internal/**` | 10 |

Result on 2026-11-16: all of the above pass (api-gateway 139 tests, identity-service 97, driver-service 249).
api-gateway re-run on 2026-11-20 after the internal endpoints were closed: 168 tests pass.
Not run against the Docker stack.

The regression tests found one defect, now fixed in api-gateway and driver-service: the PASSENGER rule
on `/api/drivers/nearby` was declared for `GET` only, and a backend answers `HEAD` with its `GET` handler,
so a `DRIVER` token could run the search with `HEAD`. The rule now covers every method on that path.

### Manual checks through the gateway

With the stack running and the demo accounts seeded (`docs/test-data/identity/README.md`):

```bash
# A driver's token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d @docs/test-data/identity/03_login_seeded_driver.json | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
DRIVER_ID=d0000000-0000-4000-8000-000000000001

# 1. No token                      -> 401 MISSING_TOKEN
curl -s http://localhost:8080/api/users/$DRIVER_ID

# 2. Invalid token                 -> 401 INVALID_TOKEN
curl -s http://localhost:8080/api/users/$DRIVER_ID -H "Authorization: Bearer not-a-jwt"

# 3. Wrong role (driver creates a ride) -> 403 ACCESS_DENIED
curl -s -X POST http://localhost:8080/api/trips -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}'

# 4. Another driver's profile      -> 403 NOT_OWN_RESOURCE
curl -s -X PATCH http://localhost:8080/api/drivers/d0000000-0000-4000-8000-000000000002/status \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"status":"AVAILABLE"}'

# 5. Internal path through the gateway -> 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/drivers/$DRIVER_ID/reserve \
  -H "Authorization: Bearer $TOKEN"

# 6. Internal path on the service port without the key -> 401 INVALID_INTERNAL_API_KEY
curl -s -X POST http://localhost:8082/api/drivers/$DRIVER_ID/reserve -H "Content-Type: application/json" \
  -d '{"tripId":"550e8400-e29b-41d4-a716-446655440000","expectedVersion":0}'
```

## 8. Known limitations

Accepted for this project; each would have to be closed before a real deployment.

| Limitation | Consequence |
|---|---|
| `docker-compose.yml` publishes every service port (8081–8087), not only the gateway | A caller on the host can skip the gateway. trip, matching, pricing, payment and notification have no authentication of their own and accept any `X-User-Id` there |
| `GET /api/drivers/available` is public | Anyone can list available drivers near a point: at most 50 per request, within 50 km, and with `page` every available driver in that circle, page by page. The endpoint remains public even though matching-service now has an internal key |
| Role rules are written per method and path; everything else needs only a valid token | A path or method that no rule names is forwarded for any role, and the backend answers `404` or `405` |
| `GET /api/users/{id}` has no own-profile rule | Any logged-in user can read another user's name and email |
| No refresh token, no logout, no revocation | A stolen token works until it expires (24 hours) |
| No rate limit or lockout on login | Passwords can be guessed without being slowed down |
| One shared `JWT_SECRET` (symmetric) and one shared `INTERNAL_API_KEY` | A service that holds the secret could also issue tokens; a leaked key opens every internal endpoint |
| The gateway forwards a client's `X-Internal-Api-Key` header unchanged | Of the endpoints that check the key, only `POST /api/matching/{requestId}/match` is routed, and it needs an `ADMIN` token at the gateway as well. The others are not routed |
| `/actuator/health` (with details) and `/actuator/prometheus` are public at the gateway | Health details and metrics are readable without a token |
| HTTP only; Kafka and the databases use no TLS and Kafka has no ACL | Traffic is readable and writable by anything inside the network |
| Default secrets are committed | Safe only for a local demo (§6) |

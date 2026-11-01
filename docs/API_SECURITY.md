# OAuth2, OpenAPI and rate limiting

Both services accept bearer JWT access tokens. The default local mode preserves the existing demo; provider mode connects the APIs to an external OAuth2/OIDC issuer. The APIs do not issue login or refresh tokens.

## Local demo mode

Existing `scripts/local-demo.ps1` behavior remains available. Set `JWT_SECRET` to an operator-generated value of at least 32 bytes. `SECURITY_MODE=local-jwt` is the default. A blank or short secret prevents web-service startup. Roles and ownership checks remain active.

## OAuth2 provider mode

Set these environment variables separately for both service processes, or map the equivalent `ledger.security.*` / `projection.security.*` Spring properties:

| Variable | Purpose |
| --- | --- |
| `SECURITY_MODE=oauth2` | Explicitly selects provider validation. No HMAC fallback. |
| `OAUTH2_ISSUER_URI` | Exact trusted issuer URI, matching the token's `iss`. HTTPS required except loopback HTTP for local tests. |
| `OAUTH2_AUDIENCE` | Required API audience; the token's `aud` must contain this value. |
| `OAUTH2_JWK_SET_URI` | Optional explicit provider JWKS endpoint. When absent, issuer metadata discovery resolves it at startup. HTTPS/loopback rules also apply here. |
| `OAUTH2_AUTHORIZATION_URL` | Optional authorization endpoint for Swagger's interactive authorization-code flow. |
| `OAUTH2_TOKEN_URL` | Token endpoint; required together with the authorization URL when either is set. |
| `OAUTH2_SWAGGER_CLIENT_ID` | Public OAuth2 client registered with the provider for Swagger UI. No browser client secret is stored. |

The supplied Compose file forwards these settings to both service containers. It defaults to the local JWT demo; setting `SECURITY_MODE=oauth2` selects provider mode for both services. In local mode, an absent secret fails application startup rather than silently allowing unauthenticated access. In provider mode the shared secret is unused and may be omitted. Use reachable provider URLs from the API containers and the browser. An explicit JWKS URL avoids discovery startup dependency, but token verification still needs available public keys or a previously populated cache. A wrong signature, unknown untrusted signing key, wrong/missing audience, wrong issuer, expired token, missing expiration or invalid subject is rejected with 401. Provider mode accepts RS256, rather than arbitrary JWT algorithms.

Before switching an existing dataset from local tokens to a provider, align provider subjects with stored account-owner IDs; this change does not migrate or link existing identities automatically. Use the same trusted identity mapping across both services.

Configure the issuer to include a signed `roles` claim containing `CUSTOMER` and/or `OPERATOR`. Customers can create customer accounts, debit/read their own accounts and read projection accounts explicitly assigned by the signed `accounts` UUID list. A provider's default roles format may differ: configure claim mapping at the provider. Do not put an OPERATOR role in a public client mapper by default. Account claims are not dynamically reconciled with current ledger ownership; use short-lived tokens and the existing documented ownership limits.

For Swagger, configure allowed browser origins at the provider and register public-client redirect URIs for each service's `/swagger-ui/oauth2-redirect.html`, such as the local service URLs on ports 8080 and 8081. Use the provider's authorization-code flow with PKCE. Both URLs must be configured to publish this flow; otherwise Swagger still accepts a provider-issued token through `bearerAuth`. Authorization is not persisted by Swagger. Browser login, callback behavior and token exchange with a real external provider remain unverified here; the HTTP tests use an ephemeral public-JWKS fixture to verify the resource-server boundary.

With an existing provider, set the issuer and API audience in your shell, optionally set its explicit JWKS and Swagger endpoints, then start the local stack:

```powershell
$env:SECURITY_MODE = 'oauth2'
# Set OAUTH2_ISSUER_URI and OAUTH2_AUDIENCE to your provider and API registration.
docker compose -f deploy/compose.yaml up --build -d
```

## OpenAPI

Each service exposes `/v3/api-docs` and `/swagger-ui/index.html`. The generated specification declares bearer security, the optional OAuth2 alternative, endpoint summaries and Problem Details errors. The transfer contract explicitly declares 201 for a committed transfer, 200 for idempotent replay, and 422 for insufficient funds or an idempotency payload conflict. Reuse the same Idempotency-Key and request body when retrying a transfer. Rate limits also apply to replay requests.

401 means authentication failed and includes `WWW-Authenticate: Bearer`. 403 means a valid identity lacks the role or account access. 429 includes a Problem Details body and `Retry-After`, measured in whole seconds. Public documentation and health paths bypass the principal limiter. An invalid bearer token may still be rejected by authentication even on a public path.

## Rate limits

| Variable | Default | Meaning |
| --- | --- | --- |
| `RATE_LIMIT_ENABLED` | `true` | Enables the principal limiter. |
| `RATE_LIMIT_REQUESTS_PER_SECOND` | `500` | Continuous token refill per authenticated subject. |
| `RATE_LIMIT_BURST` | `1000` | Maximum bucket capacity. |
| `RATE_LIMIT_MAX_PRINCIPALS` | `10000` | Maximum identities retained per service process. |
| `RATE_LIMIT_IDLE_SECONDS` | `300` | Idle time before a bucket can be discarded; must allow a complete refill. |

Authorized `/api/v1/**` requests share one quota for the signed subject within that service process. Renewing a token or changing forwarded/IP headers does not reset the quota. Role-rejected or unauthenticated requests do not allocate buckets. Requests rejected later by controller validation or account ownership still consume quota. `X-RateLimit-Limit` reports burst capacity and `X-RateLimit-Remaining` reports currently available whole tokens.

Counters use a monotonic clock and synchronized consumption to prevent concurrent overspending. When the identity cap is full, only idle buckets are removed; active identities are not evicted. New identities receive 429 until capacity becomes available. These in-memory limits reset on service restart and are independent across replicas and services. Use an edge gateway or shared limiter for a coordinated quota and unauthenticated traffic control. Earlier Azure performance numbers describe the tagged measurement source before these security changes; this feature has not been cloud-benchmarked.

## Verification

Run `./mvnw clean verify -Pcoverage --batch-mode` with Java 21 and Docker. New tests exercise real HTTP requests, RSA-signed access tokens and an ephemeral JWKS server, issuer/audience/expiry/subject rejection, public-key refresh, HMAC downgrade rejection, role/account authorization, live OpenAPI and Swagger endpoints, 429/Retry-After and token/IP quota bypass attempts. Separate deterministic tests cover refill timing, parallel consumption and bounded-state behavior without timing sleeps. Existing financial, migration, outbox and projection regressions remain part of the full suite.

Validation on 2026-10-26: `clean verify -Pcoverage` passed **90 Java tests** (62 ledger, 28 projection), with zero failures, errors or skips. Five image-scan policy tests also passed. Compose configuration parsing and provider/rate-limit environment forwarding passed. These are local component/integration results, not new remote CI, live-provider login, a fresh image vulnerability scan or an Azure performance rerun. See [sanitized validation evidence](evidence/api-security-2026-10-26.json).

Implementation follows the [Spring Security resource-server validation model](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) and [springdoc Swagger configuration](https://springdoc.org/).

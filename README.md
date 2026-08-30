# WeThrive backend

Java 21 / Spring Boot 4.1 modular monolith for the WeThrive 1.0.0 offline-first finance application. This repository is independently buildable and is designed to sit beside `wethrive_frontend`, `wethrive_infrastructure`, and `wethrive_documentation`.

## Authentication and secrets

Authentication intentionally uses opaque, high-entropy access and rotating refresh tokens stored only as SHA-256 hashes in PostgreSQL and delivered in `HttpOnly` cookies. It does **not** use JWTs, so there is no `JWT_SIGNING_KEY`. Refresh-token reuse revokes the complete token family in an independent transaction. All state-changing endpoints—including unauthenticated login, registration, bootstrap, reset, and verification—require the CSRF token obtained from `GET /api/v1/auth/csrf`.

Production startup rejects placeholder/missing security configuration. Required values include:

- `OFFLINE_SIGNING_PRIVATE_KEY`: base64/base64url PKCS#8 P-256 private DER
- `OFFLINE_VERIFICATION_PUBLIC_KEY`: base64/base64url SPKI P-256 public DER
- `OFFLINE_KEY_ID`
- `PUSH_ENCRYPTION_KEY`
- `MAIL_HOST` and `MAIL_FROM`
- VAPID public/private keys and subject when Web Push is enabled

Offline grants are compact ES256 envelopes with raw IEEE-P1363 signatures. The public SPKI key is intentionally available at `GET /api/v1/devices/offline-verification-key`.

## Database

Flyway owns the schema and Hibernate runs with `ddl-auto=validate`. `V1__baseline.sql` creates constraints, cross-space finance guards, indexes, and the original reference definitions. Forward-only `V7__standard_categories_palette_and_refund_integrity.sql` expands the canonical expense list to 38 entries and idempotently backfills missing system defaults in existing spaces. Migrations never seed user financial records.

## Verification

Use Java 21 and run:

```bash
mvn clean verify
```

Build the versioned container from this repository with:

```bash
docker build -t wethrive/backend:1.0.0 .
```

The integration suite uses PostgreSQL 18.4 through Testcontainers when Docker is available. Unit coverage includes canonical workbook calculations/refunds, ES256 tamper and authorization invalidation, role permissions, proxy-chain handling, settings validation, email fragment secrecy, Excel formula neutralization/numeric dates, and architecture boundaries.

For a direct schema smoke test, start PostgreSQL and run the application with `DATABASE_URL`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`; startup succeeds only after Flyway migration and Hibernate validation both pass.

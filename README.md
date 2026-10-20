# WeThrive backend

Java 21 / Spring Boot 4.1 modular monolith for the WeThrive 1.0.0 offline-first finance application. This repository is independently buildable and is designed to sit beside `wethrive_frontend`, `wethrive_infrastructure`, and `wethrive_documentation`.

## Authentication and secrets

Authentication intentionally uses opaque, high-entropy access and rotating refresh tokens stored only as SHA-256 hashes in PostgreSQL and delivered in `HttpOnly` cookies. It does **not** use JWTs, so there is no `JWT_SIGNING_KEY`. Refresh-token reuse revokes the complete token family in an independent transaction. All state-changing endpoints—including unauthenticated login, registration, bootstrap, reset, and verification—require the CSRF token obtained from `GET /api/v1/auth/csrf`.

Production startup rejects placeholder/missing security configuration. Required values include:

- `OFFLINE_SIGNING_PRIVATE_KEY`: base64/base64url PKCS#8 P-256 private DER
- `OFFLINE_VERIFICATION_PUBLIC_KEY`: base64/base64url SPKI P-256 public DER
- `OFFLINE_KEY_ID`
- `PUSH_ENCRYPTION_KEY`
- `MAIL_FROM` and `MAIL_PROVIDER` (`SMTP` or `GRAPH`)
- For SMTP: `MAIL_HOST`, SMTP authentication credentials when enabled, and required TLS/startup-test settings
- For Microsoft 365 Graph: `MAIL_GRAPH_TENANT_ID`, `MAIL_GRAPH_CLIENT_ID`, `MAIL_GRAPH_CLIENT_SECRET`, and `MAIL_GRAPH_SENDER`; `MAIL_FROM` must identify that same mailbox. Disable SMTP test/health checks.
- VAPID public/private keys and subject when Web Push is enabled

Offline grants are compact ES256 envelopes with raw IEEE-P1363 signatures. The public SPKI key is intentionally available at `GET /api/v1/devices/offline-verification-key`.

Graph mail uses app-only OAuth client credentials, a short-lived in-memory token cache, fixed Microsoft HTTPS endpoints, and finite connection/request timeouts. Grant the application only scoped Exchange Online `Application Mail.Send` access to the chosen sender mailbox. Provider errors and tokens are excluded from logs. Readiness checks do not contact Microsoft; verify actual delivery with a controlled invitation. A failed Graph send never falls back to SMTP.

## Application owner and invitations

Set a high-entropy `APP_BOOTSTRAP_TOKEN`, deploy the application, and open `/setup` over HTTPS. Enter the owner's name, email, password, and bootstrap secret. The account receives the `SUPER_ADMIN` application role and a private personal space. On an existing installation, the same route can claim an existing account only after verifying that account's current password and the operator's bootstrap secret. No existing user is automatically promoted by migration.

`GET /api/v1/auth/setup-status` reports initialization without exposing owner details. Bootstrap uses a permanent database singleton and a row lock so concurrent requests cannot create multiple owners. Registration waits for initialization. After successful setup, clear `APP_BOOTSTRAP_TOKEN` from the encrypted configuration and redeploy; the database seal prevents reopening setup even while the original secret is still configured. The owner cannot delete their account through the app.

Use `REGISTRATION_MODE=INVITE_ONLY` for private enrollment. In the owner's **Settings → Application invitations** panel, send and revoke seven-day email-bound invitations. The endpoints are `GET/POST /api/v1/admin/invitations` and `DELETE /api/v1/admin/invitations/{id}`. One-time links use `#applicationInvitationToken=...`, which the frontend removes from browser history. New users verify their email before signing in; existing users can accept or decline through `/api/v1/application-invitations/accept` or `/decline` using a JSON `token` field. Tokens are stored only as hashes and omitted from production API responses.

Application invitations grant ordinary account access, never application administration or access to another person's financial data. Household sharing continues through the separate household invitation flow. Owner administration requires an online session and does not bypass space permissions.

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

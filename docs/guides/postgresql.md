# PostgreSQL integration

`postgres-adapter` stores encrypted record envelopes in `efs_encrypted_records` and protected tokens in `efs_protected_tokens`. It stores neither original field values nor raw n-grams. Writes replace a record and its postings in a transaction. Queries scope candidates by tenant, field, key version, token strategy, and token format.

## Local database

The repository's Compose file starts a disposable PostgreSQL 18 service. It binds to `127.0.0.1:54329`, uses a test-only database and password, and has a health check:

```sh
docker compose -f compose.test.yaml up --detach --wait
./gradlew --no-daemon :postgres-adapter:functionalTest
docker compose -f compose.test.yaml down --volumes --remove-orphans
```

The functional tests use synthetic records and a deterministic test key. They initialize the schema, index values, query candidates, decrypt and verify matches, check no-match behavior, and test tenant/field isolation and updates. The tests do not provide a deployment-ready database configuration.

## Production integration

- Use the application's normal PostgreSQL and migration process. Review `postgres-adapter/src/main/resources/db/schema.sql` before deploying schema changes.
- Use TLS, least-privilege database roles, restricted network access, encrypted backups, and a retention policy appropriate for ciphertext and index tokens.
- Keep root keys in an application-managed secret store or KMS-compatible service. EFS does not store keys in PostgreSQL.
- Authorize the tenant and field in application code before calling EFS. Database scoping is defense in depth, not application authorization.
- Keep plaintext, normalized forms, tokens, and secrets out of logs, traces, database query logs, and metrics.
- Treat the database's equality, frequency, overlap, identifiers, and access-pattern leakage as in scope. See the [security guide](../security/index.md).

The adapter's schema initializer is useful in tests and local examples. For production, run versioned migrations under the application's change controls rather than asking each service instance to create tables at startup.

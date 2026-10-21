# Production security and operational review

**Status:** owner decisions recorded: opt-in deletion, Soundex, prefix, and multilingual strategies remain outside the production allowlist; release posture is pre-production candidate only. This review is not a third-party audit or security certification.

## Review evidence

- The threat model covers database dumps, backups, query-oracle behavior, application compromise, and the host authorization boundary. Owner acceptance applies to the exact and character n-gram baseline only. Per the owner's decision, deletion-neighborhood, Soundex, prefix, and multilingual strategies are explicitly excluded from the production allowlist and documented separately in `THREAT_MODEL.md`.
- `PostgresTokenIndexRepository` scopes candidate queries by tenant, field, and key ID and uses prepared statements for all values. Token-count-dependent SQL text adds only placeholder groups; token contents and scope values remain bound parameters. PostgreSQL functional tests cover tenant/field/key isolation, update/delete behavior, and transaction rollback.
- `EncryptedFuzzySearchService` documents that callers must authorize tenant and field access before invoking the API. The library does not authenticate callers or enforce application roles. Integration tests cover scoped retrieval, and the database adapter tests verify that shared tokens do not cross tenant/field/key boundaries.
- There are no production logging or metrics calls in the library. The synthetic benchmark writes aggregate counts, timings, and environment metadata only; it does not emit fixture values or protected tokens. This does not constrain host-application logging, traces, metrics, JVM diagnostics, or crash dumps.
- Spring Boot auto-configuration requires host-provided key material; there is no default/test root key. Schema creation is explicit, and the application owns authorization, TLS, key custody, rate limiting, migration coordination, and backup/restore policy.
- Key rotation state and active-key selection are host-managed. The library searches a caller-selected list of up to 16 key IDs. Each database record has one key version; reindex atomically replaces ciphertext and postings for that record.
- `./gradlew --no-daemon clean check` passed on JDK 25.0.4.1, including PostgreSQL functional tests, Java/Kotlin starter tests, formatting, and all module coverage gates. `search-core`'s runtime graph contains only `crypto-core`, `language-core`, and ICU4J; it has no Spring dependency.
- The deterministic PostgreSQL benchmark rerun is recorded in `BENCHMARKING.md`. On its 600-record synthetic fixture, candidate recall was 300/300 but candidate precision was 1%; the candidate cap of 100 was reached on all 300 typo queries. Final verification had 1.0 precision/recall for this fixture. The run is local and exploratory, not a production SLO or recall guarantee.
- A local OWASP Dependency-Check run was attempted but could not complete because `NVD_API_KEY` is not configured in this environment (the NVD updater rejected the empty key). CI's scheduled dependency scan and SBOM remain the dependency-vulnerability evidence to inspect before release.

## Findings and release recommendation

1. **Production workload thresholds are not approved.** The benchmark shows 100% candidate-limit saturation and 1% candidate precision in the tested synthetic workload. Perfect final results on that fixture do not prove recall on larger or differently distributed data. Do not claim a general accuracy or latency guarantee; agree workload-specific thresholds and evaluate them on representative, held-out data before a production deployment.
2. **Optional strategy leakage is excluded from production use.** The owner chose to keep deletion, Soundex, prefix, and multilingual strategies outside the production allowlist. The Spring starter defaults only to exact and n-gram strategies; deployments should not enable the optional strategies under this release posture.
3. **Deployment controls are host responsibilities.** The library cannot verify caller authorization, enforce rate limits, protect transport, coordinate active-key changes across writers, prevent host telemetry from capturing sensitive values, or manage backup/key retirement. Each deployment needs an operational review for these controls.
4. **No third-party security audit artifact exists in the repository.** This source-level review and automated testing are not a substitute for an independent cryptographic or application-security audit where the deployment's risk policy requires one.

**Owner release decision (2026-10-21): pre-production candidate only, with exact and n-gram strategies.** The measured synthetic benchmark limitations are not accepted as a basis for general production rollout. Do not promote to general production until representative, held-out workloads meet approved recall, precision, latency, and capacity thresholds and deployment controls above are verified. No such thresholds or representative workload evidence are currently recorded.

## Remaining release prerequisites

- Before any general production release, approve workload-specific candidate, recall/precision, latency, and capacity thresholds and identify representative held-out evidence that satisfies them.
- Before release, inspect scheduled CI dependency-scan and SBOM evidence. The local Dependency-Check scan could not run because this environment has no `NVD_API_KEY`; do not expose or commit the GitHub secret to make a local run work.

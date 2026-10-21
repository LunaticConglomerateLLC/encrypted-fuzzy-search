# Application-managed key rotation

Key rotation is coordinated by the host application. `MasterKeyProvider` resolves key IDs, and the host owns durable migration progress, the active write-key pointer, scheduling, and operational cutover. EFS does not scan application tables or persist a rotation state machine.

Each encrypted search record has one key ID. `PostgresTokenIndexRepository.upsert` atomically replaces that record's ciphertext and protected postings when it is reindexed. It does not keep two indexed versions of the same record. During a rollout, different records may use different key versions; `EncryptedFuzzySearchService.searchAcrossKeyVersions` searches the explicit set of versions that the host still considers active. Its candidate limit is per key version, and the API accepts at most 16 versions per call.

## Staged rollout

1. Provision the new root key and verify all application instances can resolve both old and new key IDs. Keep the old key enabled.
2. In the host's durable configuration, move new writes to the new key ID. Coordinate this change across writers; do not write the same record once under each version because each record ID has one atomic index row.
3. Backfill records from the application's authoritative source. Page through that source using its normal durable cursor, obtain each value in memory, and call `indexValue` with the new key ID. The upsert replaces the old encrypted record and postings transactionally. Do not stage plaintext in temporary files, logs, metrics, or migration tables.
4. While old-version records remain, query using both IDs, for example `searchAcrossKeyVersions(tenant, field, List.of("key-v2", "key-v1"), query, perVersionLimit, threshold)`. The host controls and authorizes the key-ID list. Queries fail if a required key is unavailable; keep the key provider and index versions aligned.
5. Track migration progress in host-owned state. Verify the source scan completed and reconcile counts or record IDs using application-specific checks. Because writes can race with backfill, ensure each writer uses the new active key before declaring completion, and replay changed records after the scan if needed.
6. Cut over searches by atomically changing the host's configured searchable-key list to the new key only after validation. Keep the old key available through the agreed rollback/backup-retention period.
7. To recover before retirement, restore the prior host configuration and search both versions. New-key records remain readable while that key is retained. If all records must return to the old key, run a reverse backfill from the authoritative source; changing the active pointer alone does not re-encrypt existing records.
8. Retire the old key and its remaining index data only after rollback is no longer required and reconciliation confirms no old-key records remain. EFS does not delete keys or claim rotation completion on the host's behalf.

## Security and operational limits

- Never persist plaintext as migration progress or intermediate output. The migration API accepts a value already held by the host and only writes ciphertext and protected tokens to EFS storage.
- Restrict host-managed migration state and searchable-key configuration to trusted operators. A key ID is not authorization; normal tenant/field authorization is still required.
- Record replacement and token replacement are atomic for each record, not for an entire batch or the application-wide active-key pointer. The host must provide durable checkpoints, writer coordination, cutover atomicity, and recovery procedures.
- Candidate limits apply separately to each key ID, so a multi-version query can examine up to `keyIds.size() * candidateLimitPerKeyVersion` candidates. Choose limits and the version list accordingly.
- A database backup can retain old ciphertext and protected tokens after live rows are migrated. Key retirement must account for backup retention and restore procedures.

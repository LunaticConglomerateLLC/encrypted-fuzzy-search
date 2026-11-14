# Key rotation

The host application owns key rotation state. EFS resolves a caller-supplied key ID through `MasterKeyProvider`; it does not track the active write key, migration cursor, or completion status.

Each record has one key version. Reindexing the record with a new key atomically replaces its ciphertext and token postings. During migration, records can temporarily use different key IDs, and the application can search a caller-authorized list of up to 16 key versions.

## Staged rotation

1. Provision the new key and confirm every service instance can resolve both old and new IDs.
2. Move new writes to the new ID in host-managed configuration.
3. Backfill from the application's authoritative source. Hold plaintext only in memory and call the normal indexing API with the new key ID.
4. Search with the old and new IDs while old-version records remain. Keep the old key available.
5. Reconcile record coverage and handle writes that raced with the backfill.
6. Switch the host's active search-key list to the new ID after validation.
7. Retire the old key only after rollback and backup-retention windows end and no records need it.

The library does not perform the backfill, keep durable migration progress, delete keys, or certify that rotation is complete. Database backups can retain old ciphertext and tokens after live rows have been reindexed. See the [full rotation procedure in the source repository](https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search/blob/main/KEY_ROTATION.md).

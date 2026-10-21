CREATE TABLE IF NOT EXISTS efs_encrypted_records (
    tenant_id TEXT NOT NULL,
    field_id TEXT NOT NULL,
    record_id TEXT NOT NULL,
    key_id TEXT NOT NULL,
    transform_version TEXT NOT NULL,
    ciphertext BYTEA NOT NULL,
    PRIMARY KEY (tenant_id, field_id, record_id)
);

CREATE TABLE IF NOT EXISTS efs_protected_tokens (
    tenant_id TEXT NOT NULL,
    field_id TEXT NOT NULL,
    record_id TEXT NOT NULL,
    key_id TEXT NOT NULL,
    token_strategy TEXT NOT NULL,
    token_version INTEGER NOT NULL,
    token BYTEA NOT NULL,
    PRIMARY KEY (tenant_id, field_id, record_id, token_strategy, token_version, token),
    FOREIGN KEY (tenant_id, field_id, record_id)
        REFERENCES efs_encrypted_records (tenant_id, field_id, record_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS efs_protected_tokens_lookup_idx
    ON efs_protected_tokens (
        tenant_id, field_id, key_id, token_strategy, token_version, token
    );

CREATE INDEX IF NOT EXISTS efs_encrypted_records_key_idx
    ON efs_encrypted_records (tenant_id, field_id, key_id, record_id);

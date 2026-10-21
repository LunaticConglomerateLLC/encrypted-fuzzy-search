package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Key-derivation scope required by a protected-index strategy. */
public enum IndexKeyScope {
  /** One index key is derived for the tenant, field, and key version. */
  TENANT_FIELD_KEY_VERSION
}

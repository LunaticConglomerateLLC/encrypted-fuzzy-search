package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Arrays;
import java.util.Objects;

/** Opaque protected-index token with its stable strategy and format identifiers. */
public record ProtectedToken(String strategyId, int formatVersion, byte[] value) {
  public ProtectedToken {
    Objects.requireNonNull(strategyId, "strategyId");
    Objects.requireNonNull(value, "value");
    if (strategyId.isBlank() || strategyId.length() > 64) {
      throw new IllegalArgumentException("strategyId must contain 1 to 64 characters");
    }
    if (formatVersion < 1) {
      throw new IllegalArgumentException("formatVersion must be positive");
    }
    if (value.length == 0 || value.length > 512) {
      throw new IllegalArgumentException("token value must contain 1 to 512 bytes");
    }
    value = value.clone();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof ProtectedToken that
            && formatVersion == that.formatVersion
            && strategyId.equals(that.strategyId)
            && Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(strategyId, formatVersion) + Arrays.hashCode(value);
  }
}

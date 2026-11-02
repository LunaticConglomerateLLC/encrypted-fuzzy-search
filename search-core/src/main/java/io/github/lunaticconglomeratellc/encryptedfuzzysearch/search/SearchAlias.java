package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Transient caller-provided known alias with stable provenance; alias text is never persisted. */
public record SearchAlias(String value, String origin, String version) {
  public static final int MAX_CODE_POINTS = 256;

  public SearchAlias {
    Objects.requireNonNull(value, "value");
    requireIdentifier(origin, "origin");
    requireIdentifier(version, "version");
    if (value.isBlank() || value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
      throw new IllegalArgumentException(
          "alias value must contain 1 to " + MAX_CODE_POINTS + " code points");
    }
  }

  @Override
  public String toString() {
    return "SearchAlias[value=<redacted>, origin=" + origin + ", version=" + version + "]";
  }

  private static void requireIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException(name + " must contain 1 to 64 characters");
    }
  }
}

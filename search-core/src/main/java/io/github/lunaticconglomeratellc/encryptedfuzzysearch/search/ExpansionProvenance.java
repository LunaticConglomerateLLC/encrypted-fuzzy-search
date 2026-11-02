package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Non-sensitive origin/version metadata for an expansion used by a search request. */
public record ExpansionProvenance(Kind kind, String origin, String version, String languageTag) {
  public enum Kind {
    KNOWN_ALIAS,
    LANGUAGE_PROVIDER
  }

  public ExpansionProvenance {
    Objects.requireNonNull(kind, "kind");
    requireIdentifier(origin, "origin");
    requireIdentifier(version, "version");
    if (kind == Kind.LANGUAGE_PROVIDER) {
      requireIdentifier(languageTag, "languageTag");
    } else if (languageTag != null) {
      throw new IllegalArgumentException("alias provenance must not include a language tag");
    }
  }

  public static ExpansionProvenance alias(String origin, String version) {
    return new ExpansionProvenance(Kind.KNOWN_ALIAS, origin, version, null);
  }

  public static ExpansionProvenance languageProvider(
      String providerId, String transformId, String languageTag) {
    return new ExpansionProvenance(Kind.LANGUAGE_PROVIDER, providerId, transformId, languageTag);
  }

  private static void requireIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must contain 1 to 256 characters");
    }
  }
}

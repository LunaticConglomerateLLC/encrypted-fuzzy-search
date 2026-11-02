package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Explicit provider and caller-selected BCP 47 language override for one field/search. */
public record LanguageProviderSelection(String providerId, String languageTag) {
  public LanguageProviderSelection {
    requireIdentifier(providerId, "providerId");
    requireIdentifier(languageTag, "languageTag");
  }

  private static void requireIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException(name + " must contain 1 to 64 characters");
    }
  }
}

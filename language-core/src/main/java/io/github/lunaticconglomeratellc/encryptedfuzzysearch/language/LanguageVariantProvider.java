package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import java.util.List;

/**
 * Provider of deterministic transliteration variants for an explicit caller-selected language.
 * Implementations must not translate meaning or infer a language without caller direction.
 */
public interface LanguageVariantProvider {
  /** Stable provider identifier used for explicit service configuration. */
  String providerId();

  /** Immutable transform version for a supported BCP 47 language tag. */
  String transformId(String languageTag);

  /**
   * Produces bounded, deterministic variants for the selected language.
   *
   * @param text input value
   * @param languageTag caller-selected BCP 47 tag; providers must reject unsupported tags
   * @return original and transliterated forms with provenance
   */
  List<TransliterationVariant> variantsForLanguage(String text, String languageTag);
}

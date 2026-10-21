package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import java.util.Objects;

/** A normalized search form with explicit provenance. */
public record TransliterationVariant(Kind kind, String transformId, String value) {
  /** The semantic kind of a generated form. */
  public enum Kind {
    /** The original text, normalized with the canonical text transform. */
    ORIGINAL,
    /** The output of the configured script transliterator. */
    LATIN,
    /** A Latin transliteration with pinyin tone marks folded for the Chinese profile. */
    TONE_FOLDED_LATIN
  }

  public TransliterationVariant {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(transformId, "transformId");
    Objects.requireNonNull(value, "value");
  }
}

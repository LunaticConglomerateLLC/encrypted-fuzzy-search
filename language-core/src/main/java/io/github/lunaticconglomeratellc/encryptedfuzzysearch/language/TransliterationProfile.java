package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;

/** Immutable, explicitly selected language and transform profile for transliteration. */
public final class TransliterationProfile {
  /** Caller metadata required for the first supported profile. */
  public static final String SIMPLIFIED_CHINESE_LANGUAGE_TAG = "zh-Hans";

  /** Stable transform ID; change this when ICU4J data or transform behavior changes. */
  public static final String SIMPLIFIED_CHINESE_TRANSFORM_ID = "efs-translit-zh-Hans-icu4j-78.3-v1";

  private static final TransliterationProfile SIMPLIFIED_CHINESE =
      new TransliterationProfile(
          SIMPLIFIED_CHINESE_LANGUAGE_TAG, SIMPLIFIED_CHINESE_TRANSFORM_ID, "Han-Latin");

  private final String languageTag;
  private final String transformId;
  private final String transliteratorId;

  private TransliterationProfile(String languageTag, String transformId, String transliteratorId) {
    this.languageTag = languageTag;
    this.transformId = transformId;
    this.transliteratorId = transliteratorId;
  }

  /**
   * Resolves an explicitly supplied language tag to a supported immutable transform profile.
   * Automatic language detection is intentionally not performed.
   *
   * @param languageTag caller-supplied BCP 47 language tag
   * @return supported transliteration profile
   * @throws NullPointerException if {@code languageTag} is null
   * @throws IllegalArgumentException if the tag is malformed or not supported
   */
  public static TransliterationProfile forLanguageTag(String languageTag) {
    Objects.requireNonNull(languageTag, "languageTag");
    String canonicalTag;
    try {
      canonicalTag = new Locale.Builder().setLanguageTag(languageTag).build().toLanguageTag();
    } catch (IllformedLocaleException exception) {
      throw new IllegalArgumentException("malformed language tag", exception);
    }
    if (SIMPLIFIED_CHINESE_LANGUAGE_TAG.equals(canonicalTag)) {
      return SIMPLIFIED_CHINESE;
    }
    throw new IllegalArgumentException("unsupported transliteration language tag: " + canonicalTag);
  }

  /** Returns the canonical caller-supplied BCP 47 language tag. */
  public String languageTag() {
    return languageTag;
  }

  /** Returns the immutable, persisted transform identifier. */
  public String transformId() {
    return transformId;
  }

  String transliteratorId() {
    return transliteratorId;
  }
}

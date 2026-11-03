package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;

/** Immutable, explicitly selected language and transform profile for transliteration. */
public final class TransliterationProfile {
  /** Caller metadata required for the first supported profile. */
  public static final String SIMPLIFIED_CHINESE_LANGUAGE_TAG = "zh-Hans";

  public static final String ARABIC_LANGUAGE_TAG = "ar-Arab";
  public static final String HINDI_LANGUAGE_TAG = "hi-Deva";
  public static final String BENGALI_LANGUAGE_TAG = "bn-Beng";
  public static final String GUJARATI_LANGUAGE_TAG = "gu-Gujr";
  public static final String PUNJABI_LANGUAGE_TAG = "pa-Guru";
  public static final String TAMIL_LANGUAGE_TAG = "ta-Taml";
  public static final String TELUGU_LANGUAGE_TAG = "te-Telu";
  public static final String KANNADA_LANGUAGE_TAG = "kn-Knda";
  public static final String MALAYALAM_LANGUAGE_TAG = "ml-Mlym";
  public static final String ODIA_LANGUAGE_TAG = "or-Orya";

  /** Stable transform ID; change this when ICU4J data or transform behavior changes. */
  public static final String SIMPLIFIED_CHINESE_TRANSFORM_ID = "efs-translit-zh-Hans-icu4j-78.3-v2";

  private static final TransliterationProfile SIMPLIFIED_CHINESE =
      new TransliterationProfile(
          SIMPLIFIED_CHINESE_LANGUAGE_TAG, SIMPLIFIED_CHINESE_TRANSFORM_ID, "Han-Latin", true);

  private static final String ICU_TRANSFORM_VERSION = "icu4j-78.3-v1";

  private final String languageTag;
  private final String transformId;
  private final String transliteratorId;
  private final boolean foldPinyinToneMarks;

  private TransliterationProfile(
      String languageTag,
      String transformId,
      String transliteratorId,
      boolean foldPinyinToneMarks) {
    this.languageTag = languageTag;
    this.transformId = transformId;
    this.transliteratorId = transliteratorId;
    this.foldPinyinToneMarks = foldPinyinToneMarks;
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
    String transliteratorId = transliteratorFor(canonicalTag);
    if (transliteratorId != null) {
      return new TransliterationProfile(
          canonicalTag,
          "efs-translit-" + canonicalTag + "-" + ICU_TRANSFORM_VERSION,
          transliteratorId,
          false);
    }
    throw new IllegalArgumentException("unsupported transliteration language tag: " + canonicalTag);
  }

  private static String transliteratorFor(String languageTag) {
    return switch (languageTag) {
      case ARABIC_LANGUAGE_TAG -> "Arabic-Latin";
      case HINDI_LANGUAGE_TAG -> "Devanagari-Latin";
      case BENGALI_LANGUAGE_TAG -> "Bengali-Latin";
      case GUJARATI_LANGUAGE_TAG -> "Gujarati-Latin";
      case PUNJABI_LANGUAGE_TAG -> "Gurmukhi-Latin";
      case TAMIL_LANGUAGE_TAG -> "Tamil-Latin";
      case TELUGU_LANGUAGE_TAG -> "Telugu-Latin";
      case KANNADA_LANGUAGE_TAG -> "Kannada-Latin";
      case MALAYALAM_LANGUAGE_TAG -> "Malayalam-Latin";
      case ODIA_LANGUAGE_TAG -> "Oriya-Latin";
      default -> null;
    };
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

  boolean foldsPinyinToneMarks() {
    return foldPinyinToneMarks;
  }
}

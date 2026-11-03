package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import com.ibm.icu.text.Transliterator;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.lucene.analysis.ja.JapaneseTokenizer;
import org.apache.lucene.analysis.ja.tokenattributes.ReadingAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Japanese reading provider using Lucene Kuromoji's IPADIC-backed morphology analysis. */
public final class KuromojiJapaneseVariantProvider implements LanguageVariantProvider {
  public static final String PROVIDER_ID = "lucene-kuromoji";
  public static final String LANGUAGE_TAG = "ja-JP";
  public static final String TRANSFORM_ID = "efs-jajp-kuro1052-icu783-v1";
  public static final int MAX_CODE_POINTS_PER_FORM = 4_096;
  public static final int MAX_VARIANT_COUNT = 4;

  private static final ThreadLocal<Transliterator> KATAKANA_TO_LATIN =
      ThreadLocal.withInitial(() -> Transliterator.getInstance("Katakana-Latin"));
  private final CanonicalTextNormalizer normalizer;

  public KuromojiJapaneseVariantProvider() {
    this(new CanonicalTextNormalizer());
  }

  KuromojiJapaneseVariantProvider(CanonicalTextNormalizer normalizer) {
    this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
  }

  @Override
  public String providerId() {
    return PROVIDER_ID;
  }

  @Override
  public String transformId(String languageTag) {
    requireSupportedLanguage(languageTag);
    return TRANSFORM_ID;
  }

  @Override
  public List<TransliterationVariant> variantsForLanguage(String text, String languageTag) {
    requireSupportedLanguage(languageTag);
    return variants(text);
  }

  /** Produces the original text, Kuromoji reading, Latin reading, and compact Latin reading. */
  public List<TransliterationVariant> variants(String text) {
    String original = normalizer.normalize(text);
    validateSize(original, "input");
    List<TransliterationVariant> variants = new ArrayList<>();
    Set<String> emitted = new LinkedHashSet<>();
    emitted.add(original);
    variants.add(
        new TransliterationVariant(
            TransliterationVariant.Kind.ORIGINAL, CanonicalTextNormalizer.TRANSFORM_ID, original));
    if (original.isEmpty()) {
      return List.copyOf(variants);
    }

    String reading = readings(original);
    validateSize(reading, "reading output");
    addDistinct(variants, emitted, TransliterationVariant.Kind.READING, reading);
    String latin = normalizer.normalize(KATAKANA_TO_LATIN.get().transliterate(reading));
    validateSize(latin, "Latin reading output");
    addDistinct(variants, emitted, TransliterationVariant.Kind.LATIN, latin);
    String compact = removeWhitespace(latin);
    validateSize(compact, "compact reading output");
    addDistinct(variants, emitted, TransliterationVariant.Kind.COMPACT_LATIN, compact);
    return List.copyOf(variants);
  }

  private static String readings(String input) {
    StringBuilder result = new StringBuilder(input.length());
    JapaneseTokenizer tokenizer =
        new JapaneseTokenizer(null, true, true, JapaneseTokenizer.Mode.NORMAL);
    tokenizer.setReader(new StringReader(input));
    ReadingAttribute reading = tokenizer.addAttribute(ReadingAttribute.class);
    CharTermAttribute surface = tokenizer.addAttribute(CharTermAttribute.class);
    try (tokenizer) {
      tokenizer.reset();
      while (tokenizer.incrementToken()) {
        String tokenReading = reading.getReading();
        result.append(tokenReading == null || tokenReading.equals("*") ? surface : tokenReading);
      }
      tokenizer.end();
    } catch (IOException exception) {
      throw new UncheckedIOException("Kuromoji failed to read Japanese text", exception);
    }
    return result.toString();
  }

  private static void addDistinct(
      List<TransliterationVariant> variants,
      Set<String> emitted,
      TransliterationVariant.Kind kind,
      String value) {
    if (!value.isEmpty() && emitted.add(value)) {
      variants.add(new TransliterationVariant(kind, TRANSFORM_ID, value));
    }
  }

  private static String removeWhitespace(String value) {
    StringBuilder compact = new StringBuilder(value.length());
    value
        .codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint))
        .forEach(compact::appendCodePoint);
    return compact.toString();
  }

  private static void validateSize(String value, String description) {
    if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS_PER_FORM) {
      throw new IllegalArgumentException(
          description + " exceeds " + MAX_CODE_POINTS_PER_FORM + " code points");
    }
  }

  private static void requireSupportedLanguage(String languageTag) {
    Objects.requireNonNull(languageTag, "languageTag");
    String canonicalTag;
    try {
      canonicalTag = new Locale.Builder().setLanguageTag(languageTag).build().toLanguageTag();
    } catch (IllformedLocaleException exception) {
      throw new IllegalArgumentException("malformed language tag", exception);
    }
    if (!LANGUAGE_TAG.equals(canonicalTag)) {
      throw new IllegalArgumentException("unsupported Japanese language tag: " + languageTag);
    }
  }
}

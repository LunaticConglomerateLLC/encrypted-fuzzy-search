package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UCharacterCategory;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.Transliterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * ICU4J-backed provider for explicitly configured and versioned transliteration profiles. ICU4J
 * emits a transliteration form, not a translation, and a Han-to-Latin pass returns one reading
 * rather than enumerating every possible pronunciation. Callers should retain the original-script
 * form and use authorized final matching to resolve ambiguous candidates.
 */
public final class Icu4jTransliterationProvider implements LanguageVariantProvider {
  public static final String PROVIDER_ID = "icu4j";
  private static final Normalizer2 ICU_NFD = Normalizer2.getNFDInstance();
  private static final Normalizer2 ICU_NFC = Normalizer2.getNFCInstance();

  /** Maximum input and generated-form length, in Unicode code points. */
  public static final int MAX_CODE_POINTS_PER_FORM = 4_096;

  /** Maximum forms produced by this profile: original, transliterated, tone-folded, and compact. */
  public static final int MAX_VARIANT_COUNT = 4;

  private final CanonicalTextNormalizer canonicalTextNormalizer;

  /** Creates a provider using the standard canonical NFC text transform. */
  public Icu4jTransliterationProvider() {
    this(new CanonicalTextNormalizer());
  }

  Icu4jTransliterationProvider(CanonicalTextNormalizer canonicalTextNormalizer) {
    this.canonicalTextNormalizer = Objects.requireNonNull(canonicalTextNormalizer);
  }

  @Override
  public String providerId() {
    return PROVIDER_ID;
  }

  @Override
  public String transformId(String languageTag) {
    return TransliterationProfile.forLanguageTag(languageTag).transformId();
  }

  @Override
  public List<TransliterationVariant> variantsForLanguage(String text, String languageTag) {
    return variants(text, TransliterationProfile.forLanguageTag(languageTag));
  }

  /**
   * Produces the original form and distinct transliterated forms for an explicit profile.
   * Transliteration is opt-in: callers should invoke this provider only for fields configured with
   * a supported profile. The original form is emitted first, followed by any distinct Latin
   * transliteration, tone-folded form, and compact tone-folded form.
   *
   * @param text input text
   * @param profile caller-selected profile
   * @return immutable forms in deterministic order
   * @throws NullPointerException if text or profile is null
   * @throws IllegalArgumentException if the text contains malformed UTF-16
   */
  public List<TransliterationVariant> variants(String text, TransliterationProfile profile) {
    Objects.requireNonNull(profile, "profile");
    String original = canonicalTextNormalizer.normalize(text);
    validateSize(original, "input");
    List<TransliterationVariant> variants = new ArrayList<>();
    Set<String> emittedValues = new LinkedHashSet<>();
    emittedValues.add(original);
    variants.add(
        new TransliterationVariant(
            TransliterationVariant.Kind.ORIGINAL, CanonicalTextNormalizer.TRANSFORM_ID, original));

    if (original.isEmpty()) {
      return List.copyOf(variants);
    }

    String latin =
        canonicalTextNormalizer.normalize(
            Transliterator.getInstance(profile.transliteratorId()).transliterate(original));
    validateSize(latin, "transliteration output");
    addDistinctVariant(
        variants, emittedValues, TransliterationVariant.Kind.LATIN, profile.transformId(), latin);

    String toneFolded = foldPinyinToneMarks(latin);
    validateSize(toneFolded, "tone-folded output");
    addDistinctVariant(
        variants,
        emittedValues,
        TransliterationVariant.Kind.TONE_FOLDED_LATIN,
        profile.transformId(),
        toneFolded);
    String compact = removeWhitespace(toneFolded);
    validateSize(compact, "compact output");
    addDistinctVariant(
        variants,
        emittedValues,
        TransliterationVariant.Kind.COMPACT_LATIN,
        profile.transformId(),
        compact);
    return List.copyOf(variants);
  }

  private static void validateSize(String value, String description) {
    if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS_PER_FORM) {
      throw new IllegalArgumentException(
          description + " exceeds " + MAX_CODE_POINTS_PER_FORM + " code points");
    }
  }

  private static void addDistinctVariant(
      List<TransliterationVariant> variants,
      Set<String> emittedValues,
      TransliterationVariant.Kind kind,
      String transformId,
      String value) {
    if (emittedValues.add(value)) {
      variants.add(new TransliterationVariant(kind, transformId, value));
    }
  }

  private static String foldPinyinToneMarks(String latin) {
    String decomposed = ICU_NFD.normalize(latin);
    StringBuilder toneFolded = new StringBuilder(decomposed.length());
    decomposed
        .codePoints()
        .filter(codePoint -> !isCombiningMark(codePoint))
        .forEach(toneFolded::appendCodePoint);
    return ICU_NFC.normalize(UCharacter.toLowerCase(Locale.ROOT, toneFolded.toString()));
  }

  private static boolean isCombiningMark(int codePoint) {
    int type = UCharacter.getType(codePoint);
    return type == UCharacterCategory.NON_SPACING_MARK
        || type == UCharacterCategory.COMBINING_SPACING_MARK
        || type == UCharacterCategory.ENCLOSING_MARK;
  }

  private static String removeWhitespace(String value) {
    StringBuilder compact = new StringBuilder(value.length());
    value
        .codePoints()
        .filter(codePoint -> !UCharacter.isUWhiteSpace(codePoint))
        .forEach(compact::appendCodePoint);
    return compact.toString();
  }
}

package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import java.util.List;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class Icu4jTransliterationProviderTest {
  private Icu4jTransliterationProvider provider;
  private TransliterationProfile simplifiedChinese;

  @BeforeMethod
  public void setUp() {
    provider = new Icu4jTransliterationProvider();
    simplifiedChinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
  }

  @Test
  public void preservesOriginalAndReturnsDistinctRomanizedForms() {
    List<TransliterationVariant> variants = provider.variants("你好", simplifiedChinese);

    assertEquals(variants.size(), 3);
    assertEquals(variants.get(0).kind(), TransliterationVariant.Kind.ORIGINAL);
    assertEquals(variants.get(0).transformId(), CanonicalTextNormalizer.TRANSFORM_ID);
    assertEquals(variants.get(0).value(), "你好");
    assertEquals(variants.get(1).kind(), TransliterationVariant.Kind.LATIN);
    assertEquals(variants.get(1).transformId(), simplifiedChinese.transformId());
    assertEquals(variants.get(1).value(), "nǐ hǎo");
    assertEquals(variants.get(2).kind(), TransliterationVariant.Kind.TONE_FOLDED_LATIN);
    assertEquals(variants.get(2).transformId(), simplifiedChinese.transformId());
    assertEquals(variants.get(2).value(), "ni hao");
    assertThrows(UnsupportedOperationException.class, () -> variants.add(variants.get(0)));
  }

  @Test
  public void foldsToneMarksFromUserSuppliedPinyinQuery() {
    List<TransliterationVariant> variants = provider.variants("nǐ hǎo", simplifiedChinese);

    assertEquals(variants.size(), 2);
    assertEquals(variants.get(0).kind(), TransliterationVariant.Kind.ORIGINAL);
    assertEquals(variants.get(0).value(), "nǐ hǎo");
    assertEquals(variants.get(1).kind(), TransliterationVariant.Kind.TONE_FOLDED_LATIN);
    assertEquals(variants.get(1).value(), "ni hao");
  }

  @Test
  public void deduplicatesUnchangedLatinFormsAndKeepsOriginalFirst() {
    List<TransliterationVariant> variants = provider.variants("ni hao", simplifiedChinese);

    assertEquals(variants.size(), 1);
    assertEquals(variants.get(0).kind(), TransliterationVariant.Kind.ORIGINAL);
    assertEquals(variants.get(0).value(), "ni hao");
  }

  @Test
  public void emitsOnlyOriginalForEmptyInput() {
    List<TransliterationVariant> variants = provider.variants("", simplifiedChinese);

    assertEquals(variants.size(), 1);
    assertEquals(variants.get(0).kind(), TransliterationVariant.Kind.ORIGINAL);
    assertEquals(variants.get(0).value(), "");
  }

  @Test
  public void rejectsNullsAndMalformedUtf16() {
    assertThrows(NullPointerException.class, () -> provider.variants(null, simplifiedChinese));
    assertThrows(NullPointerException.class, () -> provider.variants("你好", null));
    assertThrows(
        IllegalArgumentException.class, () -> provider.variants("bad\uD800", simplifiedChinese));
    assertThrows(NullPointerException.class, () -> new Icu4jTransliterationProvider(null));
  }

  @Test
  public void rejectsOversizedInputAndGeneratedVariantsInsteadOfTruncating() {
    String oversizedInput = "a".repeat(Icu4jTransliterationProvider.MAX_CODE_POINTS_PER_FORM + 1);
    assertThrows(
        IllegalArgumentException.class, () -> provider.variants(oversizedInput, simplifiedChinese));

    String transliterationExpandsPastLimit =
        "中".repeat(Icu4jTransliterationProvider.MAX_CODE_POINTS_PER_FORM);
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.variants(transliterationExpandsPastLimit, simplifiedChinese));
  }
}

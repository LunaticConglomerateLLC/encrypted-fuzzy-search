package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.List;
import org.testng.annotations.Test;

public class KuromojiJapaneseVariantProviderTest {
  @Test
  public void producesOriginalDictionaryReadingLatinAndCompactForms() {
    KuromojiJapaneseVariantProvider provider = new KuromojiJapaneseVariantProvider();

    List<TransliterationVariant> variants = provider.variants("東京");

    assertEquals(variants.get(0).value(), "東京");
    assertTrue(
        variants.stream()
            .anyMatch(
                variant ->
                    variant.kind() == TransliterationVariant.Kind.READING
                        && variant.value().equals("トウキョウ")));
    assertTrue(
        variants.stream()
            .anyMatch(
                variant ->
                    variant.kind() == TransliterationVariant.Kind.LATIN
                        && variant.value().equals("toukyou")),
        variants.toString());
    assertTrue(variants.size() <= KuromojiJapaneseVariantProvider.MAX_VARIANT_COUNT);
    assertTrue(
        variants.stream()
            .filter(variant -> variant.kind() != TransliterationVariant.Kind.ORIGINAL)
            .allMatch(variant -> variant.transformId().equals(provider.transformId("ja-JP"))));
  }

  @Test
  public void exposesOnlyTheExplicitJapaneseProfile() {
    KuromojiJapaneseVariantProvider provider = new KuromojiJapaneseVariantProvider();
    assertEquals(provider.providerId(), "lucene-kuromoji");
    assertEquals(provider.transformId("ja-JP"), "efs-jajp-kuro1052-icu783-v1");
    assertEquals(provider.transformId("ja-jp"), provider.transformId("ja-JP"));
    assertThrows(NullPointerException.class, () -> provider.transformId(null));
    assertThrows(IllegalArgumentException.class, () -> provider.transformId("ja"));
    assertThrows(IllegalArgumentException.class, () -> provider.variantsForLanguage("東京", "en"));
  }

  @Test
  public void rejectsMalformedNullAndOversizedInputsAndKeepsEmptyInputBounded() {
    KuromojiJapaneseVariantProvider provider = new KuromojiJapaneseVariantProvider();
    assertThrows(NullPointerException.class, () -> provider.variants(null));
    assertThrows(IllegalArgumentException.class, () -> provider.variants("bad\uD800"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            provider.variants(
                "あ".repeat(KuromojiJapaneseVariantProvider.MAX_CODE_POINTS_PER_FORM + 1)));
    assertEquals(provider.variants("").size(), 1);
  }
}

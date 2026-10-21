package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import org.testng.annotations.Test;

public class TransliterationProfileTest {
  @Test
  public void resolvesCanonicalSimplifiedChineseLanguageTagAndStableVersion() {
    TransliterationProfile profile = TransliterationProfile.forLanguageTag("zh-hans");

    assertEquals(profile.languageTag(), "zh-Hans");
    assertEquals(profile.transformId(), "efs-translit-zh-Hans-icu4j-78.3-v2");
    assertSame(
        TransliterationProfile.forLanguageTag("zh-Hans"),
        TransliterationProfile.forLanguageTag("zh-hans"));
  }

  @Test
  public void requiresExplicitSupportedLanguageTag() {
    assertThrows(NullPointerException.class, () -> TransliterationProfile.forLanguageTag(null));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("en-US"));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("zh-Hant"));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("zh_Hans"));
  }

  @Test
  public void variantRequiresAllFields() {
    assertNotNull(
        new TransliterationVariant(TransliterationVariant.Kind.ORIGINAL, "canonical-v1", "你好"));
    assertThrows(
        NullPointerException.class, () -> new TransliterationVariant(null, "canonical-v1", "你好"));
    assertThrows(
        NullPointerException.class,
        () -> new TransliterationVariant(TransliterationVariant.Kind.ORIGINAL, null, "你好"));
    assertThrows(
        NullPointerException.class,
        () ->
            new TransliterationVariant(TransliterationVariant.Kind.ORIGINAL, "canonical-v1", null));
  }
}

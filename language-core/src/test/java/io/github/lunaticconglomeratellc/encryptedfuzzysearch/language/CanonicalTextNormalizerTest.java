package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class CanonicalTextNormalizerTest {
  private CanonicalTextNormalizer normalizer;

  @BeforeMethod
  public void setUp() {
    normalizer = new CanonicalTextNormalizer();
  }

  @Test
  public void exposesStableTransformIdentifier() {
    assertEquals(CanonicalTextNormalizer.TRANSFORM_ID, "efs-text-nfc-utf8-v1");
  }

  @Test
  public void matchesEverySharedCompatibilityVector() throws IOException {
    InputStream vectors =
        CanonicalTextNormalizerTest.class.getResourceAsStream("/canonical-text-v1.tsv");
    if (vectors == null) {
      throw new AssertionError("canonical-text-v1.tsv resource is missing");
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(vectors, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] columns = line.split("\\t", -1);
        if (columns.length != 3) {
          throw new AssertionError("invalid canonical text vector: " + line);
        }
        String input = fromCodePoints(columns[0]);
        String expectedText = fromCodePoints(columns[1]);

        assertEquals(normalizer.normalize(input), expectedText, line);
        String expectedUtf8 = "-".equals(columns[2]) ? "" : columns[2];
        assertEquals(HexFormat.of().formatHex(normalizer.encode(input)), expectedUtf8, line);
      }
    }
  }

  @Test
  public void preservesCaseSpacesAndCompatibilityCharacters() {
    assertEquals(normalizer.normalize("A a ﬁ"), "A a ﬁ");
  }

  @Test
  public void rejectsNullAndMalformedUtf16() {
    assertThrows(NullPointerException.class, () -> normalizer.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("bad\uD800"));
    assertThrows(IllegalArgumentException.class, () -> normalizer.encode("bad\uDC00"));
  }

  private static String fromCodePoints(String sequence) {
    if ("-".equals(sequence)) {
      return "";
    }
    StringBuilder value = new StringBuilder();
    for (String codePoint : sequence.split(" ")) {
      value.appendCodePoint(Integer.parseInt(codePoint, 16));
    }
    return value.toString();
  }
}

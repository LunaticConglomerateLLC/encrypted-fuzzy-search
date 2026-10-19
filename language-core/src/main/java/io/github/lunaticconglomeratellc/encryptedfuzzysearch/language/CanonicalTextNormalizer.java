package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;

/** Canonicalizes text using the versioned EFS NFC + UTF-8 transform. */
public final class CanonicalTextNormalizer {
  /** Stable identifier to persist alongside tokens created from canonical text. */
  public static final String TRANSFORM_ID = "efs-text-nfc-utf8-v1";

  /** Creates the stateless normalizer. */
  public CanonicalTextNormalizer() {}

  /**
   * Returns Unicode NFC text without case folding, trimming, punctuation removal, or
   * transliteration.
   *
   * @param text source text
   * @return NFC-normalized text
   * @throws NullPointerException if text is null
   * @throws IllegalArgumentException if text contains an unpaired UTF-16 surrogate
   */
  public String normalize(String text) {
    Objects.requireNonNull(text, "text");
    validateUnicodeScalarValues(text);
    return Normalizer.normalize(text, Normalizer.Form.NFC);
  }

  /** Returns the UTF-8 encoding of {@link #normalize(String)}. */
  public byte[] encode(String text) {
    return normalize(text).getBytes(StandardCharsets.UTF_8);
  }

  private static void validateUnicodeScalarValues(String text) {
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
          throw new IllegalArgumentException("text contains an unpaired UTF-16 surrogate");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("text contains an unpaired UTF-16 surrogate");
      }
    }
  }
}

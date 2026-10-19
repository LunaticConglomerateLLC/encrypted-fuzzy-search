package io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import java.util.Arrays;
import java.util.HexFormat;
import org.testng.annotations.Test;

public class HkdfSha256Test {
  @Test
  public void matchesRfc5869Sha256TestCaseOne() throws Exception {
    byte[] inputKeyMaterial = repeatedByte((byte) 0x0b, 22);
    byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
    byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");

    byte[] output = HkdfSha256.derive(inputKeyMaterial, salt, info, 42);

    assertEquals(
        HexFormat.of().formatHex(output),
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
  }

  @Test
  public void usesZeroSaltWhenEmptyAndEnforcesOutputLimit() throws Exception {
    byte[] inputKeyMaterial = new byte[32];
    byte[] empty = new byte[0];

    assertEquals(HkdfSha256.derive(inputKeyMaterial, empty, empty, 32).length, 32);
    assertThrows(
        IllegalArgumentException.class, () -> HkdfSha256.derive(inputKeyMaterial, empty, empty, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> HkdfSha256.derive(inputKeyMaterial, empty, empty, 255 * 32 + 1));
  }

  private static byte[] repeatedByte(byte value, int length) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }
}

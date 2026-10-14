package com.tarimatwasi.quipu.shared.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNamesTest {

  private static String sanitized(String name) {
    return FileNames.sanitized(name, "fallback.pdf");
  }

  @Test
  void sanitized_dropsTheDirectoriesOfEitherSeparator() {
    assertThat(sanitized("..\\..\\windows\\contrato.pdf")).isEqualTo("contrato.pdf");
    assertThat(sanitized("../../etc/passwd")).isEqualTo("passwd");
    assertThat(sanitized("a/b\\c.png")).isEqualTo("c.png");
  }

  @Test
  void sanitized_dropsControlAndFormatCharacters() {
    assertThat(sanitized("a\u0000b\nc.pdf")).isEqualTo("abc.pdf");
    assertThat(sanitized("cont\u202Efdp.exe")).isEqualTo("contfdp.exe");
    assertThat(sanitized("a\u200Bb\uFEFF.pdf")).isEqualTo("ab.pdf");
  }

  @Test
  void sanitized_keepsAnOrdinaryNameWithoutTheSpacesAtItsEdges() {
    assertThat(sanitized("  Contrato \u00D1and\u00FA.pdf "))
        .isEqualTo("Contrato \u00D1and\u00FA.pdf");
  }

  @Test
  void sanitized_neverReturnsAnEmptyNameOrOnlyDots() {
    assertThat(sanitized("")).isEqualTo("fallback.pdf");
    assertThat(sanitized("..")).isEqualTo("fallback.pdf");
    assertThat(sanitized("dir/")).isEqualTo("fallback.pdf");
    assertThat(sanitized("\u202E")).isEqualTo("fallback.pdf");
  }

  @Test
  void sanitized_cutsTheNameAt255Characters() {
    assertThat(sanitized("x".repeat(300))).hasSize(255);
  }
}

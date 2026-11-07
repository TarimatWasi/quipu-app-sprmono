package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** TAR-207: the text of a published consent version cannot change without failing the startup. */
class ConsentTextsTest {

  private static final String CURRENT = "2026-11";
  private static final String HASH =
      "6a2552ab771870557d5b61559d14eb027501df53a97f2674bb372d4bcf3ff501";

  /** The manifest in the repository matches the text and names the version in force. */
  @Test
  void theManifestOfTheRepositoryHolds() {
    assertThatCode(() -> ConsentTexts.verify(CURRENT)).doesNotThrowAnyException();
    assertThat(ConsentTexts.manifest()).containsOnlyKeys(CURRENT).containsEntry(CURRENT, HASH);
    assertThat(ConsentTexts.textVersions()).containsExactly(CURRENT);
    assertThatCode(() -> new ConsentTexts(() -> CURRENT)).doesNotThrowAnyException();
  }

  /** TAR-168, TAR-246: the version in force is the definitive text approved by the lawyer. */
  @Test
  void theConfiguredVersionIsTheDefinitiveText() throws IOException {
    String configured =
        String.valueOf(
            new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .get(0)
                .getProperty("app.consent.version"));

    assertThat(configured).isEqualTo(CURRENT);
    String text = new ConsentTexts(() -> configured).currentText();
    assertThat(ConsentTexts.sha256(text)).isEqualTo(HASH);
    assertThat(text)
        .doesNotContainIgnoringCase("BORRADOR")
        .contains("las ven un administrador y tú mismo, cada uno mediante un enlace");
  }

  @Test
  void aChangedTextFailsWithoutShowingIt() {
    String text = ConsentTexts.textOf(CURRENT);

    assertThatThrownBy(
            () -> ConsentTexts.verify(CURRENT, Map.of(CURRENT, "0".repeat(64)), Set.of(CURRENT)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CURRENT)
        .hasMessageContaining("SHA-256")
        .hasMessageNotContaining(text.substring(0, 20));
  }

  @Test
  void aVersionOfTheManifestWithoutItsTextFails() {
    assertThatThrownBy(
            () ->
                ConsentTexts.verify(
                    CURRENT, Map.of(CURRENT, HASH, "no-existe", HASH), Set.of(CURRENT)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-existe");
  }

  @Test
  void theVersionInForceOutsideTheManifestFails() {
    assertThatThrownBy(() -> ConsentTexts.verify(CURRENT, Map.of(), Set.of(CURRENT)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CURRENT);
  }

  @Test
  void aTextOutsideTheManifestFails() {
    assertThatThrownBy(
            () -> ConsentTexts.verify(CURRENT, Map.of(CURRENT, HASH), Set.of(CURRENT, "otra")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("otra");
  }
}

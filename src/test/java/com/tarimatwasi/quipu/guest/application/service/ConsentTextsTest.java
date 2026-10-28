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

  private static final String CURRENT = "borrador-2026-10";
  private static final String HASH =
      "23795aa537f750434b48694ec522d18af4005959801e27996960f5227ff2f9d8";
  private static final String DEFINITIVE_HASH =
      "0348877596af3c0308425f2cf29c7cf34c1af7a3b77951ada58edc7f46f926c1";

  /** The manifest in the repository matches every text and names the version in force. */
  @Test
  void theManifestOfTheRepositoryHolds() {
    assertThatCode(() -> ConsentTexts.verify(CURRENT)).doesNotThrowAnyException();
    assertThat(ConsentTexts.manifest()).containsEntry(CURRENT, HASH);
    assertThat(ConsentTexts.textVersions()).contains(CURRENT);
    assertThatCode(() -> new ConsentTexts(CURRENT)).doesNotThrowAnyException();
  }

  /** TAR-168: the version in force is the definitive text approved by the lawyer (D-62). */
  @Test
  void theConfiguredVersionIsTheDefinitiveText() throws IOException {
    String configured =
        String.valueOf(
            new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .get(0)
                .getProperty("app.consent.version"));

    assertThat(configured).isEqualTo("2026-10");
    assertThatCode(() -> ConsentTexts.verify(configured)).doesNotThrowAnyException();
    String text = new ConsentTexts(configured).currentText();
    assertThat(ConsentTexts.manifest()).containsEntry(configured, DEFINITIVE_HASH);
    assertThat(ConsentTexts.sha256(text)).isEqualTo(DEFINITIVE_HASH);
    assertThat(text).doesNotContainIgnoringCase("BORRADOR");
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

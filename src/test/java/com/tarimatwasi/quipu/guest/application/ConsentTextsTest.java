package com.tarimatwasi.quipu.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** TAR-207: the text of a published consent version cannot change without failing the startup. */
class ConsentTextsTest {

  private static final String CURRENT = "borrador-2026-10";
  private static final String HASH =
      "23795aa537f750434b48694ec522d18af4005959801e27996960f5227ff2f9d8";

  /** The manifest in the repository matches every text and names the version in force. */
  @Test
  void theManifestOfTheRepositoryHolds() {
    assertThatCode(() -> ConsentTexts.verify(CURRENT)).doesNotThrowAnyException();
    assertThat(ConsentTexts.manifest()).containsEntry(CURRENT, HASH);
    assertThat(ConsentTexts.textVersions()).contains(CURRENT);
    assertThatCode(() -> new ConsentTexts(CURRENT)).doesNotThrowAnyException();
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

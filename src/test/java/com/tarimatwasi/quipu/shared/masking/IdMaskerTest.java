package com.tarimatwasi.quipu.shared.masking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class IdMaskerTest {

  private static final String KEY = "a-test-key-with-more-than-thirty-two-characters";

  private final IdMasker masker = new IdMasker(new IdMaskProperties(KEY));

  @Test
  void aMaskedIdComesBackAsTheSameId() {
    for (long id : new long[] {0, 1, 2, 42, 1_000_000, Long.MAX_VALUE}) {
      for (IdKind kind : IdKind.values()) {
        assertThat(masker.unmask(kind, masker.mask(kind, id))).isEqualTo(id);
      }
    }
  }

  @Test
  void manyRandomIdsComeBackAsTheSameId() {
    var random = ThreadLocalRandom.current();
    for (int i = 0; i < 500; i++) {
      long id = random.nextLong(Long.MAX_VALUE);
      assertThat(masker.unmask(IdKind.GUEST, masker.mask(IdKind.GUEST, id))).isEqualTo(id);
    }
  }

  @Test
  void theSameIdMasksTheSameWayEveryTime() {
    assertThat(masker.mask(IdKind.ENVIRONMENT, 7)).isEqualTo(masker.mask(IdKind.ENVIRONMENT, 7));
  }

  @Test
  void consecutiveIdsDoNotLookConsecutive() {
    var masked = new HashSet<UUID>();
    UUID previous = masker.mask(IdKind.EXPENSE, 1);
    masked.add(previous);
    for (long id = 2; id <= 200; id++) {
      UUID current = masker.mask(IdKind.EXPENSE, id);
      assertThat(current.getMostSignificantBits()).isNotEqualTo(previous.getMostSignificantBits());
      masked.add(current);
      previous = current;
    }
    assertThat(masked).hasSize(200);
  }

  @Test
  void theSameIdOfTwoKindsMasksToDifferentValues() {
    assertThat(masker.mask(IdKind.ENVIRONMENT, 5)).isNotEqualTo(masker.mask(IdKind.GUEST, 5));
  }

  @Test
  void aMaskedIdOfOneKindIsNotAcceptedAsAnother() {
    UUID environment = masker.mask(IdKind.ENVIRONMENT, 5);

    assertThatThrownBy(() -> masker.unmask(IdKind.GUEST, environment))
        .isInstanceOf(UnknownMaskedIdException.class)
        .extracting(e -> ((UnknownMaskedIdException) e).kind())
        .isEqualTo(IdKind.GUEST);
  }

  @Test
  void aUuidThatWasNeverIssuedIsNotAccepted() {
    for (int i = 0; i < 200; i++) {
      UUID random = UUID.randomUUID();
      assertThatThrownBy(() -> masker.unmask(IdKind.ENVIRONMENT, random))
          .isInstanceOf(UnknownMaskedIdException.class);
    }
  }

  @Test
  void aTamperedValueIsNotAccepted() {
    UUID issued = masker.mask(IdKind.GUEST, 99);
    var tampered = new UUID(issued.getMostSignificantBits() ^ 1L, issued.getLeastSignificantBits());

    assertThatThrownBy(() -> masker.unmask(IdKind.GUEST, tampered))
        .isInstanceOf(UnknownMaskedIdException.class);
  }

  @Test
  void anotherKeyMasksDifferentlyAndDoesNotAcceptTheValue() {
    var other = new IdMasker(new IdMaskProperties("another-key-with-more-than-thirty-two-chars"));
    UUID issued = masker.mask(IdKind.ENVIRONMENT, 3);

    assertThat(other.mask(IdKind.ENVIRONMENT, 3)).isNotEqualTo(issued);
    assertThatThrownBy(() -> other.unmask(IdKind.ENVIRONMENT, issued))
        .isInstanceOf(UnknownMaskedIdException.class);
  }

  @Test
  void aNegativeIdCannotBeMasked() {
    assertThatThrownBy(() -> masker.mask(IdKind.GUEST, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aShortKeyIsRefusedAtStartup() {
    assertThatThrownBy(() -> new IdMaskProperties("too-short"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

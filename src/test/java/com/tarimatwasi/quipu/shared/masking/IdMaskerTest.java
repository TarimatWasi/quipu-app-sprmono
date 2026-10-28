package com.tarimatwasi.quipu.shared.masking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IdMaskerTest {

  private static final String KEY = "a-test-key-with-more-than-thirty-two-characters";
  private static final String SESSION = "AAECAwQFBgcICQoLDA0ODw";
  private static final String OTHER_SESSION = "8PHy8_T19vf4-fr7_P3-_w";

  /** The session of the "request"; null as outside one. */
  private final AtomicReference<@Nullable MaskSession> current = new AtomicReference<>();

  private final IdMasker masker =
      new IdMasker(new IdMaskProperties(KEY), () -> Optional.ofNullable(current.get()));
  private final MaskSession session = masker.session(SESSION);

  @Test
  void aMaskedIdComesBackAsTheSameId() {
    for (long id : new long[] {0, 1, 2, 42, 1_000_000, Long.MAX_VALUE}) {
      for (IdKind kind : IdKind.values()) {
        assertThat(session.unmask(kind, session.mask(kind, id))).isEqualTo(id);
      }
    }
  }

  @Test
  void manyRandomIdsComeBackAsTheSameId() {
    var random = ThreadLocalRandom.current();
    for (int i = 0; i < 500; i++) {
      long id = random.nextLong(Long.MAX_VALUE);
      assertThat(session.unmask(IdKind.GUEST, session.mask(IdKind.GUEST, id))).isEqualTo(id);
    }
  }

  @Test
  void theSameIdMasksTheSameWayEveryTimeInOneSession() {
    assertThat(session.mask(IdKind.ENVIRONMENT, 7))
        .isEqualTo(session.mask(IdKind.ENVIRONMENT, 7))
        .isEqualTo(masker.session(SESSION).mask(IdKind.ENVIRONMENT, 7));
  }

  @Test
  void theSameIdMasksDifferentlyInTwoSessions() {
    MaskSession other = masker.session(OTHER_SESSION);

    for (long id : new long[] {0, 1, 7, 1_000_000}) {
      assertThat(other.mask(IdKind.ENVIRONMENT, id))
          .isNotEqualTo(session.mask(IdKind.ENVIRONMENT, id));
    }
  }

  @Test
  void anIdMaskedInOneSessionIsUnknownInAnother() {
    MaskSession other = masker.session(OTHER_SESSION);

    for (long id : new long[] {0, 1, 7, 1_000_000}) {
      UUID issued = session.mask(IdKind.GUEST, id);
      assertThatThrownBy(() -> other.unmask(IdKind.GUEST, issued))
          .isInstanceOf(UnknownMaskedIdException.class)
          .extracting(e -> ((UnknownMaskedIdException) e).kind())
          .isEqualTo(IdKind.GUEST);
    }
  }

  @Test
  void theMaskerUsesTheSessionOfTheRequest() {
    current.set(session);
    UUID issued = masker.mask(IdKind.STAY, 12);

    assertThat(issued).isEqualTo(session.mask(IdKind.STAY, 12));
    assertThat(masker.unmask(IdKind.STAY, issued)).isEqualTo(12);

    current.set(masker.session(OTHER_SESSION));
    assertThatThrownBy(() -> masker.unmask(IdKind.STAY, issued))
        .isInstanceOf(UnknownMaskedIdException.class);
  }

  @Test
  void withoutASessionTheMaskerFailsClosed() {
    UUID issued = session.mask(IdKind.GUEST, 5);

    assertThatThrownBy(() -> masker.mask(IdKind.GUEST, 5))
        .isInstanceOf(NoMaskSessionException.class);
    assertThatThrownBy(() -> masker.unmask(IdKind.GUEST, issued))
        .isInstanceOf(NoMaskSessionException.class);
  }

  @Test
  void onlyACanonicalSessionIdOfSixteenBytesIsASession() {
    for (String notASession :
        new String[] {
          "",
          "short",
          "AAECAwQFBgcICQoLDA0ODw==",
          "AAECAwQFBgcICQoLDA0ODx",
          "AAECAwQFBgcICQoLDA0ODwAA",
          "AAECAwQFBgcICQoLDA0OD+",
          "not base64 at all!!!!!"
        }) {
      assertThatThrownBy(() -> masker.session(notASession))
          .as(notASession)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(notASession.isEmpty() ? "\u0000" : notASession);
    }
  }

  @Test
  void theKeyOfASessionIsNeverShown() {
    assertThat(session.toString()).doesNotContain(KEY).doesNotContain(SESSION).contains("hidden");
  }

  @Test
  void consecutiveIdsDoNotLookConsecutive() {
    var masked = new HashSet<UUID>();
    UUID previous = session.mask(IdKind.EXPENSE, 1);
    masked.add(previous);
    for (long id = 2; id <= 200; id++) {
      UUID current = session.mask(IdKind.EXPENSE, id);
      assertThat(current.getMostSignificantBits()).isNotEqualTo(previous.getMostSignificantBits());
      masked.add(current);
      previous = current;
    }
    assertThat(masked).hasSize(200);
  }

  @Test
  void theSameIdOfTwoKindsMasksToDifferentValues() {
    assertThat(session.mask(IdKind.ENVIRONMENT, 5)).isNotEqualTo(session.mask(IdKind.GUEST, 5));
  }

  @Test
  void aMaskedIdOfAnyKindIsNotAcceptedAsAnyOtherKind() {
    for (IdKind issued : IdKind.values()) {
      UUID masked = session.mask(issued, 77);
      for (IdKind asked : IdKind.values()) {
        if (asked != issued) {
          assertThatThrownBy(() -> session.unmask(asked, masked))
              .as("%s asked as %s", issued, asked)
              .isInstanceOf(UnknownMaskedIdException.class);
        }
      }
    }
  }

  @Test
  void aUuidThatWasNeverIssuedIsNotAccepted() {
    for (int i = 0; i < 200; i++) {
      UUID random = UUID.randomUUID();
      assertThatThrownBy(() -> session.unmask(IdKind.ENVIRONMENT, random))
          .isInstanceOf(UnknownMaskedIdException.class);
    }
  }

  @Test
  void aTamperedValueIsNotAccepted() {
    UUID issued = session.mask(IdKind.GUEST, 99);
    var tampered = new UUID(issued.getMostSignificantBits() ^ 1L, issued.getLeastSignificantBits());

    assertThatThrownBy(() -> session.unmask(IdKind.GUEST, tampered))
        .isInstanceOf(UnknownMaskedIdException.class);
  }

  @Test
  void anotherKeyMasksTheSameSessionDifferentlyAndDoesNotAcceptTheValue() {
    var other =
        new IdMasker(
                new IdMaskProperties("another-key-with-more-than-thirty-two-chars"),
                Optional::empty)
            .session(SESSION);
    UUID issued = session.mask(IdKind.ENVIRONMENT, 3);

    assertThat(other.mask(IdKind.ENVIRONMENT, 3)).isNotEqualTo(issued);
    assertThatThrownBy(() -> other.unmask(IdKind.ENVIRONMENT, issued))
        .isInstanceOf(UnknownMaskedIdException.class);
  }

  @Test
  void aNegativeIdCannotBeMasked() {
    assertThatThrownBy(() -> session.mask(IdKind.GUEST, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aShortKeyIsRefusedAtStartup() {
    assertThatThrownBy(() -> new IdMaskProperties("too-short"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

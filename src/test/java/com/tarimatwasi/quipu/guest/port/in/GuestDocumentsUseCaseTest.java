package com.tarimatwasi.quipu.guest.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase.DocumentActionRequest;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TAR-187 (RN-37): the rules of the reason of a deletion or replacement. */
class GuestDocumentsUseCaseTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-24");

  private static DocumentActionRequest request(
      @Nullable String reason,
      @Nullable String requestedAt,
      @Nullable String channel,
      @Nullable String note) {
    return new DocumentActionRequest(reason, requestedAt, channel, note);
  }

  @Test
  void anArcoRequestCarriesItsDateAndChannel() {
    Why why = request("ARCO_REQUEST", "2026-10-24", "EMAIL", "  ticket 12 ").why(TODAY);

    assertThat(why).isEqualTo(new Why(Reason.ARCO_REQUEST, TODAY, Channel.EMAIL, "ticket 12"));
  }

  @Test
  void anUploadErrorCarriesNeitherAndABlankFieldCountsAsAbsent() {
    Why why = request(" UPLOAD_ERROR ", "", "  ", "\t").why(TODAY);

    assertThat(why).isEqualTo(new Why(Reason.UPLOAD_ERROR, null, null, null));
  }

  @Test
  void aNoteOfExactly500CharactersIsTaken() {
    request("UPLOAD_ERROR", null, null, "x".repeat(500)).check(TODAY);
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "null, null, null, reason",
        "' ', null, null, reason",
        "DELETE, null, null, reason",
        "arco_request, 2026-10-01, EMAIL, reason",
        "ARCO_REQUEST, null, EMAIL, requestedAt",
        "ARCO_REQUEST, 2026-10-01, null, requestChannel",
        "ARCO_REQUEST, 2026-10-25, EMAIL, requestedAt",
        "ARCO_REQUEST, 24/10/2026, EMAIL, requestedAt",
        "ARCO_REQUEST, 2026-10-01, FAX, requestChannel",
        "UPLOAD_ERROR, 2026-10-01, null, requestedAt",
        "UPLOAD_ERROR, null, EMAIL, requestChannel"
      })
  void eachBrokenRuleNamesItsField(
      @Nullable String reason,
      @Nullable String requestedAt,
      @Nullable String channel,
      String field) {
    assertThatThrownBy(() -> request(reason, requestedAt, channel, null).check(TODAY))
        .isInstanceOfSatisfying(
            InvalidDocumentActionException.class, e -> assertThat(e.field()).isEqualTo(field));
  }

  @Test
  void aNoteOver500CharactersIsRefused() {
    assertThatThrownBy(() -> request("UPLOAD_ERROR", null, null, "x".repeat(501)).check(TODAY))
        .isInstanceOfSatisfying(
            InvalidDocumentActionException.class, e -> assertThat(e.field()).isEqualTo("note"));
  }
}

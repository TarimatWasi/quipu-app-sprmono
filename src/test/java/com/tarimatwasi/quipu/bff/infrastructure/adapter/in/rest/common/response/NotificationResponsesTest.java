package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.NotificationResponses.NotificationResponse;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.NotificationPage;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.NotificationView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationResponsesTest {

  private static final IdMasker BASE =
      new IdMasker(
          new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"), Optional::empty);
  private static final MaskSession SESSION = BASE.session("AAECAwQFBgcICQoLDA0ODw");
  private static final IdMasker IDS =
      new IdMasker(
          new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"),
          () -> Optional.of(SESSION));

  /** Each target opens its own kind of resource, masked as such. */
  @ParameterizedTest
  @EnumSource(Target.class)
  void theTargetIsMaskedAsItsKindOfResource(Target target) {
    NotificationResponse response = only(NotificationResponses.of(page(view(target, null)), IDS));

    var opened = Objects.requireNonNull(response.target());
    assertThat(opened.kind()).isEqualTo(target.name());
    assertThat(opened.id()).isEqualTo(SESSION.mask(kindOf(target), 9));
  }

  @Test
  void theGuestIsNamedWithTheNameReadNowAndOtherwiseIsAGuest() {
    NotificationView named = view(Target.GUEST, 5L);
    NotificationView unknown = view(Target.GUEST, 6L);
    NotificationView none = view(Target.GUEST, null);

    var content =
        NotificationResponses.of(page(named, unknown, none), IDS, Map.of(5L, "Ana Quispe"))
            .content();

    assertThat(content.get(0).body()).isEqualTo("Ana Quispe terminó su registro.");
    assertThat(content.get(1).body()).isEqualTo("Un huésped terminó su registro.");
    assertThat(content.get(2).body()).isEqualTo("Un huésped terminó su registro.");
  }

  private static IdKind kindOf(Target target) {
    return switch (target) {
      case PAYMENT -> IdKind.PAYMENT;
      case GUEST -> IdKind.GUEST;
      case CONTRACT -> IdKind.CONTRACT;
      case FINE -> IdKind.FINE;
      case REFUND -> IdKind.REFUND;
      case DOCUMENT -> IdKind.GUEST_DOCUMENT;
    };
  }

  private static NotificationResponse only(
      NotificationResponses.PagedNotificationsResponse response) {
    assertThat(response.content()).hasSize(1);
    return response.content().getFirst();
  }

  private static NotificationPage page(NotificationView... views) {
    return new NotificationPage(new CursorPage<>(List.of(views), null), views.length);
  }

  private static NotificationView view(Target target, @Nullable Long guestId) {
    return new NotificationView(
        1L, Kind.ONBOARDING_COMPLETED, target, 9L, guestId, Map.of(), Instant.EPOCH, null);
  }
}

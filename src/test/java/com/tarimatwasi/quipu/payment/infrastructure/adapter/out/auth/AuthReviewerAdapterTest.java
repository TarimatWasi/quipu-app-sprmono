package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorStatus;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorView;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class AuthReviewerAdapterTest {

  private final ManageAdministratorsUseCase administrators =
      mock(ManageAdministratorsUseCase.class);
  private final AuthReviewerAdapter adapter = new AuthReviewerAdapter(administrators);

  /** Only the ones asked for; one that never wrote its name is named by its email. */
  @Test
  void theReviewersAreNamedByTheirNameOrElseTheirEmail() {
    when(administrators.list(anyLong()))
        .thenReturn(
            List.of(
                administrator(1L, "ana@example.test", "Ana"),
                administrator(2L, "luis@example.test", null),
                administrator(3L, "otra@example.test", "Otra")));

    assertThat(adapter.namesOf(Set.of(1L, 2L)))
        .containsOnly(entry(1L, "Ana"), entry(2L, "luis@example.test"));
  }

  @Test
  void noReviewersAskNothing() {
    assertThat(adapter.namesOf(Set.of())).isEmpty();
    verifyNoInteractions(administrators);
  }

  private static AdministratorView administrator(Long id, String email, @Nullable String name) {
    return new AdministratorView(
        id,
        DocumentKind.DNI,
        "****5678",
        email,
        AdministratorStatus.ACTIVE,
        Instant.EPOCH,
        false,
        name);
  }
}

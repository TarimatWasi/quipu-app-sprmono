package com.tarimatwasi.quipu.auth.infrastructure.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.ProvisionInitialAdminUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ProvisionInitialAdminUseCase.InitialAdminCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

  private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

  @Mock ProvisionInitialAdminUseCase initialAdmin;

  private AdminBootstrap bootstrap(String document, String email, boolean required) {
    return new AdminBootstrap(initialAdmin, new AdminProperties(document, email, required));
  }

  @Test
  void provisionsTheAdminFromTheConfiguredIdentity() {
    bootstrap("12345678", "admin@example.com", true).run(NO_ARGS);

    verify(initialAdmin).provision(new InitialAdminCommand("12345678", "admin@example.com"));
  }

  @Test
  void stripsTheWhitespaceOfTheConfiguredIdentity() {
    bootstrap(" 12345678\n", "admin@example.com \n", true).run(NO_ARGS);

    verify(initialAdmin).provision(new InitialAdminCommand("12345678", "admin@example.com"));
  }

  @Test
  void doesNotFailWhenNothingIsConfiguredAndAnAdminExists() {
    when(initialAdmin.hasActiveAdmin()).thenReturn(true);

    assertThatCode(() -> bootstrap("", "", true).run(NO_ARGS)).doesNotThrowAnyException();

    verify(initialAdmin, never()).provision(any());
  }

  @Test
  void doesNotFailWhenNothingIsConfiguredAndAnAdminIsNotRequired() {
    when(initialAdmin.hasActiveAdmin()).thenReturn(false);

    assertThatCode(() -> bootstrap("", "", false).run(NO_ARGS)).doesNotThrowAnyException();

    verify(initialAdmin, never()).provision(any());
  }

  @Test
  void failsWhenAnAdminIsRequiredButThereIsNoneAndNothingIsConfigured() {
    when(initialAdmin.hasActiveAdmin()).thenReturn(false);

    assertThatThrownBy(() -> bootstrap("", "", true).run(NO_ARGS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nobody could access");
  }

  @Test
  void failsFastOnPartialConfiguration() {
    assertThatThrownBy(() -> bootstrap("12345678", "", false).run(NO_ARGS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("together");
    assertThatThrownBy(() -> bootstrap("", "admin@example.com", false).run(NO_ARGS))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsAnEmailWithoutAtSign() {
    assertThatThrownBy(() -> bootstrap("12345678", "not-an-email", false).run(NO_ARGS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not an email");
  }
}

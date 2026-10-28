package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ProvisionInitialAdminUseCase.InitialAdminCommand;
import com.tarimatwasi.quipu.auth.application.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.application.port.out.InitialAdminStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.InitialAdminStorePort.NewAdmin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class InitialAdminServiceTest {

  private static final InitialAdminCommand COMMAND =
      new InitialAdminCommand("12345678", "admin@example.com");

  @Mock InitialAdminStorePort store;
  @Mock PasswordEncoder encoder;
  @Mock PasswordRecoveryUseCase recovery;

  private InitialAdminService service() {
    return new InitialAdminService(store, encoder, recovery);
  }

  @Test
  void doesNothingWhenAnActiveAdminExists() {
    when(store.countActiveAdmins()).thenReturn(1);

    service().provision(COMMAND);

    verify(store, never()).insert(any());
    verify(recovery, never()).requestReset(any());
  }

  @Test
  void createsTheAdminWithAnUnknownPasswordAndSendsTheLink() {
    when(store.countActiveAdmins()).thenReturn(0);
    when(encoder.encode(any())).thenReturn("hash");

    service().provision(COMMAND);

    var admin = ArgumentCaptor.forClass(NewAdmin.class);
    verify(store).insert(admin.capture());
    assertThat(admin.getValue().email()).isEqualTo("admin@example.com");
    assertThat(admin.getValue().documentNumber()).isEqualTo("12345678");
    assertThat(admin.getValue().passwordHash()).isEqualTo("hash");
    verify(recovery).requestReset("admin@example.com");
  }

  @Test
  void theUnusablePasswordIsRandomAndNeverTheOneOfAnotherAccount() {
    when(store.countActiveAdmins()).thenReturn(0);
    when(encoder.encode(any())).thenReturn("hash");

    service().provision(COMMAND);
    service().provision(COMMAND);

    var passwords = ArgumentCaptor.forClass(CharSequence.class);
    verify(encoder, org.mockito.Mockito.times(2)).encode(passwords.capture());
    assertThat(passwords.getAllValues().get(0).toString())
        .hasSizeGreaterThanOrEqualTo(43)
        .isNotEqualTo(passwords.getAllValues().get(1).toString());
  }

  @Test
  void toleratesTheConcurrentCreationByAnotherInstance() {
    when(store.countActiveAdmins()).thenReturn(0, 1);
    when(encoder.encode(any())).thenReturn("hash");
    org.mockito.Mockito.doThrow(new AccountAlreadyExistsException(new RuntimeException()))
        .when(store)
        .insert(any());

    service().provision(COMMAND);

    verify(recovery, never()).requestReset(any());
  }

  @Test
  void failsWhenTheEmailOrDocumentBelongsToAnotherUser() {
    when(store.countActiveAdmins()).thenReturn(0);
    when(encoder.encode(any())).thenReturn("hash");
    org.mockito.Mockito.doThrow(new AccountAlreadyExistsException(new RuntimeException()))
        .when(store)
        .insert(any());

    assertThatThrownBy(() -> service().provision(COMMAND))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another user");
  }
}

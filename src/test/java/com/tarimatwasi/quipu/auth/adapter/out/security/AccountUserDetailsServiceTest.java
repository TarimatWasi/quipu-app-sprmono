package com.tarimatwasi.quipu.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.domain.DocumentIdentity;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class AccountUserDetailsServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final MutableClock clock = new MutableClock(NOW);
  private final AccountUserDetailsService service = new AccountUserDetailsService(users, clock);

  @Test
  void theUsernameIsTheIdentityAndTheObjectIsStillThere() {
    when(users.findByDocument(DocumentType.DNI, "12345678"))
        .thenReturn(Optional.of(account(Role.GUEST, "ACTIVE", null)));

    var details = (AccountUserDetails) service.loadUserByUsername("DNI:12345678");

    assertThat(details.getUsername()).isEqualTo("DNI:12345678");
    assertThat(details.identity()).isEqualTo(new DocumentIdentity(DocumentType.DNI, "12345678"));
    assertThat(details.userId()).isEqualTo(7L);
    assertThat(details.getPassword()).isEqualTo("hash");
    assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_GUEST");
  }

  @Test
  void anActiveUnlockedAccountCanAuthenticate() {
    when(users.findByDocument(DocumentType.DNI, "12345678"))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", null)));

    var details = service.loadUserByUsername("DNI:12345678");

    assertThat(details.isEnabled()).isTrue();
    assertThat(details.isAccountNonLocked()).isTrue();
    assertThat(details.isAccountNonExpired()).isTrue();
    assertThat(details.isCredentialsNonExpired()).isTrue();
  }

  @Test
  void anInactiveAccountIsDisabled() {
    when(users.findByDocument(DocumentType.DNI, "12345678"))
        .thenReturn(Optional.of(account(Role.GUEST, "INACTIVE", null)));

    assertThat(service.loadUserByUsername("DNI:12345678").isEnabled()).isFalse();
  }

  @Test
  void aLockThatHasNotPassedLocksTheAccountAndAPassedOneDoesNot() {
    when(users.findByDocument(DocumentType.DNI, "12345678"))
        .thenReturn(Optional.of(account(Role.GUEST, "ACTIVE", NOW.plus(Duration.ofMinutes(5)))));
    assertThat(service.loadUserByUsername("DNI:12345678").isAccountNonLocked()).isFalse();

    clock.advance(Duration.ofMinutes(6));
    assertThat(service.loadUserByUsername("DNI:12345678").isAccountNonLocked()).isTrue();
  }

  @Test
  void anUnknownIdentityIsNotFound() {
    when(users.findByDocument(DocumentType.CE, "999")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.loadUserByUsername("CE:999"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  void aUsernameThatIsNotAnIdentityIsNotFoundAndNeverReachesTheDatabase() {
    assertThatThrownBy(() -> service.loadUserByUsername("not an identity"))
        .isInstanceOf(UsernameNotFoundException.class);
    assertThatThrownBy(() -> service.loadUserByUsername("DNI:1:2"))
        .isInstanceOf(UsernameNotFoundException.class);
    org.mockito.Mockito.verifyNoInteractions(users);
  }

  private static UserAccount account(Role role, String status, @Nullable Instant lockedUntil) {
    return new UserAccount(
        7L,
        "a@example.test",
        DocumentType.DNI,
        "12345678",
        "hash",
        role,
        null,
        false,
        status,
        0,
        lockedUntil,
        null);
  }
}

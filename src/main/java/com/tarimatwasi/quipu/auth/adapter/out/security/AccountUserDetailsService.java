package com.tarimatwasi.quipu.auth.adapter.out.security;

import com.tarimatwasi.quipu.auth.domain.DocumentIdentity;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Clock;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Finds an account by its document identity ({@code TYPE:number}), which is the username Spring
 * Security uses. Having a real service also stops Boot from generating a default user with a
 * password in the log.
 */
@Component
public class AccountUserDetailsService implements UserDetailsService {

  private final UserRepositoryPort users;
  private final Clock clock;

  public AccountUserDetailsService(UserRepositoryPort users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    DocumentIdentity identity;
    try {
      identity = DocumentIdentity.parse(username);
    } catch (IllegalArgumentException notAnIdentity) {
      throw new UsernameNotFoundException("Unknown account", notAnIdentity);
    }
    return users
        .findByDocument(identity.type(), identity.number())
        .map(account -> new AccountUserDetails(account, clock.instant()))
        .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
  }
}

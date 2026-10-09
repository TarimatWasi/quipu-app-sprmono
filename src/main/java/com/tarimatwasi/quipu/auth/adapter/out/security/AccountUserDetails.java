package com.tarimatwasi.quipu.auth.adapter.out.security;

import com.tarimatwasi.quipu.auth.domain.DocumentIdentity;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * An account as Spring Security sees it. The username is the document identity as one text; the
 * identity and the account id stay available as values, so nobody has to read them back from the
 * text.
 */
public final class AccountUserDetails implements UserDetails {

  private static final long serialVersionUID = 1L;

  private final DocumentIdentity identity;
  private final Long userId;
  private final String passwordHash;
  private final String role;
  private final boolean enabled;
  private final boolean nonLocked;

  /** The account as of {@code now}: a lock that has passed no longer locks. */
  AccountUserDetails(UserAccount account, Instant now) {
    this.identity = account.identity();
    this.userId = account.id();
    this.passwordHash = account.passwordHash();
    this.role = account.role().name();
    this.enabled = !account.isDisabled();
    this.nonLocked = !account.isLockedAt(now);
  }

  /** Who the account is, as a value. */
  public DocumentIdentity identity() {
    return identity;
  }

  /** The id of the account. */
  public Long userId() {
    return userId;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return identity.toString();
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return nonLocked;
  }

  /** A password that must be changed (RF-12) still logs in: the session is what is limited. */
  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}

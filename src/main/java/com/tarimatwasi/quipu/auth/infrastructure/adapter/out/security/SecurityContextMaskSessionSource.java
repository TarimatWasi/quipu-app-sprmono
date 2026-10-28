package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.shared.masking.MaskSessionSource;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * TAR-227: the masking session of the request is the one of its session token, as {@link
 * AccountJwtAuthenticationConverter} left it in the security context. Any other authentication
 * (none, anonymous, a test double) has no session, and masking fails closed.
 */
@Component
class SecurityContextMaskSessionSource implements MaskSessionSource {

  @Override
  public Optional<MaskSession> current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof MaskedSessionAuthenticationToken session
        && session.isAuthenticated()) {
      return session.maskSession();
    }
    return Optional.empty();
  }
}

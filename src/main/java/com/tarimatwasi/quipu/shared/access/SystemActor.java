package com.tarimatwasi.quipu.shared.access;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The application acting on its own, without a user: the work a domain event starts after the
 * commit (the sweep of a business day). It holds only ROLE_SYSTEM, which the use cases grant method
 * by method to the reads such work needs; QP-EVT-06 keeps it out of every request path.
 */
public final class SystemActor {

  /** The role of the system; never the role of an account. */
  public static final String ROLE = "ROLE_SYSTEM";

  private SystemActor() {}

  /** Runs the work as the system, then puts back the context the thread had. */
  public static void run(Runnable work) {
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext system = SecurityContextHolder.createEmptyContext();
    system.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "system", null, List.of(new SimpleGrantedAuthority(ROLE))));
    SecurityContextHolder.setContext(system);
    try {
      work.run();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}

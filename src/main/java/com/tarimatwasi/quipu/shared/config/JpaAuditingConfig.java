package com.tarimatwasi.quipu.shared.config;

import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
public class JpaAuditingConfig {

  /**
   * The logged-in user, by reference: no query, the audit column only needs the id. Without a
   * session (or with a name that is not a user id) there is no auditor and the column stays empty.
   */
  @Bean
  public AuditorAware<AuditorJpaEntity> auditorAware(EntityManager entityManager) {
    return () -> {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            entityManager.getReference(AuditorJpaEntity.class, Long.parseLong(auth.getName())));
      } catch (NumberFormatException notAnId) {
        return Optional.empty();
      }
    };
  }
}

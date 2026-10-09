package com.tarimatwasi.quipu.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JpaAuditingConfigTest {

  private static final long USER_ID = 42L;

  private final EntityManager entityManager = mock(EntityManager.class);
  private final AuditorAware<AuditorJpaEntity> auditor =
      new JpaAuditingConfig().auditorAware(entityManager);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void auditsTheAuthenticatedUserByReference() {
    var reference = mock(AuditorJpaEntity.class);
    when(entityManager.getReference(AuditorJpaEntity.class, USER_ID)).thenReturn(reference);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));

    assertThat(auditor.getCurrentAuditor()).containsSame(reference);
  }

  @Test
  void hasNoAuditorWhenThereIsNoSession() {
    assertThat(auditor.getCurrentAuditor()).isEmpty();
    verifyNoInteractions(entityManager);
  }

  @Test
  void hasNoAuditorForAnAnonymousRequest() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

    assertThat(auditor.getCurrentAuditor()).isEmpty();
    verifyNoInteractions(entityManager);
  }

  @Test
  void hasNoAuditorWhenTheSessionNameIsNotAUserId() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("not-a-number", null, List.of()));

    assertThat(auditor.getCurrentAuditor()).isEmpty();
    verifyNoInteractions(entityManager);
  }
}

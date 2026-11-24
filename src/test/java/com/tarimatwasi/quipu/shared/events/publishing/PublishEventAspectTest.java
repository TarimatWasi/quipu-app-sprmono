package com.tarimatwasi.quipu.shared.events.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublishEventAspectTest {

  private static final Instant NOW = Instant.parse("2026-11-23T15:00:00Z");

  record Done(EventMetadata metadata, Long id) implements DomainEvent {
    @Override
    public String subject() {
      return "thing/" + id;
    }
  }

  public static class Service {
    @PublishEvent(
        type = Done.class,
        args = {"#id"})
    public void finish(Long id) {}
  }

  private final List<Object> published = new ArrayList<>();
  private final Service service = proxy();

  private Service proxy() {
    AspectJProxyFactory factory = new AspectJProxyFactory(new Service());
    factory.setProxyTargetClass(true);
    factory.addAspect(
        new PublishEventAspect(
            published::add, new PublishEventDeclarations(), Clock.fixed(NOW, ZoneOffset.UTC)));
    return factory.getProxy();
  }

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    SecurityContextHolder.clearContext();
  }

  /** Outside a transaction the event would be written alone and never delivered: it fails. */
  @Test
  void outsideATransaction_theMethodFailsAndNothingIsPublished() {
    assertThatThrownBy(() -> service.finish(4L))
        .isInstanceOf(EventOutsideTransactionException.class)
        .hasMessageContaining("Service.finish")
        .hasMessageContaining("@Transactional");
    assertThat(published).isEmpty();
  }

  @Test
  void theActorIsTheAccountOfTheTokenAndTheTimeIsTheClock() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    authenticate(new TestingAuthenticationToken("42", null, "ROLE_ADMIN"));

    service.finish(4L);

    assertThat(published).singleElement().isInstanceOf(Done.class);
    Done done = (Done) published.getFirst();
    assertThat(done.id()).isEqualTo(4L);
    assertThat(done.metadata().actorUserId()).isEqualTo(42L);
    assertThat(done.metadata().occurredAt()).isEqualTo(NOW);
  }

  /** No session, an anonymous one, or a name that is no account id: the system acted. */
  @Test
  void withoutAnAccountTheActorIsTheSystem() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TestingAuthenticationToken notAuthenticated = new TestingAuthenticationToken("7", null);
    notAuthenticated.setAuthenticated(false);

    for (Authentication authentication :
        new Authentication[] {
          null,
          notAuthenticated,
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")),
          new TestingAuthenticationToken("", null, "ROLE_SYSTEM")
        }) {
      published.clear();
      authenticate(authentication);

      service.finish(4L);

      assertThat(((Done) published.getFirst()).metadata().actorUserId()).isNull();
    }
  }

  private static void authenticate(@Nullable Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}

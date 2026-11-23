package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.support.InMemorySessionStore;
import org.mockito.Mockito;

/** The real security changes over test doubles; outside Spring no event is published. */
final class TestSecurityFacts {
  private TestSecurityFacts() {}

  static SecurityFacts over(UserRepositoryPort users) {
    return over(users, Mockito.mock(AdministratorStorePort.class));
  }

  static SecurityFacts over(UserRepositoryPort users, AdministratorStorePort administrators) {
    return new SecurityFacts(new InMemorySessionStore(), users, administrators);
  }
}

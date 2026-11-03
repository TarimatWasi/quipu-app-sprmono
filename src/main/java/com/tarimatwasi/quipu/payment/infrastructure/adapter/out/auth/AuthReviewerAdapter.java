package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.auth;

import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentReviewerPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The administrators are few (at most a handful), so the whole list is read once per request. One
 * that never wrote its name (before A0.5) is named by its email.
 */
@Component
public class AuthReviewerAdapter implements PaymentReviewerPort {

  /** The list marks who is the session user; any id serves, nobody here reads it. */
  private static final long NOBODY = 0L;

  private final ManageAdministratorsUseCase administrators;

  AuthReviewerAdapter(ManageAdministratorsUseCase administrators) {
    this.administrators = administrators;
  }

  @Override
  public Map<Long, String> namesOf(Collection<Long> administratorIds) {
    Map<Long, String> names = new HashMap<>();
    if (administratorIds.isEmpty()) {
      return names;
    }
    for (AdministratorView administrator : administrators.list(NOBODY)) {
      if (administratorIds.contains(administrator.id())) {
        String name = administrator.name();
        names.put(administrator.id(), name == null ? administrator.email() : name);
      }
    }
    return names;
  }
}

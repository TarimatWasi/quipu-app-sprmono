package com.tarimatwasi.quipu.auth.infrastructure.adapter.in.bootstrap;

import com.tarimatwasi.quipu.auth.application.port.in.ProvisionInitialAdminUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ProvisionInitialAdminUseCase.InitialAdminCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the first ADMIN at startup when the system has no active one (ADR-F2). */
@Component
public class AdminBootstrap implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

  private final ProvisionInitialAdminUseCase initialAdmin;
  private final AdminProperties properties;

  public AdminBootstrap(ProvisionInitialAdminUseCase initialAdmin, AdminProperties properties) {
    this.initialAdmin = initialAdmin;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    String documentNumber = properties.documentNumber().strip();
    String email = properties.email().strip();
    boolean noDocument = documentNumber.isEmpty();
    boolean noEmail = email.isEmpty();
    if (noDocument && noEmail) {
      requireAnAdminIfNothingIsConfigured();
      return;
    }
    if (noDocument || noEmail) {
      throw new IllegalStateException("ADMIN_DOCUMENT_NUMBER and ADMIN_EMAIL must be set together");
    }
    if (!email.contains("@")) {
      throw new IllegalStateException("ADMIN_EMAIL is not an email address");
    }
    initialAdmin.provision(new InitialAdminCommand(documentNumber, email));
  }

  private void requireAnAdminIfNothingIsConfigured() {
    if (initialAdmin.hasActiveAdmin()) {
      return;
    }
    if (properties.required()) {
      throw new IllegalStateException(
          "No active ADMIN user exists and ADMIN_DOCUMENT_NUMBER / ADMIN_EMAIL are not set:"
              + " nobody could access the system");
    }
    LOG.warn("No active ADMIN user exists and ADMIN_DOCUMENT_NUMBER / ADMIN_EMAIL are not set");
  }
}

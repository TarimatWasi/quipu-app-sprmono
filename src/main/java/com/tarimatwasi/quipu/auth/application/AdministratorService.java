package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.application.AdministratorRegistrar.Registered;
import com.tarimatwasi.quipu.auth.application.AdministratorRegistrar.Registration;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.port.in.InvalidAdministratorDocumentException;
import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * RF-22, CU-30. Like the first administrator ({@link InitialAdminService}), the account is created
 * with the hash of random bytes that nobody knows and the person chooses the real password through
 * the single-use link of the recovery flow (RF-16): no password is returned, logged or emailed.
 *
 * <p>Not transactional on purpose: {@link AdministratorRegistrar} commits the account before the
 * link is issued and the emails are sent, so a failed email never undoes the creation.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class AdministratorService implements CreateAdministratorUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AdministratorService.class);
  private static final int UNUSABLE_PASSWORD_BYTES = 32;

  /** The rule of the guests' documents (GuestService), so both kinds of account store it alike. */
  private static final Pattern DNI = Pattern.compile("[0-9]{8}");

  private static final Pattern OTHER_DOCUMENT = Pattern.compile("[A-Za-z0-9]{1,20}");

  private final SecureRandom random = new SecureRandom();
  private final AdministratorRegistrar registrar;
  private final ResetCodeIssuer issuer;
  private final PasswordResetMailPort mail;
  private final AdministratorNoticePort notices;
  private final AdministratorStorePort store;
  private final PasswordEncoder passwordEncoder;

  AdministratorService(
      AdministratorRegistrar registrar,
      ResetCodeIssuer issuer,
      PasswordResetMailPort mail,
      AdministratorNoticePort notices,
      AdministratorStorePort store,
      PasswordEncoder passwordEncoder) {
    this.registrar = registrar;
    this.issuer = issuer;
    this.mail = mail;
    this.notices = notices;
    this.store = store;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public AdministratorCreated create(CreateAdministratorCommand command) {
    DocumentType type = DocumentType.valueOf(command.documentType().name());
    String number = documentNumber(type, command.documentNumber());
    String email = command.email().strip();
    // Hashed before the transaction: the slow hash does not hold any lock.
    Registered registered =
        registrar.register(
            new Registration(
                command.creatorId(),
                type,
                number,
                email,
                command.currentPassword(),
                unusablePasswordHash()));
    LOG.info(
        "Administrator {} was created by administrator {}", registered.id(), command.creatorId());
    boolean emailSent = sendAccessLink(registered.id(), email);
    notifyTheOthers(command.creatorId(), registered, email);
    return new AdministratorCreated(registered.id(), emailSent);
  }

  /** No edge spaces and the number fits its type; CE and passport are upper case. */
  private static String documentNumber(DocumentType type, String requested) {
    String number = requested.strip();
    boolean valid =
        type == DocumentType.DNI
            ? DNI.matcher(number).matches()
            : OTHER_DOCUMENT.matcher(number).matches();
    if (!valid) {
      throw new InvalidAdministratorDocumentException();
    }
    return type == DocumentType.DNI ? number : number.toUpperCase(Locale.ROOT);
  }

  /**
   * The synchronous variant of the recovery request (RF-16): the creator must learn whether the
   * link left. Only the SHA-256 of the code is stored ({@link ResetCodeIssuer}). The account is
   * already committed, so whatever fails here only means that no link left; the person can use
   * "forgot password" later.
   */
  private boolean sendAccessLink(Long id, String email) {
    try {
      Optional<ResetCodeIssuer.IssuedCode> issued = issuer.issueFor(email);
      if (issued.isEmpty()) {
        LOG.warn("No access code was issued for administrator {}", id);
        return false;
      }
      boolean sent =
          mail.sendAccessLinkNow(email, issued.get().code(), ResetCodeIssuer.CODE_VALIDITY);
      if (!sent) {
        LOG.warn("The access email of administrator {} was not delivered", id);
      }
      return sent;
    } catch (MailDeliveryException | DataAccessException | TransactionException e) {
      // Only the type: the message of a provider can echo the address.
      LOG.warn(
          "The access email of administrator {} was not delivered ({})",
          id,
          e.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * Tells the other active administrators who created which account, so a creation nobody expected
   * is noticed. Best effort: a failed notice does not undo the creation nor stop the next one.
   */
  private void notifyTheOthers(Long creatorId, Registered registered, String email) {
    List<String> others;
    try {
      others = store.activeAdministratorEmailsExcept(List.of(creatorId, registered.id()));
    } catch (DataAccessException e) {
      LOG.warn(
          "The other administrators were not told about administrator {} ({})",
          registered.id(),
          e.getClass().getSimpleName());
      return;
    }
    for (String other : others) {
      try {
        notices.administratorCreated(other, registered.creatorEmail(), email);
      } catch (MailDeliveryException e) {
        LOG.warn(
            "An administrator was not told about administrator {} ({})",
            registered.id(),
            e.getClass().getSimpleName());
      }
    }
  }

  private String unusablePasswordHash() {
    byte[] bytes = new byte[UNUSABLE_PASSWORD_BYTES];
    random.nextBytes(bytes);
    return passwordEncoder.encode(Base64.getEncoder().encodeToString(bytes));
  }
}

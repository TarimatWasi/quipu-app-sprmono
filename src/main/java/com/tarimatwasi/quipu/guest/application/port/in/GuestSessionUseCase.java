package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import org.jspecify.annotations.Nullable;

/**
 * What a session of a guest shows (login, activation, current session): the caller passes the guest
 * of the account that was just authenticated, never one named in the request.
 */
public interface GuestSessionUseCase {

  /** The onboarding steps after the password, in the order of RN-33. */
  enum OnboardingStep {
    PERSONAL_DATA,
    DOCUMENTS,
    CONSENT
  }

  /**
   * The guest as its session shows it.
   *
   * @param name the full name, or the document in text ("DNI 12345678") until it is known
   * @param nextStep the first pending step after the password; null unless the guest is onboarding
   */
  record GuestSessionView(
      GuestKind type, GuestState status, String name, @Nullable OnboardingStep nextStep) {}

  /**
   * The guest.
   *
   * @throws GuestNotFoundException if there is none
   */
  GuestSessionView sessionOf(Long guestId);
}

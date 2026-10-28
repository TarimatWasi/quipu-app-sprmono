package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.adapter.in.rest.GuestOnboardingBffController.EmergencyContactBody;
import com.tarimatwasi.quipu.bff.adapter.in.rest.GuestsBffController.GuestSummaryResponse;
import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase.ProfileChange;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CU-20, CU-23, RN-17: the guest edits its own personal and contact data. Only GUEST reaches these
 * areas (RN-08), and the guard of the guest areas refuses the other type of guest and a guest that
 * has not completed the onboarding before the body is read. The guest is always the one of the
 * session, never one named in the request (RN-09). The answers carry personal data: no cache keeps
 * them.
 */
@RestController
public class GuestProfileBffController {

  private final OwnProfileUseCase profiles;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;

  public GuestProfileBffController(
      OwnProfileUseCase profiles, GuestAccountUseCase accounts, IdMasker ids) {
    this.profiles = profiles;
    this.accounts = accounts;
    this.ids = ids;
  }

  /**
   * ProfileUpdateInput: every field optional, at least one, none other. A setter runs only for a
   * field that is in the body, so an absent field (kept) is told apart from an explicit null
   * (refused); the limits are those of the contract and of the onboarding.
   */
  public static final class ProfileBody {

    /** Something besides blanks; a missing field (null) is let through, as @Pattern does. */
    private static final String NOT_BLANK = "(?s).*[^\\s\\p{Z}].*";

    private final Set<String> present = new LinkedHashSet<>();
    private final Map<String, Object> unknown = new LinkedHashMap<>();

    @Size(min = 1, max = 200)
    @Pattern(regexp = NOT_BLANK)
    private @Nullable String name;

    @Size(min = 1, max = 30)
    @Pattern(regexp = NOT_BLANK)
    private @Nullable String phone;

    @Email
    @Size(min = 3, max = 200)
    private @Nullable String contactEmail;

    @Valid private @Nullable EmergencyContactBody emergencyContact;

    @JsonSetter("name")
    public void setName(@Nullable String name) {
      present.add("name");
      this.name = name;
    }

    @JsonSetter("phone")
    public void setPhone(@Nullable String phone) {
      present.add("phone");
      this.phone = phone;
    }

    @JsonSetter("contactEmail")
    public void setContactEmail(@Nullable String contactEmail) {
      present.add("contactEmail");
      this.contactEmail = contactEmail;
    }

    @JsonSetter("emergencyContact")
    public void setEmergencyContact(@Nullable EmergencyContactBody emergencyContact) {
      present.add("emergencyContact");
      this.emergencyContact = emergencyContact;
    }

    @JsonAnySetter
    public void setUnknown(String field, @Nullable Object value) {
      unknown.put(field, value == null ? "" : value);
    }

    /** The change, once the body is known to be closed, not empty and without a null field. */
    ProfileChange change() {
      UnknownFieldException.rejectAny(unknown);
      // The contract asks for at least one field (minProperties: 1).
      if (present.isEmpty()) {
        throw new EmptyGuestUpdateException();
      }
      rejectNull("name", name);
      rejectNull("phone", phone);
      rejectNull("contactEmail", contactEmail);
      rejectNull("emergencyContact", emergencyContact);
      EmergencyContactBody contact = emergencyContact;
      if (contact != null) {
        GuestOnboardingBffController.rejectUnknown("emergencyContact.", contact.unknown());
      }
      String newName = name;
      String newPhone = phone;
      return new ProfileChange(
          newName == null ? null : newName.strip(),
          newPhone == null ? null : newPhone.strip(),
          contactEmail,
          contact == null
              ? null
              : new EmergencyContactView(
                  GuestOnboardingBffController.blankToNull(contact.name()),
                  GuestOnboardingBffController.blankToNull(contact.relationship()),
                  GuestOnboardingBffController.blankToNull(contact.phone())));
    }

    private void rejectNull(String field, @Nullable Object value) {
      if (present.contains(field) && value == null) {
        throw new NullFieldException(field);
      }
    }
  }

  @PatchMapping("/bff/guest/profile")
  public ResponseEntity<GuestSummaryResponse> updateOwnProfile(
      @Valid @RequestBody ProfileBody body, Authentication authentication) {
    return update(GuestKind.CONTRACT, body, authentication);
  }

  @PatchMapping("/bff/temporary-guest/profile")
  public ResponseEntity<GuestSummaryResponse> updateOwnProfileTemporary(
      @Valid @RequestBody ProfileBody body, Authentication authentication) {
    return update(GuestKind.TEMPORARY, body, authentication);
  }

  private ResponseEntity<GuestSummaryResponse> update(
      GuestKind area, ProfileBody body, Authentication authentication) {
    ProfileChange change = body.change();
    // A GUEST account always belongs to a guest; one that does not is refused (RN-09).
    Long guestId =
        accounts
            .guestOf(Long.valueOf(authentication.getName()))
            .orElseThrow(OtherGuestTypeException::new);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(GuestSummaryResponse.of(profiles.update(guestId, area, change), ids));
  }
}

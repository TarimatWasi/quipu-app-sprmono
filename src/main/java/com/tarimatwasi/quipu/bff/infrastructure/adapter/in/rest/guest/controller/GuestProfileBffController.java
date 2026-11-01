package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.GuestsBffController.GuestSummaryResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.NullFieldException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.UnknownFieldException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller.GuestOnboardingBffController.DocumentForm;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller.GuestOnboardingBffController.DocumentRefResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller.GuestOnboardingBffController.EmergencyContactBody;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller.GuestOnboardingBffController.EmergencyContactResponse;
import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentsUseCase.GuestDocumentView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.application.port.in.OwnDocumentUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase.OwnProfileView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase.ProfileChange;
import com.tarimatwasi.quipu.payment.application.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

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
  private final OwnDocumentUseCase documents;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;

  public GuestProfileBffController(
      OwnProfileUseCase profiles,
      OwnDocumentUseCase documents,
      GuestAccountUseCase accounts,
      IdMasker ids) {
    this.profiles = profiles;
    this.documents = documents;
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

  /**
   * OwnProfile (H5, T3; TAR-242). The nullable fields are always present, as null when missing; the
   * documents never carry a link to their file (TAR-241 adds it apart).
   */
  public record OwnProfileResponse(
      String documentType,
      String documentNumber,
      String name,
      @Nullable String phone,
      @Nullable String contactEmail,
      @Nullable EmergencyContactResponse emergencyContact,
      @Nullable String accountEmail,
      List<DocumentRefResponse> documents,
      int documentsLimit) {

    static OwnProfileResponse of(OwnProfileView view, IdMasker ids) {
      EmergencyContactView contact = view.emergencyContact();
      return new OwnProfileResponse(
          view.documentType(),
          view.documentNumber(),
          view.name(),
          view.phone(),
          view.contactEmail(),
          contact == null
              ? null
              : new EmergencyContactResponse(
                  contact.name(), contact.relationship(), contact.phone()),
          view.accountEmail(),
          view.documents().stream().map(document -> DocumentRefResponse.of(document, ids)).toList(),
          view.documentsLimit());
    }
  }

  @GetMapping("/bff/guest/profile")
  public ResponseEntity<OwnProfileResponse> getOwnProfile(Authentication authentication) {
    return own(GuestKind.CONTRACT, authentication);
  }

  @GetMapping("/bff/temporary-guest/profile")
  public ResponseEntity<OwnProfileResponse> getOwnProfileTemporary(Authentication authentication) {
    return own(GuestKind.TEMPORARY, authentication);
  }

  @PostMapping(path = UploadRoutes.GUEST_DOCUMENTS, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentRefResponse uploadOwnDocument(
      @Valid DocumentForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    return upload(GuestKind.CONTRACT, form, request, authentication);
  }

  @PostMapping(
      path = UploadRoutes.TEMPORARY_GUEST_DOCUMENTS,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentRefResponse uploadTemporaryOwnDocument(
      @Valid DocumentForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    return upload(GuestKind.TEMPORARY, form, request, authentication);
  }

  /** GuestDocument (TAR-241): an own document with its signed link, valid 15 minutes. */
  public record OwnDocumentResponse(
      UUID id,
      String originalName,
      String contentType,
      long sizeBytes,
      Instant uploadedAt,
      String url,
      Instant urlExpiresAt) {

    static OwnDocumentResponse of(GuestDocumentView view, IdMasker ids) {
      return new OwnDocumentResponse(
          ids.mask(IdKind.GUEST_DOCUMENT, view.id()),
          view.originalName(),
          view.contentType(),
          view.sizeBytes(),
          view.uploadedAt(),
          view.url(),
          view.urlExpiresAt());
    }
  }

  @GetMapping("/bff/guest/documents/{documentId}")
  public ResponseEntity<OwnDocumentResponse> getOwnDocument(
      @PathVariable UUID documentId, Authentication authentication) {
    return openDocument(GuestKind.CONTRACT, documentId, authentication);
  }

  @GetMapping("/bff/temporary-guest/documents/{documentId}")
  public ResponseEntity<OwnDocumentResponse> getTemporaryOwnDocument(
      @PathVariable UUID documentId, Authentication authentication) {
    return openDocument(GuestKind.TEMPORARY, documentId, authentication);
  }

  /**
   * An id that this session never handed out is the same 404 as a document of another guest: the
   * answer never tells them apart (RN-09).
   */
  private ResponseEntity<OwnDocumentResponse> openDocument(
      GuestKind area, UUID masked, Authentication authentication) {
    Long guestId = guestOf(authentication);
    long documentId;
    try {
      documentId = ids.unmask(IdKind.GUEST_DOCUMENT, masked);
    } catch (UnknownMaskedIdException forged) {
      throw new GuestDocumentNotFoundException();
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(OwnDocumentResponse.of(documents.open(guestId, area, documentId), ids));
  }

  private ResponseEntity<OwnProfileResponse> own(GuestKind area, Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(OwnProfileResponse.of(profiles.own(guestOf(authentication), area), ids));
  }

  private DocumentRefResponse upload(
      GuestKind area,
      DocumentForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    DocumentUpload upload = GuestOnboardingBffController.documentUpload(form, request);
    return DocumentRefResponse.of(
        profiles.uploadDocument(guestOf(authentication), area, upload), ids);
  }

  /** A GUEST account always belongs to a guest; one that does not is refused (RN-09). */
  private Long guestOf(Authentication authentication) {
    return accounts
        .guestOf(Long.valueOf(authentication.getName()))
        .orElseThrow(OtherGuestTypeException::new);
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
    Long guestId = guestOf(authentication);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(GuestSummaryResponse.of(profiles.update(guestId, area, change), ids));
  }
}

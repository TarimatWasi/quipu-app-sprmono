package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.ConsentView;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.DocumentView;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.OnboardingView;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.PersonalData;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * RF-14, RF-20, CU-11, CU-24..26: the onboarding of a guest of either type. Only GUEST reaches
 * /bff/guest/** (RN-08) and these routes are the ones a guest that is not active yet may use. The
 * guest is always the one of the session, never one named in the request (RN-09). The answers carry
 * personal data: no cache keeps them.
 */
@RestController
public class GuestOnboardingBffController {

  /** The only part the document form defines; any other one is refused. */
  private static final Set<String> DOCUMENT_FORM = Set.of("file");

  private final OnboardingUseCase onboarding;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;

  public GuestOnboardingBffController(
      OnboardingUseCase onboarding, GuestAccountUseCase accounts, IdMasker ids) {
    this.onboarding = onboarding;
    this.accounts = accounts;
    this.ids = ids;
  }

  /** The optional emergency contact: the fields that are not there are left out of the answer. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EmergencyContactBody(
      @Size(max = 200) @Nullable String name,
      @Size(max = 50) @Nullable String relationship,
      @Size(max = 30) @Nullable String phone) {}

  /** The personal data step; the limits are those of the contract and of the columns. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PersonalDataBody(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 30) String phone,
      @Email @Size(min = 3, max = 200) @Nullable String contactEmail,
      @Valid @Nullable EmergencyContactBody emergencyContact) {

    static PersonalDataBody of(PersonalData data) {
      EmergencyContactView contact = data.emergencyContact();
      return new PersonalDataBody(
          data.name(),
          data.phone(),
          data.contactEmail(),
          contact == null
              ? null
              : new EmergencyContactBody(contact.name(), contact.relationship(), contact.phone()));
    }
  }

  /** {@code accepted} is not required here: anything but true is CONSENT_NOT_ACCEPTED. */
  public record ConsentBody(@Nullable Boolean accepted, @NotNull String consentVersion) {}

  /** The document form: the use case checks the file by its content. */
  public record DocumentForm(@NotNull MultipartFile file) {}

  /** A document as the guest sees it: never a link to the file (RN-37). */
  public record DocumentRefResponse(
      UUID id, String originalName, String contentType, long sizeBytes, Instant uploadedAt) {
    static DocumentRefResponse of(DocumentView view, IdMasker ids) {
      return new DocumentRefResponse(
          ids.mask(IdKind.GUEST_DOCUMENT, view.id()),
          view.originalName(),
          view.contentType(),
          view.sizeBytes(),
          view.uploadedAt());
    }
  }

  public record ConsentResponse(String version, String text, boolean accepted) {
    static ConsentResponse of(ConsentView view) {
      return new ConsentResponse(view.version(), view.text(), view.accepted());
    }
  }

  /** {@code nextStep} and {@code personalData} are left out where the contract has none. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OnboardingResponse(
      String status,
      @Nullable String nextStep,
      @Nullable PersonalDataBody personalData,
      List<DocumentRefResponse> documents,
      ConsentResponse consent) {
    static OnboardingResponse of(OnboardingView view, IdMasker ids) {
      return new OnboardingResponse(
          view.completed() ? "ACTIVE" : "ONBOARDING",
          view.nextStep() == null ? null : view.nextStep().name(),
          view.personalData() == null ? null : PersonalDataBody.of(view.personalData()),
          view.documents().stream().map(document -> DocumentRefResponse.of(document, ids)).toList(),
          ConsentResponse.of(view.consent()));
    }
  }

  @GetMapping("/bff/guest/onboarding")
  public ResponseEntity<OnboardingResponse> state(Authentication authentication) {
    return answer(onboarding.state(guestOf(authentication)));
  }

  @PutMapping("/bff/guest/onboarding/personal-data")
  public ResponseEntity<OnboardingResponse> savePersonalData(
      @Valid @RequestBody PersonalDataBody body, Authentication authentication) {
    EmergencyContactBody contact = body.emergencyContact();
    PersonalData data =
        new PersonalData(
            body.name().strip(),
            body.phone().strip(),
            body.contactEmail(),
            contact == null
                ? null
                : new EmergencyContactView(
                    blankToNull(contact.name()),
                    blankToNull(contact.relationship()),
                    blankToNull(contact.phone())));
    return answer(onboarding.savePersonalData(guestOf(authentication), data));
  }

  @PostMapping(
      path = UploadRoutes.GUEST_ONBOARDING_DOCUMENTS,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentRefResponse upload(
      @Valid DocumentForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    UnknownFieldException.rejectAnyOutside(
        DOCUMENT_FORM,
        Stream.concat(
                request.getParameterMap().keySet().stream(), request.getFileMap().keySet().stream())
            .toList());
    String name = form.file().getOriginalFilename();
    return DocumentRefResponse.of(
        onboarding.uploadDocument(
            guestOf(authentication),
            new DocumentUpload(name == null ? "" : name, content(form.file()))),
        ids);
  }

  @PostMapping("/bff/guest/onboarding/documents/done")
  public ResponseEntity<OnboardingResponse> finishDocuments(Authentication authentication) {
    return answer(onboarding.finishDocuments(guestOf(authentication)));
  }

  @PostMapping("/bff/guest/onboarding/consent")
  public ResponseEntity<OnboardingResponse> acceptConsent(
      @Valid @RequestBody ConsentBody body, Authentication authentication) {
    return answer(
        onboarding.acceptConsent(
            guestOf(authentication), Boolean.TRUE.equals(body.accepted()), body.consentVersion()));
  }

  private ResponseEntity<OnboardingResponse> answer(OnboardingView view) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(OnboardingResponse.of(view, ids));
  }

  /** A GUEST account always belongs to a guest; one that does not is refused (RN-09). */
  private Long guestOf(Authentication authentication) {
    return accounts
        .guestOf(Long.valueOf(authentication.getName()))
        .orElseThrow(OtherGuestTypeException::new);
  }

  private static @Nullable String blankToNull(@Nullable String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }

  private static byte[] content(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException unreadable) {
      throw new InvalidOnboardingInputException(
          InvalidOnboardingInputException.Reason.DOCUMENT_INVALID_FILE);
    }
  }
}

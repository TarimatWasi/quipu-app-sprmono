package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentActionFailedException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase.DocumentActionRequest;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase.DocumentActionView;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase.GuestDocumentView;
import com.tarimatwasi.quipu.guest.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestDetailView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.StatusFilter;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** RF-02. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class GuestsBffController {

  /** The parts the replacement form defines; any other one is refused (RN-37). */
  private static final Set<String> REPLACEMENT_FORM =
      Set.of("file", "reason", "requestedAt", "requestChannel", "note");

  private final ManageGuestsUseCase guests;
  private final GuestDocumentsUseCase documents;
  private final IdMasker ids;
  private final Clock clock;

  public GuestsBffController(
      ManageGuestsUseCase guests, GuestDocumentsUseCase documents, IdMasker ids, Clock clock) {
    this.guests = guests;
    this.documents = documents;
    this.ids = ids;
    this.clock = clock;
  }

  /** The contract requires {@code name} even when it is still null (RN-31). */
  public record GuestSummaryResponse(
      UUID id,
      String documentType,
      String documentNumber,
      @Nullable String name,
      String type,
      String status,
      boolean hasLoginAccess) {
    static GuestSummaryResponse of(GuestSummaryView guest, IdMasker ids) {
      return new GuestSummaryResponse(
          ids.mask(IdKind.GUEST, guest.id()),
          guest.documentType().name(),
          guest.documentNumber(),
          guest.name(),
          guest.type().name(),
          guest.status().name(),
          guest.hasLoginAccess());
    }
  }

  public record EmergencyContactResponse(
      @Nullable String name, @Nullable String relationship, @Nullable String phone) {
    static @Nullable EmergencyContactResponse of(@Nullable EmergencyContactView contact) {
      return contact == null
          ? null
          : new EmergencyContactResponse(contact.name(), contact.relationship(), contact.phone());
    }
  }

  /**
   * The optional fields are left out when there is none; the nullable ones the contract requires
   * ({@code name}) or defines as nullable are always there.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record GuestDetailResponse(
      UUID id,
      String documentType,
      String documentNumber,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String name,
      String type,
      String status,
      boolean hasLoginAccess,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String phone,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String contactEmail,
      @Nullable EmergencyContactResponse emergencyContact,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable BigDecimal agreedAmount,
      int documentCount,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Instant accessExpiresAt,
      String onboardingProgress,
      List<Object> paymentHistory) {
    static GuestDetailResponse of(GuestDetailView detail, IdMasker ids) {
      GuestSummaryView guest = detail.summary();
      return new GuestDetailResponse(
          ids.mask(IdKind.GUEST, guest.id()),
          guest.documentType().name(),
          guest.documentNumber(),
          guest.name(),
          guest.type().name(),
          guest.status().name(),
          guest.hasLoginAccess(),
          detail.phone(),
          detail.contactEmail(),
          EmergencyContactResponse.of(detail.emergencyContact()),
          detail.stayStartDate(),
          detail.stayEndDate(),
          detail.agreedAmount(),
          detail.documentCount(),
          detail.accessExpiresAt(),
          detail.onboardingProgress().name(),
          // The payments arrive with RF-04 to RF-06; until then the history is empty.
          List.of());
    }
  }

  /**
   * The use case checks what the document type allows. The contract refuses any other field (the
   * stays have their own endpoints), so an unknown one is a 400 and not silently ignored.
   */
  public record UpdateGuestRequest(
      @Nullable IdDocumentKind documentType,
      @Nullable @Size(min = 1, max = 20) String documentNumber,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  @GetMapping("/bff/admin/guests")
  public PagedResponse<GuestSummaryResponse> list(
      @RequestParam(name = "status", defaultValue = "CURRENT") StatusFilter status,
      @RequestParam(name = "type", required = false) @Nullable GuestKind type,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    return PagedResponse.of(
        guests.list(status, type, PageParams.query(after, limit)),
        guest -> GuestSummaryResponse.of(guest, ids));
  }

  @GetMapping("/bff/admin/guests/{id}")
  public GuestDetailResponse get(@PathVariable UUID id) {
    return GuestDetailResponse.of(guests.get(ids.unmask(IdKind.GUEST, id)), ids);
  }

  /** RN-17, RN-36: only the document changes here, while the guest is pending activation. */
  @PatchMapping("/bff/admin/guests/{id}")
  public GuestSummaryResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateGuestRequest request) {
    UnknownFieldException.rejectAny(request.unknown());
    return GuestSummaryResponse.of(
        guests.update(
            ids.unmask(IdKind.GUEST, id),
            new UpdateCommand(request.documentType(), request.documentNumber())),
        ids);
  }

  /** A document of the onboarding with a link that downloads it for 5 minutes (RN-37). */
  public record GuestDocumentResponse(
      UUID id,
      String originalName,
      String contentType,
      long sizeBytes,
      Instant uploadedAt,
      String url,
      Instant urlExpiresAt) {
    static GuestDocumentResponse of(GuestDocumentView view, IdMasker ids) {
      return new GuestDocumentResponse(
          ids.mask(IdKind.GUEST_DOCUMENT, view.id()),
          view.originalName(),
          view.contentType(),
          view.sizeBytes(),
          view.uploadedAt(),
          view.url(),
          view.urlExpiresAt());
    }
  }

  /** CU-27: the body carries freshly signed links, so no cache may keep it. */
  @GetMapping("/bff/admin/guests/{id}/documents")
  public ResponseEntity<List<GuestDocumentResponse>> documents(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            documents.documents(ids.unmask(IdKind.GUEST, id)).stream()
                .map(document -> GuestDocumentResponse.of(document, ids))
                .toList());
  }

  /** Why a document is deleted (RN-37); a field the schema does not define is refused. */
  public record DocumentActionBody(
      @Nullable String reason,
      @Nullable String requestedAt,
      @Nullable String requestChannel,
      @Nullable String note,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  /** The replacement form: the new file and why; the use case checks the file by its content. */
  public record DocumentReplacementForm(
      @Nullable MultipartFile file,
      @Nullable String reason,
      @Nullable String requestedAt,
      @Nullable String requestChannel,
      @Nullable String note) {}

  /** Evidence of one deletion or replacement; never the name, the key nor the content. */
  public record DocumentActionResponse(
      UUID id,
      String action,
      UUID documentId,
      @Nullable UUID replacementDocumentId,
      String reason,
      @Nullable LocalDate requestedAt,
      @Nullable String requestChannel,
      @Nullable String note,
      String contentType,
      long sizeBytes,
      UUID actorId,
      Instant performedAt) {
    static DocumentActionResponse of(DocumentActionView view, IdMasker ids) {
      Long replacement = view.replacementDocumentId();
      return new DocumentActionResponse(
          ids.mask(IdKind.GUEST_DOCUMENT_ACTION, view.id()),
          view.action(),
          ids.mask(IdKind.GUEST_DOCUMENT, view.documentId()),
          replacement == null ? null : ids.mask(IdKind.GUEST_DOCUMENT, replacement),
          view.reason(),
          view.requestedAt(),
          view.requestChannel(),
          view.note(),
          view.contentType(),
          view.sizeBytes(),
          ids.mask(IdKind.USER, view.actorId()),
          view.performedAt());
    }
  }

  /**
   * RN-37: deletes a document on an ARCO request or an upload error, with evidence. The body is
   * checked before the ids are unmasked. If the file left the store but the row could not, the
   * answer is a 500 and repeating the action completes it.
   */
  @PostMapping(UploadRoutes.GUEST_DOCUMENT_DELETION)
  public ResponseEntity<Void> deleteDocument(
      @PathVariable UUID id,
      @PathVariable UUID documentId,
      @Valid @RequestBody DocumentActionBody body) {
    UnknownFieldException.rejectAny(body.unknown());
    DocumentActionRequest request =
        new DocumentActionRequest(
            body.reason(), body.requestedAt(), body.requestChannel(), body.note());
    request.check(LocalDate.now(clock));
    Long guestId = guestOf(id);
    Long document = documentOf(documentId);
    try {
      documents.delete(guestId, document, request);
    } catch (DataAccessException | TransactionException e) {
      throw new GuestDocumentActionFailedException(e);
    }
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  /**
   * RN-37: replaces a document with a new one (with a new id), with evidence. The parts and the
   * reason are checked first, then the size before the file is read, and the PDF is inspected
   * before the use case locks any row (TAR-39).
   */
  @PutMapping(
      path = UploadRoutes.GUEST_DOCUMENT_REPLACEMENT,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<GuestDocumentResponse> replaceDocument(
      @PathVariable UUID id,
      @PathVariable UUID documentId,
      @Valid DocumentReplacementForm form,
      MultipartHttpServletRequest multipart) {
    boolean unknownPart =
        Stream.concat(
                multipart.getParameterMap().keySet().stream(),
                multipart.getFileMap().keySet().stream())
            .anyMatch(part -> !REPLACEMENT_FORM.contains(part));
    if (unknownPart) {
      throw invalidFile();
    }
    DocumentActionRequest request =
        new DocumentActionRequest(
            form.reason(), form.requestedAt(), form.requestChannel(), form.note());
    request.check(LocalDate.now(clock));
    MultipartFile file = form.file();
    // RF-15: the declared size is checked before the bytes are read into memory.
    if (file == null || file.getSize() > OnboardingUseCase.MAX_DOCUMENT_BYTES) {
      throw invalidFile();
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException unreadable) {
      throw invalidFile();
    }
    FileKind.inspectAhead(content);
    String name = file.getOriginalFilename();
    GuestDocumentView replaced =
        documents.replace(
            guestOf(id),
            documentOf(documentId),
            request,
            new DocumentUpload(name == null ? "" : name, content));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(GuestDocumentResponse.of(replaced, ids));
  }

  /** RN-37: who deleted or replaced which document of the guest, when and why; newest first. */
  @GetMapping("/bff/admin/guests/{id}/document-actions")
  public ResponseEntity<PagedResponse<DocumentActionResponse>> documentActions(
      @PathVariable UUID id,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    PageQuery page = PageParams.query(after, limit);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            PagedResponse.of(
                documents.actions(ids.unmask(IdKind.GUEST, id), page),
                action -> DocumentActionResponse.of(action, ids)));
  }

  /** A guest id that was never issued is, for a document, a document that does not exist. */
  private Long guestOf(UUID id) {
    try {
      return ids.unmask(IdKind.GUEST, id);
    } catch (UnknownMaskedIdException forged) {
      throw new GuestDocumentNotFoundException();
    }
  }

  private Long documentOf(UUID id) {
    try {
      return ids.unmask(IdKind.GUEST_DOCUMENT, id);
    } catch (UnknownMaskedIdException forged) {
      throw new GuestDocumentNotFoundException();
    }
  }

  private static InvalidOnboardingInputException invalidFile() {
    return new InvalidOnboardingInputException(
        InvalidOnboardingInputException.Reason.DOCUMENT_INVALID_FILE);
  }

  /** RF-13, RN-12: the guest loses its login access; a contract in force blocks it. */
  @PostMapping("/bff/admin/guests/{id}/deactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivate(@PathVariable UUID id) {
    guests.deactivate(ids.unmask(IdKind.GUEST, id));
  }

  /** RF-13, CU-28: back to the state it had (NonInactiveGuestSummary); debt blocks it. */
  @PostMapping("/bff/admin/guests/{id}/reactivate")
  public GuestSummaryResponse reactivate(@PathVariable UUID id) {
    return GuestSummaryResponse.of(guests.reactivate(ids.unmask(IdKind.GUEST, id)), ids);
  }
}

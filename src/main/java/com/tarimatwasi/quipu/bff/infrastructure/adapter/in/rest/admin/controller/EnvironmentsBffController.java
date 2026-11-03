package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.CreateCommand;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentKind;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentPage;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentView;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.StatusFilter;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNamesUseCase;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-01; TAR-237: each environment says who occupies it. Only ADMIN reaches /bff/admin/** (RN-08);
 * SecurityConfig enforces it. The names of the occupants come from the guest module.
 */
@RestController
public class EnvironmentsBffController {

  /**
   * The code of the contract (decided 2026-10-31, TAR-237): letters, digits, space and {@code . _ /
   * -}, at least one character that is not a space.
   */
  private static final String CODE = "^(?=.*\\S)[A-Za-z0-9 ._/-]{1,50}$";

  private final ManageEnvironmentsUseCase environments;
  private final GuestNamesUseCase guests;
  private final IdMasker ids;

  public EnvironmentsBffController(
      ManageEnvironmentsUseCase environments, GuestNamesUseCase guests, IdMasker ids) {
    this.environments = environments;
    this.guests = guests;
    this.ids = ids;
  }

  public record CreateEnvironmentRequest(
      @NotBlank @Pattern(regexp = CODE) @Size(max = 50) String code,
      @NotNull EnvironmentKind type) {}

  /** {@code version} is the one the client loaded (TAR-276). */
  public record UpdateEnvironmentRequest(
      @NotNull @Min(0) Long version,
      @Nullable @Pattern(regexp = CODE) @Size(max = 50) String code,
      @Nullable EnvironmentKind type) {}

  /** Occupant of the contract: a contract or a stay, whose and when (RN-20, TAR-237). */
  public record OccupantResponse(
      String kind,
      UUID guestId,
      @Nullable String guestName,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      LocalDate startDate,
      @Nullable LocalDate endDate) {}

  /** Environment of the contract; the nullable fields are always present. */
  public record EnvironmentResponse(
      UUID id,
      String code,
      String type,
      String status,
      @Nullable OccupantResponse occupant,
      @Nullable OccupantResponse nextOccupant,
      @Nullable LocalDate freeSince,
      @Nullable Instant deactivatedAt,
      long version) {}

  /** PagedEnvironments of the contract: the page and the A2 notice. */
  public record PagedEnvironmentsResponse(
      List<EnvironmentResponse> content, @Nullable String nextCursor, long freeCount) {}

  @GetMapping("/bff/admin/environments")
  public PagedEnvironmentsResponse list(
      @RequestParam(name = "status", defaultValue = "ACTIVE") StatusFilter status,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after,
      @RequestParam(name = "freeFrom", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate freeFrom,
      @RequestParam(name = "freeTo", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate freeTo) {
    EnvironmentPage found =
        environments.list(status, PageParams.query(after, limit), freeFrom, freeTo);
    List<EnvironmentView> views = found.page().content();
    Map<Long, String> names = namesOf(views);
    return new PagedEnvironmentsResponse(
        views.stream().map(view -> response(view, names)).toList(),
        found.page().nextCursor(),
        found.freeCount());
  }

  @PostMapping("/bff/admin/environments")
  public ResponseEntity<EnvironmentResponse> create(
      @Valid @RequestBody CreateEnvironmentRequest request) {
    EnvironmentResponse created =
        single(environments.create(new CreateCommand(request.code(), request.type())));
    return ResponseEntity.created(URI.create("/bff/admin/environments/" + created.id()))
        .body(created);
  }

  @GetMapping("/bff/admin/environments/{id}")
  public EnvironmentResponse get(@PathVariable UUID id) {
    return single(environments.get(ids.unmask(IdKind.ENVIRONMENT, id)));
  }

  @PatchMapping("/bff/admin/environments/{id}")
  public EnvironmentResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateEnvironmentRequest request) {
    return single(
        environments.update(
            ids.unmask(IdKind.ENVIRONMENT, id),
            new UpdateCommand(request.version(), request.code(), request.type())));
  }

  /** RF-13: the environment leaves the operational listings but keeps its history (RN-12). */
  @PostMapping("/bff/admin/environments/{id}/deactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivate(@PathVariable UUID id) {
    environments.deactivate(ids.unmask(IdKind.ENVIRONMENT, id));
  }

  @PostMapping("/bff/admin/environments/{id}/reactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reactivate(@PathVariable UUID id) {
    environments.reactivate(ids.unmask(IdKind.ENVIRONMENT, id));
  }

  private EnvironmentResponse single(EnvironmentView view) {
    return response(view, namesOf(List.of(view)));
  }

  /** One read of the guest module for every occupant of the page. */
  private Map<Long, String> namesOf(List<EnvironmentView> views) {
    Set<Long> guestIds = new HashSet<>();
    for (EnvironmentView view : views) {
      Occupancy occupant = view.occupant();
      Occupancy next = view.nextOccupant();
      if (occupant != null) {
        guestIds.add(occupant.guestId());
      }
      if (next != null) {
        guestIds.add(next.guestId());
      }
    }
    return guestIds.isEmpty() ? Map.of() : guests.namesOf(guestIds);
  }

  private EnvironmentResponse response(EnvironmentView view, Map<Long, String> names) {
    return new EnvironmentResponse(
        ids.mask(IdKind.ENVIRONMENT, view.id()),
        view.code(),
        view.type().name(),
        view.status().name(),
        occupant(view.occupant(), names),
        occupant(view.nextOccupant(), names),
        view.freeSince(),
        view.deactivatedAt(),
        view.version());
  }

  private @Nullable OccupantResponse occupant(
      @Nullable Occupancy occupancy, Map<Long, String> names) {
    if (occupancy == null) {
      return null;
    }
    Long contractId = occupancy.contractId();
    Long stayId = occupancy.stayId();
    return new OccupantResponse(
        occupancy.kind().name(),
        ids.mask(IdKind.GUEST, occupancy.guestId()),
        names.get(occupancy.guestId()),
        contractId == null ? null : ids.mask(IdKind.CONTRACT, contractId),
        stayId == null ? null : ids.mask(IdKind.STAY, stayId),
        occupancy.startDate(),
        occupancy.endDate());
  }
}

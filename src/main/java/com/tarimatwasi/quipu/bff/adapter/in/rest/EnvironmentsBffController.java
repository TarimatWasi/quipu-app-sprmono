package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase.CreateCommand;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase.EnvironmentKind;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase.EnvironmentView;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase.StatusFilter;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RF-01. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class EnvironmentsBffController {

  // (?U): the Unicode whitespace (EM SPACE and others) counts as whitespace, as in the contract
  private static final String NOT_BLANK = "(?U).*\\S.*";

  private final ManageEnvironmentsUseCase environments;
  private final IdMasker ids;

  public EnvironmentsBffController(ManageEnvironmentsUseCase environments, IdMasker ids) {
    this.environments = environments;
    this.ids = ids;
  }

  public record CreateEnvironmentRequest(
      @NotBlank @Pattern(regexp = NOT_BLANK) @Size(max = 50) String code,
      @NotNull EnvironmentKind type) {}

  public record UpdateEnvironmentRequest(
      @Nullable @Pattern(regexp = NOT_BLANK) @Size(max = 50) String code,
      @Nullable EnvironmentKind type) {}

  public record EnvironmentResponse(UUID id, String code, String type, String status) {
    static EnvironmentResponse of(EnvironmentView environment, IdMasker ids) {
      return new EnvironmentResponse(
          ids.mask(IdKind.ENVIRONMENT, environment.id()),
          environment.code(),
          environment.type().name(),
          environment.status().name());
    }
  }

  @GetMapping("/bff/admin/environments")
  public List<EnvironmentResponse> list(
      @RequestParam(name = "status", defaultValue = "ACTIVE") StatusFilter status) {
    return environments.list(status).stream()
        .map(environment -> EnvironmentResponse.of(environment, ids))
        .toList();
  }

  @PostMapping("/bff/admin/environments")
  @ResponseStatus(HttpStatus.CREATED)
  public EnvironmentResponse create(@Valid @RequestBody CreateEnvironmentRequest request) {
    return EnvironmentResponse.of(
        environments.create(new CreateCommand(request.code(), request.type())), ids);
  }

  @GetMapping("/bff/admin/environments/{id}")
  public EnvironmentResponse get(@PathVariable UUID id) {
    return EnvironmentResponse.of(environments.get(ids.unmask(IdKind.ENVIRONMENT, id)), ids);
  }

  @PatchMapping("/bff/admin/environments/{id}")
  public EnvironmentResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateEnvironmentRequest request) {
    return EnvironmentResponse.of(
        environments.update(
            ids.unmask(IdKind.ENVIRONMENT, id), new UpdateCommand(request.code(), request.type())),
        ids);
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
}

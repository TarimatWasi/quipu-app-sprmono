package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase.FineCommand;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase.FineView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RF-19, RN-30. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class FinesBffController {

  private static final String URL = "/bff/admin/fines";

  // PostgreSQL cannot store the NUL character in a TEXT column
  private static final String NO_NUL = "(?s)[^\\x00]*";

  private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

  /** Read as text so that a value that is not a UUID is a 400 that names the field. */
  private static final String UUID_TEXT =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final java.util.regex.Pattern MONTH_PATTERN =
      java.util.regex.Pattern.compile(MONTH);

  private final ManageFinesUseCase fines;
  private final IdMasker ids;

  public FinesBffController(ManageFinesUseCase fines, IdMasker ids) {
    this.fines = fines;
    this.ids = ids;
  }

  /** The use case checks the amount: greater than 0, at most two decimals and the column limit. */
  public record FineRequest(
      @NotNull @Pattern(regexp = UUID_TEXT) String guestId,
      @NotNull BigDecimal amount,
      @NotNull @Pattern(regexp = MONTH) String month,
      @Nullable @Pattern(regexp = NO_NUL) String description) {}

  /** The description is left out when there is none. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FineResponse(
      UUID id, UUID guestId, BigDecimal amount, String month, @Nullable String description) {
    static FineResponse of(FineView fine, IdMasker ids) {
      return new FineResponse(
          ids.mask(IdKind.FINE, fine.id()),
          ids.mask(IdKind.GUEST, fine.guestId()),
          fine.amount(),
          fine.month().toString(),
          fine.description());
    }
  }

  @GetMapping(URL)
  public List<FineResponse> list(@RequestParam("month") String month) {
    if (!MONTH_PATTERN.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    return fines.list(YearMonth.parse(month)).stream()
        .map(fine -> FineResponse.of(fine, ids))
        .toList();
  }

  @PostMapping(URL)
  public ResponseEntity<FineResponse> create(@Valid @RequestBody FineRequest request) {
    FineResponse created =
        FineResponse.of(
            fines.create(
                new FineCommand(
                    guestOf(request.guestId()),
                    request.amount(),
                    YearMonth.parse(request.month()),
                    request.description())),
            ids);
    return ResponseEntity.created(URI.create(URL + "/" + created.id())).body(created);
  }

  @DeleteMapping(URL + "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    fines.delete(ids.unmask(IdKind.FINE, id));
  }

  /**
   * A guest id that was never issued is passed as none: the use case answers it as a guest that
   * does not exist, after it checked the rest of the data.
   */
  private @Nullable Long guestOf(String masked) {
    try {
      return ids.unmask(IdKind.GUEST, UUID.fromString(masked));
    } catch (UnknownMaskedIdException forged) {
      return null;
    }
  }
}

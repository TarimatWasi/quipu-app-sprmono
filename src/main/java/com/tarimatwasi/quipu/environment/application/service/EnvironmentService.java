package com.tarimatwasi.quipu.environment.application.service;

import com.tarimatwasi.quipu.environment.application.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.environment.application.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.environment.application.port.in.LockEnvironmentUseCase;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentOccupancyPort;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentRepositoryPort;
import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class EnvironmentService implements ManageEnvironmentsUseCase, LockEnvironmentUseCase {

  /**
   * An explicit visible alphabet, as the contract fixes it (decided 2026-10-31, TAR-237): ASCII
   * letters and digits, and after the first character also a space, dot, underscore, slash or
   * hyphen. Accents, blank letters such as U+3164 and zero-width characters are outside it.
   */
  private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._/-]*");

  /** Whitespace and Unicode separators (NBSP, EM SPACE...), which String.strip() leaves. */
  private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$");

  /** An environment that no contract or stay ever occupied. */
  private static final EnvironmentOccupancies NOTHING = new EnvironmentOccupancies(List.of());

  private final EnvironmentRepositoryPort environments;
  private final EnvironmentOccupancyPort occupancy;
  private final Clock clock;

  EnvironmentService(
      EnvironmentRepositoryPort environments, EnvironmentOccupancyPort occupancy, Clock clock) {
    this.environments = environments;
    this.occupancy = occupancy;
    this.clock = clock;
  }

  @Override
  @Transactional
  public EnvironmentView create(CreateCommand command) {
    String code = visibleCode(command.code());
    try {
      return views(
              List.of(environments.insert(code, EnvironmentType.valueOf(command.type().name()))))
          .getFirst();
    } catch (EnvironmentCodeAlreadyExistsException e) {
      throw new EnvironmentCodeTakenException(e);
    }
  }

  @Override
  @Transactional
  public EnvironmentView update(Long id, UpdateCommand command) {
    if (command.code() == null && command.type() == null) {
      throw new EmptyEnvironmentUpdateException();
    }
    String code = command.code() == null ? null : visibleCode(command.code());
    // TAR-276 (BE-SPR-DAT-13): the environment changed since the client loaded it.
    Environment current =
        environments.findByIdForUpdate(id).orElseThrow(EnvironmentNotFoundException::new);
    if (current.version() != command.version()) {
      throw new OptimisticLockingFailureException("The environment changed since it was loaded");
    }
    try {
      Environment updated =
          environments
              .update(id, code, optionalType(command.type()))
              .orElseThrow(EnvironmentNotFoundException::new);
      return views(List.of(updated)).getFirst();
    } catch (EnvironmentCodeAlreadyExistsException e) {
      throw new EnvironmentCodeTakenException(e);
    }
  }

  @Override
  @Transactional
  public void deactivate(Long id) {
    Environment environment =
        environments.findByIdForUpdate(id).orElseThrow(EnvironmentNotFoundException::new);
    if (environment.status() == EnvironmentStatus.INACTIVE) {
      return;
    }
    if (occupancy.isOccupied(id)) {
      throw new EnvironmentOccupiedException();
    }
    setStatus(id, EnvironmentStatus.INACTIVE);
  }

  @Override
  @Transactional
  public boolean lock(Long id) {
    return environments
            .findByIdForUpdate(id)
            .orElseThrow(EnvironmentNotFoundException::new)
            .status()
        == EnvironmentStatus.ACTIVE;
  }

  @Override
  @Transactional
  public void reactivate(Long id) {
    setStatus(id, EnvironmentStatus.ACTIVE);
  }

  private void setStatus(Long id, EnvironmentStatus status) {
    if (!environments.updateStatus(id, status, clock.instant())) {
      throw new EnvironmentNotFoundException();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, String> codesOf(Collection<Long> ids) {
    Map<Long, String> codes = new HashMap<>();
    if (!ids.isEmpty()) {
      environments.findAllByIds(ids).forEach(e -> codes.put(e.id(), e.code()));
    }
    return codes;
  }

  @Override
  @Transactional(readOnly = true)
  public EnvironmentView get(Long id) {
    Environment environment =
        environments.findById(id).orElseThrow(EnvironmentNotFoundException::new);
    return views(List.of(environment)).getFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public EnvironmentPage list(
      StatusFilter filter,
      PageQuery page,
      @Nullable LocalDate freeFrom,
      @Nullable LocalDate freeTo) {
    LocalDate today = LocalDate.now(clock);
    // The A2 notice counts across all pages: the active environments, which are few.
    List<Environment> active = environments.findAllByStatus(EnvironmentStatus.ACTIVE);
    Map<Long, EnvironmentOccupancies> activeOccupancy = occupanciesOf(active);
    long freeCount =
        active.stream()
            .filter(e -> of(activeOccupancy, e.id()).freeWithNothingComing(today))
            .count();
    if (freeFrom != null) {
      // TAR-237: to choose an environment for a stay or a contract; all of them in one page.
      List<Environment> free =
          active.stream()
              .filter(e -> of(activeOccupancy, e.id()).freeBetween(freeFrom, freeTo))
              .toList();
      return new EnvironmentPage(new CursorPage<>(views(free), null), freeCount);
    }
    CursorPage<Environment> found = environments.findAll(toStatus(filter), page);
    return new EnvironmentPage(
        new CursorPage<>(views(found.content()), found.nextCursor()), freeCount);
  }

  /** The occupancy of each environment, from one read of the contracts and the stays. */
  private Map<Long, EnvironmentOccupancies> occupanciesOf(List<Environment> found) {
    Map<Long, List<Occupancy>> byEnvironment = new HashMap<>();
    found.forEach(e -> byEnvironment.put(e.id(), new ArrayList<>()));
    for (Occupancy o : occupancy.occupanciesOf(byEnvironment.keySet())) {
      byEnvironment.computeIfAbsent(o.environmentId(), key -> new ArrayList<>()).add(o);
    }
    Map<Long, EnvironmentOccupancies> result = new HashMap<>();
    byEnvironment.forEach((id, list) -> result.put(id, new EnvironmentOccupancies(list)));
    return result;
  }

  /** The occupancy of the environment; one that nothing ever occupied has none. */
  private static EnvironmentOccupancies of(Map<Long, EnvironmentOccupancies> byId, Long id) {
    return Objects.requireNonNullElse(byId.get(id), NOTHING);
  }

  /** The views of those environments, in the same order, each with its occupancy today. */
  private List<EnvironmentView> views(List<Environment> found) {
    LocalDate today = LocalDate.now(clock);
    Map<Long, EnvironmentOccupancies> byEnvironment = occupanciesOf(found);
    List<EnvironmentView> views = new ArrayList<>();
    for (Environment environment : found) {
      EnvironmentOccupancies occupancies = of(byEnvironment, environment.id());
      views.add(
          new EnvironmentView(
              environment.id(),
              environment.code(),
              EnvironmentKind.valueOf(environment.type().name()),
              EnvironmentState.valueOf(environment.status().name()),
              occupancies.occupantOn(today).orElse(null),
              occupancies.nextAfter(today).orElse(null),
              occupancies.freeSince(
                  today, LocalDate.ofInstant(environment.createdAt(), clock.getZone())),
              environment.deactivatedAt(),
              environment.version()));
    }
    return views;
  }

  private static @Nullable EnvironmentStatus toStatus(StatusFilter filter) {
    return switch (filter) {
      case ACTIVE -> EnvironmentStatus.ACTIVE;
      case INACTIVE -> EnvironmentStatus.INACTIVE;
      case ALL -> null;
    };
  }

  private static @Nullable EnvironmentType optionalType(@Nullable EnvironmentKind type) {
    return type == null ? null : EnvironmentType.valueOf(type.name());
  }

  /** Whatever the caller sent, the stored code has no edge spaces and only {@link #VALID_CODE}. */
  private static String visibleCode(String code) {
    String stripped = EDGE_SPACE.matcher(code).replaceAll("");
    if (!VALID_CODE.matcher(stripped).matches()) {
      throw new InvalidEnvironmentCodeException();
    }
    return stripped;
  }
}

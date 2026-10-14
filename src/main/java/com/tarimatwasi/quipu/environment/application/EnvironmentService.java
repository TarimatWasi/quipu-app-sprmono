package com.tarimatwasi.quipu.environment.application;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.environment.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.environment.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.environment.port.in.LockEnvironmentUseCase;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentOccupancyPort;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class EnvironmentService implements ManageEnvironmentsUseCase, LockEnvironmentUseCase {

  /**
   * An explicit visible alphabet, not a list of invisible characters to exclude: Latin letters
   * (accents and ñ included), digits, and after the first character also a space, dot, underscore,
   * slash or hyphen. Blank letters such as U+3164 and zero-width characters are outside it.
   */
  private static final Pattern VALID_CODE =
      Pattern.compile("[\\p{IsLatin}0-9][\\p{IsLatin}0-9 ._/-]*");

  /** Whitespace and Unicode separators (NBSP, EM SPACE...), which String.strip() leaves. */
  private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$");

  private final EnvironmentRepositoryPort environments;
  private final EnvironmentOccupancyPort occupancy;

  EnvironmentService(EnvironmentRepositoryPort environments, EnvironmentOccupancyPort occupancy) {
    this.environments = environments;
    this.occupancy = occupancy;
  }

  @Override
  @Transactional
  public EnvironmentView create(CreateCommand command) {
    String code = visibleCode(command.code());
    try {
      return view(environments.insert(code, EnvironmentType.valueOf(command.type().name())));
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
    try {
      return environments
          .update(id, code, optionalType(command.type()))
          .map(EnvironmentService::view)
          .orElseThrow(EnvironmentNotFoundException::new);
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
  public void lock(Long id) {
    if (environments.findByIdForUpdate(id).isEmpty()) {
      throw new EnvironmentNotFoundException();
    }
  }

  @Override
  @Transactional
  public void reactivate(Long id) {
    setStatus(id, EnvironmentStatus.ACTIVE);
  }

  private void setStatus(Long id, EnvironmentStatus status) {
    if (!environments.updateStatus(id, status)) {
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
    return environments
        .findById(id)
        .map(EnvironmentService::view)
        .orElseThrow(EnvironmentNotFoundException::new);
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<EnvironmentView> list(StatusFilter filter, PageQuery page) {
    return environments.findAll(toStatus(filter), page).map(EnvironmentService::view);
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

  private static EnvironmentView view(Environment environment) {
    return new EnvironmentView(
        environment.id(),
        environment.code(),
        EnvironmentKind.valueOf(environment.type().name()),
        EnvironmentState.valueOf(environment.status().name()));
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

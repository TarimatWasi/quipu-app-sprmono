package com.tarimatwasi.quipu.environment.application;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.environment.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentRepositoryPort;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService implements ManageEnvironmentsUseCase {

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

  EnvironmentService(EnvironmentRepositoryPort environments) {
    this.environments = environments;
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
  public EnvironmentView update(UUID id, UpdateCommand command) {
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
  @Transactional(readOnly = true)
  public EnvironmentView get(UUID id) {
    return environments
        .findById(id)
        .map(EnvironmentService::view)
        .orElseThrow(EnvironmentNotFoundException::new);
  }

  @Override
  @Transactional(readOnly = true)
  public List<EnvironmentView> list(StatusFilter filter) {
    return environments.findAll(toStatus(filter)).stream().map(EnvironmentService::view).toList();
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

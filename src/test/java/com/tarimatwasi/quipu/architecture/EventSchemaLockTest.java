package com.tarimatwasi.quipu.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * QP-EVT-05: the outbox stores each event by class name and each durable delivery by listener
 * method, and the audit trail keeps the event as JSON. Renaming an event or a listener, or changing
 * its components, breaks the publications still pending and the reading of the trail, silently.
 * This lock makes every such change explicit: the current shape must match {@code events.lock}.
 *
 * <p>To accept a change, run with {@code -Devents.lock.update=true} and commit the new lock. An
 * event whose components change must also raise its {@code version()}; a renamed or removed durable
 * listener must have no pending publication when it ships.
 */
class EventSchemaLockTest {
  private static final Path LOCK = Path.of("src/test/resources/events.lock");

  @Test
  void eventsAndDurableListeners_matchTheLock() throws IOException {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ArchitectureRules.ROOT);
    Map<String, String> events = new TreeMap<>();
    for (JavaClass type : classes) {
      if (type.isAssignableTo(DomainEvent.class) && !type.isInterface()) {
        events.put(type.getName(), describe(type.reflect()));
      }
    }
    List<String> listeners = new ArrayList<>();
    for (JavaClass type : classes) {
      for (JavaMethod method : type.getMethods()) {
        if (method.isAnnotatedWith(ApplicationModuleListener.class)) {
          listeners.add("listener " + method.getFullName());
        }
      }
    }
    List<String> current = new ArrayList<>();
    events.forEach((name, shape) -> current.add("event " + name + " " + shape));
    listeners.stream().sorted().forEach(current::add);

    if (Boolean.getBoolean("events.lock.update")) {
      Files.write(LOCK, current, StandardCharsets.UTF_8);
    }
    List<String> locked = Files.readAllLines(LOCK, StandardCharsets.UTF_8);
    assertVersionsRaised(locked, events);
    assertThat(current)
        .as(
            "events and durable listeners changed: review the outbox compatibility, then run"
                + " with -Devents.lock.update=true and commit src/test/resources/events.lock")
        .containsExactlyElementsOf(locked);
  }

  /** An event that keeps its name but changes its components must raise its version. */
  private static void assertVersionsRaised(List<String> locked, Map<String, String> events) {
    for (String line : locked) {
      if (!line.startsWith("event ")) {
        continue;
      }
      String[] parts = line.split(" ", 3);
      String shape = events.get(parts[1]);
      if (shape != null && !shape.equals(parts[2])) {
        assertThat(versionOf(shape))
            .as("%s changed its components: raise its version()", parts[1])
            .isNotEqualTo(versionOf(parts[2]));
      }
    }
  }

  private static String versionOf(String shape) {
    return shape.substring(0, shape.indexOf(' '));
  }

  /** {@code v<version> (<name>:<type>, ...)} of a record event. */
  private static String describe(Class<?> type) {
    RecordComponent[] components = type.getRecordComponents();
    String fields =
        Arrays.stream(components)
            .map(c -> c.getName() + ":" + c.getGenericType().getTypeName())
            .collect(Collectors.joining(", ", "(", ")"));
    return "v" + version(type, components) + " " + fields;
  }

  /** The version of the record, read from an instance built with neutral values. */
  private static int version(Class<?> type, RecordComponent[] components) {
    Class<?>[] types =
        Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
    Object[] values = Arrays.stream(types).map(EventSchemaLockTest::neutral).toArray();
    try {
      Constructor<?> constructor = type.getDeclaredConstructor(types);
      constructor.setAccessible(true);
      return ((DomainEvent) constructor.newInstance(values)).version();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot read the version of " + type.getName(), e);
    }
  }

  private static Object neutral(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}

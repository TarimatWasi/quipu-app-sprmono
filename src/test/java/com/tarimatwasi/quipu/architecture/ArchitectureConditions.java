package com.tarimatwasi.quipu.architecture;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rules that need their own ArchUnit condition because its catalog has no equivalent (see "Guía
 * GitHub Actions y calidad de CI", section 4.3). Each one is exercised against fixtures in {@code
 * ArchitectureConditionsTest}.
 */
final class ArchitectureConditions {

  private static final String VALUE = "org.springframework.beans.factory.annotation.Value";
  private static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";
  private static final String CONFIGURATION_PROPERTIES =
      "org.springframework.boot.context.properties.ConfigurationProperties";
  private static final String REST_CONTROLLER =
      "org.springframework.web.bind.annotation.RestController";
  private static final Set<String> VALID =
      Set.of("jakarta.validation.Valid", "org.springframework.validation.annotation.Validated");
  private static final Set<String> TO_ONE =
      Set.of("jakarta.persistence.ManyToOne", "jakarta.persistence.OneToOne");
  private static final Set<String> MAPPINGS =
      Set.of(
          "org.springframework.web.bind.annotation.RequestMapping",
          "org.springframework.web.bind.annotation.GetMapping",
          "org.springframework.web.bind.annotation.PostMapping",
          "org.springframework.web.bind.annotation.PutMapping",
          "org.springframework.web.bind.annotation.PatchMapping",
          "org.springframework.web.bind.annotation.DeleteMapping");
  private static final Pattern CORE_PATH = Pattern.compile("^/api/v(\\d+)/(.*)$");
  private static final Pattern BFF_PATH = Pattern.compile("^/bff/.*$");

  /** Allowed same-module dependencies per layer (BE-SPR-ARQ-03, table 3.2 of the guide). */
  private static final Map<String, Set<String>> ALLOWED_LAYER_DEPENDENCIES =
      Map.of(
          "domain", Set.of(),
          "application", Set.of("domain", "port.in", "port.out"),
          "port.in", Set.of("domain"),
          "port.out", Set.of("domain"),
          "adapter.in", Set.of("port.in"),
          "adapter.out", Set.of("domain", "port.out"),
          "config",
              Set.of("domain", "application", "port.in", "port.out", "adapter.in", "adapter.out"));

  /** The packages of a hexagonal module (TAR-226, D-72), relative to the module package. */
  static final List<String> HEXAGONAL_LAYERS =
      List.of(
          "domain",
          "application.service",
          "application.port.in",
          "application.port.out",
          "infrastructure.adapter.in",
          "infrastructure.adapter.out",
          "infrastructure.config");

  /**
   * Package of each layer of the table, relative to the module: the hexagonal packages first, then
   * the flat ones of the modules not yet moved (bff, until batch 2 of TAR-226). First match wins.
   */
  private static final List<Map.Entry<String, String>> LAYER_PACKAGES =
      List.of(
          Map.entry("domain", "domain"),
          Map.entry("application.service", "application"),
          Map.entry("application.port.in", "port.in"),
          Map.entry("application.port.out", "port.out"),
          Map.entry("infrastructure.adapter.in", "adapter.in"),
          Map.entry("infrastructure.adapter.out", "adapter.out"),
          Map.entry("infrastructure.config", "config"),
          Map.entry("application", "application"),
          Map.entry("port.in", "port.in"),
          Map.entry("port.out", "port.out"),
          Map.entry("adapter.in", "adapter.in"),
          Map.entry("adapter.out", "adapter.out"),
          Map.entry("config", "config"));

  private ArchitectureConditions() {}

  /** BE-SPR-CFG-03: at most one {@code @Value} per class. */
  static ArchRule atMostOneValuePerClass() {
    return classes()
        .should(
            condition(
                "declare at most one @Value",
                (c, events) -> {
                  var count =
                      c.getCodeUnits().stream()
                          .flatMap(u -> u.getParameterAnnotations().stream())
                          .flatMap(Set::stream)
                          .filter(a -> a.getRawType().getName().equals(VALUE))
                          .count();
                  if (count > 1) {
                    events.add(violated(c, c.getName() + " declares " + count + " @Value"));
                  }
                }));
  }

  /** BE-SPR-CFG-04: no {@code @Value} on fields and no SpEL inside {@code @Value}. */
  static ArchRule noValueOnFieldsNorSpel() {
    return classes()
        .should(
            condition(
                "not use @Value on fields or with SpEL",
                (c, events) -> {
                  c.getFields().stream()
                      .filter(f -> f.isAnnotatedWith(VALUE))
                      .forEach(f -> events.add(violated(f, f.getFullName() + " uses @Value")));
                  valueAnnotations(c)
                      .filter(a -> String.valueOf(a.get("value").orElse("")).contains("#{"))
                      .forEach(a -> events.add(violated(c, c.getName() + " uses SpEL in @Value")));
                }));
  }

  /** BE-SPR-WEB-03: every {@code @RequestBody} parameter is also {@code @Valid}. */
  static ArchRule requestBodyIsValidated() {
    return classes()
        .should(
            condition(
                "validate every @RequestBody",
                (c, events) ->
                    c.getMethods()
                        .forEach(
                            m ->
                                m.getParameterAnnotations()
                                    .forEach(
                                        annotations -> {
                                          var names = names(annotations);
                                          if (names.contains(REQUEST_BODY)
                                              && names.stream().noneMatch(VALID::contains)) {
                                            events.add(
                                                violated(m, m.getFullName() + " lacks @Valid"));
                                          }
                                        }))));
  }

  /** BE-SPR-DAT-07: {@code @ManyToOne} and {@code @OneToOne} declare {@code fetch = LAZY}. */
  static ArchRule toOneRelationsAreLazy() {
    return classes()
        .should(
            condition(
                "declare LAZY on @ManyToOne and @OneToOne (fields and getters)",
                (c, events) ->
                    Stream.<JavaMember>concat(c.getFields().stream(), c.getMethods().stream())
                        .forEach(
                            member ->
                                member.getAnnotations().stream()
                                    .filter(a -> TO_ONE.contains(a.getRawType().getName()))
                                    .filter(a -> !isLazy(a))
                                    .forEach(
                                        a ->
                                            events.add(
                                                violated(
                                                    member,
                                                    member.getFullName() + " is not LAZY"))))));
  }

  /** QP-SPRMONO-API-02: route prefixes per surface ({@code /bff/} or {@code /api/v<n>/}). */
  static ArchRule routesUseTheSurfacePrefix() {
    return classes()
        .that()
        .resideInAPackage("..adapter.in.rest..")
        .and()
        .areAnnotatedWith(REST_CONTROLLER)
        .should(
            condition(
                "map only routes under /bff/ (bff module) or /api/v<n>/ (the rest)",
                (c, events) -> {
                  var pattern = c.getPackageName().contains(".bff.") ? BFF_PATH : CORE_PATH;
                  routes(c).stream()
                      .filter(route -> !pattern.matcher(route).matches())
                      .forEach(
                          route ->
                              events.add(violated(c, c.getName() + " maps the route " + route)));
                }));
  }

  /** QP-SPRMONO-API-03 (structural part): a {@code /api/v2+} route coexists with its v1. */
  static ArchRule newApiVersionsCoexistWithV1() {
    return classes()
        .that()
        .resideInAPackage("..adapter.in.rest..")
        .and()
        .areAnnotatedWith(REST_CONTROLLER)
        .should(
            new ArchCondition<JavaClass>("keep the /v1 route next to every /v2+ route") {
              private final Set<String> v1Resources = new HashSet<>();

              @Override
              public void init(Collection<JavaClass> all) {
                all.stream()
                    .flatMap(c -> routes(c).stream())
                    .map(CORE_PATH::matcher)
                    .filter(Matcher::matches)
                    .filter(m -> m.group(1).equals("1"))
                    .forEach(m -> v1Resources.add(m.group(2)));
              }

              @Override
              public void check(JavaClass c, ConditionEvents events) {
                routes(c).stream()
                    .map(CORE_PATH::matcher)
                    .filter(Matcher::matches)
                    .filter(m -> !m.group(1).equals("1") && !v1Resources.contains(m.group(2)))
                    .forEach(
                        m ->
                            events.add(
                                violated(
                                    c, c.getName() + " has /v" + m.group(1) + " without /v1")));
              }
            });
  }

  /** BE-SPR-ARQ-03: same-module dependencies between layers follow the guide table. */
  static ArchRule layersFollowTheTable(String rootPackage) {
    return classes()
        .that(DescribedPredicate.describe("are in a module", c -> layerOf(c, rootPackage) != null))
        .should(
            condition(
                "depend only on the layers that the table allows",
                (c, events) -> {
                  var from = layerOf(c, rootPackage);
                  for (Dependency d : c.getDirectDependenciesFromSelf()) {
                    var to = layerOf(d.getTargetClass(), rootPackage);
                    if (to != null
                        && moduleOf(c, rootPackage)
                            .equals(moduleOf(d.getTargetClass(), rootPackage))
                        && !to.equals(from)
                        && !ALLOWED_LAYER_DEPENDENCIES.get(from).contains(to)) {
                      events.add(violated(d, d.getDescription()));
                    }
                  }
                }));
  }

  /**
   * TAR-226 (D-72): every class of a hexagonal module lives in one of {@link #HEXAGONAL_LAYERS},
   * and no class of its domain or application depends on an infrastructure package.
   */
  static ArchRule hexagonalLayout(String rootPackage, Set<String> modules) {
    DescribedPredicate<JavaClass> inModule =
        DescribedPredicate.describe(
            "are in the hexagonal modules " + new TreeSet<>(modules),
            c -> pathInModule(c.getPackageName(), rootPackage, modules) != null);
    DescribedPredicate<JavaClass> inCore =
        DescribedPredicate.describe(
            "are in domain or application",
            c -> {
              var path = pathInModule(c.getPackageName(), rootPackage, modules);
              return path != null && (isOrUnder(path, "domain") || isOrUnder(path, "application"));
            });
    DescribedPredicate<JavaClass> inInfrastructure =
        DescribedPredicate.describe(
            "are in an infrastructure package",
            c -> {
              var name = c.getPackageName();
              if (!name.startsWith(rootPackage + ".")) {
                return false;
              }
              var parts = name.substring(rootPackage.length() + 1).split("\\.", 2);
              return parts.length == 2 && isOrUnder(parts[1], "infrastructure");
            });
    return CompositeArchRule.of(
            classes()
                .that(inModule)
                .and()
                .doNotHaveSimpleName("package-info")
                .should(
                    condition(
                        "reside in one of the layers " + HEXAGONAL_LAYERS,
                        (c, events) -> {
                          var path = pathInModule(c.getPackageName(), rootPackage, modules);
                          if (HEXAGONAL_LAYERS.stream().noneMatch(l -> isOrUnder(path, l))) {
                            events.add(violated(c, c.getName() + " is outside the layers"));
                          }
                        })))
        .and(
            noClasses()
                .that(inModule)
                .and(inCore)
                .should()
                .dependOnClassesThat(inInfrastructure)
                .because("domain and application never see the infrastructure"));
  }

  /** The package path after {@code root.<module>} ("" for the module root), or null outside. */
  private static String pathInModule(String name, String rootPackage, Set<String> modules) {
    for (var module : modules) {
      var base = rootPackage + "." + module;
      if (name.equals(base)) {
        return "";
      }
      if (name.startsWith(base + ".")) {
        return name.substring(base.length() + 1);
      }
    }
    return null;
  }

  private static boolean isOrUnder(String path, String layer) {
    return path.equals(layer) || path.startsWith(layer + ".");
  }

  /** BE-SPR-CFG-01: {@code @ConfigurationProperties} prefixes start with {@code app.}. */
  static ArchRule propertiesPrefixIsApp() {
    return classes()
        .that()
        .areAnnotatedWith(CONFIGURATION_PROPERTIES)
        .should(
            condition(
                "use a prefix under app.",
                (c, events) -> {
                  var annotation = c.getAnnotationOfType(CONFIGURATION_PROPERTIES);
                  var prefix =
                      String.valueOf(
                          annotation
                              .tryGetExplicitlyDeclaredProperty("prefix")
                              .or(() -> annotation.tryGetExplicitlyDeclaredProperty("value"))
                              .orElse(""));
                  if (!prefix.startsWith("app.")) {
                    events.add(violated(c, c.getName() + " uses the prefix " + prefix));
                  }
                }));
  }

  /** BE-SPR-TST-05: {@code FooTest} lives in the package of {@code Foo}. */
  static ArchRule testsLiveWithTheirClass(Set<String> exemptPackageSuffixes) {
    return classes()
        .that()
        .haveSimpleNameEndingWith("Test")
        .should(
            condition(
                "be in the same package as the class under test",
                (c, events) -> {
                  var exempt =
                      exemptPackageSuffixes.stream().anyMatch(s -> c.getPackageName().endsWith(s));
                  var name = c.getSimpleName();
                  var tested = name.substring(0, name.length() - 4);
                  var found =
                      c.getPackage().getClasses().stream()
                          .anyMatch(other -> other.getSimpleName().equals(tested));
                  if (!exempt && !found) {
                    events.add(violated(c, c.getName() + " has no " + tested + " next to it"));
                  }
                }));
  }

  private static ArchCondition<JavaClass> condition(String description, Check check) {
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        check.apply(item, events);
      }
    };
  }

  @FunctionalInterface
  private interface Check {
    void apply(JavaClass c, ConditionEvents events);
  }

  private static Stream<JavaAnnotation<?>> valueAnnotations(JavaClass c) {
    Stream<JavaAnnotation<?>> onParameters =
        c.getCodeUnits().stream()
            .flatMap(u -> u.getParameterAnnotations().stream())
            .flatMap(Set::stream);
    Stream<JavaAnnotation<?>> onFields =
        c.getFields().stream().flatMap(f -> f.getAnnotations().stream());
    Stream<JavaAnnotation<?>> onMethods =
        c.getMethods().stream().flatMap(m -> m.getAnnotations().stream());
    return Stream.of(onParameters, onFields, onMethods)
        .flatMap(s -> s)
        .filter(a -> a.getRawType().getName().equals(VALUE));
  }

  private static Set<String> names(Set<? extends JavaAnnotation<?>> annotations) {
    Set<String> names = new HashSet<>();
    annotations.forEach(a -> names.add(a.getRawType().getName()));
    return names;
  }

  private static boolean isLazy(JavaAnnotation<?> relation) {
    return relation
        .tryGetExplicitlyDeclaredProperty("fetch")
        .filter(JavaEnumConstant.class::isInstance)
        .map(JavaEnumConstant.class::cast)
        .map(e -> e.name().equals("LAZY"))
        .orElse(false);
  }

  private static List<String> routes(JavaClass c) {
    var bases = paths(c.getAnnotations());
    var routes = new ArrayList<String>();
    for (var method : c.getMethods()) {
      var mapped =
          method.getAnnotations().stream()
              .anyMatch(a -> MAPPINGS.contains(a.getRawType().getName()));
      if (mapped) {
        for (var base : bases) {
          for (var path : paths(method.getAnnotations())) {
            routes.add(join(base, path));
          }
        }
      }
    }
    return routes;
  }

  /** Joins a class and a method mapping like Spring: adds the missing slash, collapses repeats. */
  static String join(String base, String path) {
    var joined = ("/" + base + "/" + path).replaceAll("/{2,}", "/");
    return joined.length() > 1 && joined.endsWith("/")
        ? joined.substring(0, joined.length() - 1)
        : joined;
  }

  /** The {@code value} and {@code path} of the mapping annotations; one empty path if none. */
  private static List<String> paths(Collection<? extends JavaAnnotation<?>> annotations) {
    var found = new ArrayList<String>();
    for (var a : annotations) {
      if (MAPPINGS.contains(a.getRawType().getName())) {
        for (var key : List.of("value", "path")) {
          a.tryGetExplicitlyDeclaredProperty(key).ifPresent(v -> found.addAll(asStrings(v)));
        }
      }
    }
    return found.isEmpty() ? List.of("") : found;
  }

  private static List<String> asStrings(Object value) {
    if (value instanceof String[] array) {
      return List.of(array);
    }
    return List.of(String.valueOf(value));
  }

  /** The layer of a class inside a module, or null for the root, shared and package-info. */
  private static String layerOf(JavaClass c, String rootPackage) {
    var prefix = rootPackage + ".";
    var name = c.getPackageName();
    if (!name.startsWith(prefix) || c.getSimpleName().equals("package-info")) {
      return null;
    }
    var parts = name.substring(prefix.length()).split("\\.", 2);
    if (parts[0].equals("shared") || parts.length < 2) {
      return null;
    }
    for (var entry : LAYER_PACKAGES) {
      if (isOrUnder(parts[1], entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String moduleOf(JavaClass c, String rootPackage) {
    var name = c.getPackageName();
    var start = Math.min(name.length(), rootPackage.length() + 1);
    return name.substring(start).split("\\.", 2)[0];
  }
}

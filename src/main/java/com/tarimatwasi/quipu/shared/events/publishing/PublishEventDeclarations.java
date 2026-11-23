package com.tarimatwasi.quipu.shared.events.publishing;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Compiles every {@link PublishEvent} of every bean while the context starts and refuses to start
 * when one cannot build its event: unparsable SpEL, an unknown parameter, {@code #result} on a void
 * method, a wrong number of expressions, or an expression whose static type cannot fill its record
 * component. The static type is inferred for the usual shapes ({@code #param}, {@code #result} and
 * chains of accessors such as {@code #result.guestId()}); other expressions are checked at runtime.
 */
@Component
public class PublishEventDeclarations implements BeanPostProcessor {
  private static final Pattern VARIABLE = Pattern.compile("#(\\w+)");
  private static final Pattern ACCESSOR_CHAIN = Pattern.compile("^#(\\w+)((?:\\.\\w+\\(\\))*)$");
  private static final Pattern ACCESSOR = Pattern.compile("\\.(\\w+)\\(\\)");
  private static final Set<String> BUILT_IN = Set.of("result", "root", "this");

  private final ExpressionParser parser = new SpelExpressionParser();
  private final ParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();
  private final Map<Method, List<Compiled>> compiled = new ConcurrentHashMap<>();

  /** One declaration ready to build its event. */
  record Compiled(
      Class<? extends DomainEvent> type,
      Constructor<? extends DomainEvent> constructor,
      List<Class<?>> componentTypes,
      List<Expression> expressions,
      @Nullable Expression when) {}

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    validate(AopUtils.getTargetClass(bean));
    return bean;
  }

  /** Compiles the declarations of the class; fails with all of its errors at once. */
  public void validate(Class<?> type) {
    List<String> errors = new ArrayList<>();
    ReflectionUtils.doWithMethods(
        ClassUtils.getUserClass(type),
        method -> errors.addAll(compile(method)),
        method -> !declarations(method).isEmpty());
    if (!errors.isEmpty()) {
      throw new InvalidPublishEventException(errors);
    }
  }

  /** The events the method declares, built from its invocation; null result for void. */
  public List<DomainEvent> build(
      Method method,
      Object target,
      Object[] arguments,
      @Nullable Object result,
      EventMetadata metadata) {
    List<Compiled> declarations = compiled.get(method);
    if (declarations == null) {
      validate(method.getDeclaringClass());
      declarations = compiled.getOrDefault(method, List.of());
    }
    var evaluation = new MethodBasedEvaluationContext(target, method, arguments, names);
    evaluation.setVariable("result", result);
    List<DomainEvent> events = new ArrayList<>(declarations.size());
    for (Compiled declaration : declarations) {
      Expression when = declaration.when();
      if (when != null && !Boolean.TRUE.equals(when.getValue(evaluation, Boolean.class))) {
        continue;
      }
      @Nullable Object[] values = new Object[declaration.componentTypes().size()];
      values[0] = metadata;
      for (int i = 1; i < values.length; i++) {
        values[i] =
            declaration
                .expressions()
                .get(i - 1)
                .getValue(evaluation, declaration.componentTypes().get(i));
      }
      try {
        events.add(declaration.constructor().newInstance(values));
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Cannot build " + declaration.type().getSimpleName(), e);
      }
    }
    return events;
  }

  private static List<PublishEvent> declarations(Method method) {
    return List.copyOf(
        AnnotatedElementUtils.findMergedRepeatableAnnotations(method, PublishEvent.class));
  }

  private List<String> compile(Method method) {
    String where = method.getDeclaringClass().getSimpleName() + "." + method.getName();
    @Nullable String @Nullable [] parameterNames = names.getParameterNames(method);
    Map<String, Class<?>> variables = new HashMap<>();
    for (int i = 0; parameterNames != null && i < parameterNames.length; i++) {
      String name = parameterNames[i];
      if (name != null) {
        variables.put(name, method.getParameterTypes()[i]);
      }
    }
    if (method.getReturnType() != void.class) {
      variables.put("result", method.getReturnType());
    }
    List<String> errors = new ArrayList<>();
    List<Compiled> result = new ArrayList<>();
    for (PublishEvent declaration : declarations(method)) {
      Compiled one = compile(where, declaration, variables, errors);
      if (one != null) {
        result.add(one);
      }
    }
    if (errors.isEmpty()) {
      compiled.put(method, List.copyOf(result));
    }
    return errors;
  }

  private @Nullable Compiled compile(
      String where,
      PublishEvent declaration,
      Map<String, Class<?>> variables,
      List<String> errors) {
    String event = declaration.type().getSimpleName();
    RecordComponent[] components = declaration.type().getRecordComponents();
    if (components == null
        || components.length == 0
        || components[0].getType() != EventMetadata.class) {
      errors.add(where + ": " + event + " must be a record whose first component is EventMetadata");
      return null;
    }
    if (components.length - 1 != declaration.args().length) {
      errors.add(
          where
              + ": "
              + event
              + " needs "
              + (components.length - 1)
              + " expressions, found "
              + declaration.args().length);
      return null;
    }
    int before = errors.size();
    List<Expression> expressions = new ArrayList<>();
    for (int i = 0; i < declaration.args().length; i++) {
      String source = declaration.args()[i];
      RecordComponent component = components[i + 1];
      try {
        expressions.add(parser.parseExpression(source));
      } catch (ParseException e) {
        errors.add(where + ": cannot parse '" + source + "'");
        continue;
      }
      checkVariables(where, source, variables, errors);
      checkType(where, event, source, component, variables, errors);
    }
    Expression when = condition(where, declaration.when(), variables, errors);
    if (errors.size() > before) {
      return null;
    }
    return compiled(where, declaration.type(), components, expressions, when, errors);
  }

  /** The parsed {@code when}, or null when it is empty or broken (then with its error). */
  private @Nullable Expression condition(
      String where, String source, Map<String, Class<?>> variables, List<String> errors) {
    if (source.isBlank()) {
      return null;
    }
    try {
      Expression when = parser.parseExpression(source);
      checkVariables(where, source, variables, errors);
      checkCondition(where, source, variables, errors);
      return when;
    } catch (ParseException e) {
      errors.add(where + ": cannot parse when '" + source + "'");
      return null;
    }
  }

  private static @Nullable Compiled compiled(
      String where,
      Class<? extends DomainEvent> type,
      RecordComponent[] components,
      List<Expression> expressions,
      @Nullable Expression when,
      List<String> errors) {
    Class<?>[] types = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
    }
    try {
      Constructor<? extends DomainEvent> constructor = type.getDeclaredConstructor(types);
      ReflectionUtils.makeAccessible(constructor);
      return new Compiled(type, constructor, List.of(types), List.copyOf(expressions), when);
    } catch (NoSuchMethodException e) {
      errors.add(where + ": " + type.getSimpleName() + " has no canonical constructor");
      return null;
    }
  }

  private static void checkVariables(
      String where, String source, Map<String, Class<?>> variables, List<String> errors) {
    Matcher found = VARIABLE.matcher(source);
    while (found.find()) {
      String variable = found.group(1);
      if (variable.equals("result") && !variables.containsKey("result")) {
        errors.add(where + ": '#result' on a void method");
      } else if (!BUILT_IN.contains(variable) && !variables.containsKey(variable)) {
        errors.add(where + ": unknown variable '#" + variable + "'");
      }
    }
  }

  /** A condition whose static type is known must be a boolean. */
  private static void checkCondition(
      String where, String source, Map<String, Class<?>> variables, List<String> errors) {
    Class<?> type = staticType(where, source, variables, errors);
    if (type != null && ClassUtils.resolvePrimitiveIfNecessary(type) != Boolean.class) {
      errors.add(where + ": when '" + source + "' is " + type.getSimpleName() + ", not boolean");
    }
  }

  /** The static type of {@code #var.a().b()}, or null when it is another kind of expression. */
  private static @Nullable Class<?> staticType(
      String where, String source, Map<String, Class<?>> variables, List<String> errors) {
    Matcher chain = ACCESSOR_CHAIN.matcher(source.strip());
    if (!chain.matches() || !variables.containsKey(chain.group(1))) {
      return null;
    }
    Class<?> type = variables.get(chain.group(1));
    Matcher accessor = ACCESSOR.matcher(chain.group(2));
    while (accessor.find()) {
      Method next = ReflectionUtils.findMethod(type, accessor.group(1));
      if (next == null || next.getParameterCount() != 0) {
        errors.add(where + ": " + type.getSimpleName() + " has no " + accessor.group(1) + "()");
        return null;
      }
      type = next.getReturnType();
    }
    return type;
  }

  /** Infers the static type of {@code #var.a().b()} and compares it with the component. */
  private static void checkType(
      String where,
      String event,
      String source,
      RecordComponent component,
      Map<String, Class<?>> variables,
      List<String> errors) {
    Class<?> type = staticType(where, source, variables, errors);
    if (type == null) {
      return;
    }
    Class<?> expected = ClassUtils.resolvePrimitiveIfNecessary(component.getType());
    if (!expected.isAssignableFrom(ClassUtils.resolvePrimitiveIfNecessary(type))) {
      errors.add(
          where
              + ": '"
              + source
              + "' is "
              + type.getSimpleName()
              + " but "
              + event
              + "."
              + component.getName()
              + " is "
              + component.getType().getSimpleName());
    }
  }
}

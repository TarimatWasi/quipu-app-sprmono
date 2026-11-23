package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Spanish text of a notification, written when it is read from the facts it keeps: nothing
 * personal is stored, and a later correction shows corrected (contract v0.27.0).
 */
public final class NotificationTexts {
  private static final Locale SPANISH = Locale.forLanguageTag("es-PE");
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SPANISH);
  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("MMMM 'de' yyyy", SPANISH);

  /** Title and body as the bell shows them. */
  public record Text(String title, String body) {}

  private NotificationTexts() {}

  /** Title and body with the facts to fill, by name between braces. */
  private record Template(String title, String body) {}

  private static final Pattern FACT = Pattern.compile("\\{(\\w+)\\}");

  /** Every kind has its text: a kind without one stops the application when it loads. */
  private static final Map<Kind, Template> TEMPLATES = templates();

  private static Map<Kind, Template> templates() {
    Map<Kind, Template> all = new EnumMap<>(Kind.class);
    all.put(
        Kind.FINE_APPLIED,
        new Template(
            "Nueva multa", "Se registró una multa de {amount} correspondiente a {month}."));
    all.put(
        Kind.CONTRACT_SIGNED,
        new Template(
            "Contrato registrado",
            "Tu contrato empieza el {startDate}, con una renta mensual de {monthlyAmount}."));
    all.put(
        Kind.CONTRACT_TERMINATED,
        new Template("Contrato finalizado", "La administración finalizó tu contrato."));
    all.put(
        Kind.PAYMENT_PROOF_SUBMITTED,
        new Template(
            "Comprobante por revisar", "Un huésped envió un pago de {amount}{of} para revisar."));
    all.put(
        Kind.ONBOARDING_COMPLETED,
        new Template("Registro completado", "Un huésped terminó su registro."));
    all.put(
        Kind.GUEST_DOCUMENT_UPLOADED,
        new Template("Documento nuevo", "Un huésped subió un documento."));
    all.put(
        Kind.CONTRACT_EXPIRING,
        new Template("Contrato por vencer", "Un contrato termina el {endDate}."));
    all.put(
        Kind.PAYMENT_OVERDUE,
        new Template("Pago vencido", "Un pago de {amount}{of} está vencido desde el {dueDate}."));
    all.put(
        Kind.PAYMENT_APPROVED,
        new Template("Pago aprobado", "Tu pago de {amount}{of} fue aprobado."));
    all.put(
        Kind.PAYMENT_REJECTED,
        new Template(
            "Pago observado",
            "Tu pago de {amount}{of} fue observado. Revisa el motivo y envíalo de nuevo."));
    all.put(
        Kind.REFUND_CREATED,
        new Template(
            "Devolución registrada", "Se registró una devolución de {amount} a tu favor."));
    all.put(
        Kind.STAY_ENDING,
        new Template("Estadía por terminar", "Una estadía termina el {endDate}."));
    all.put(
        Kind.ACCESS_EXPIRED,
        new Template(
            "Acceso vencido", "Un acceso venció el {expiredOn} sin usarse: entrega uno nuevo."));
    all.put(
        Kind.PAYMENT_DUE,
        new Template("Pago pendiente", "Tu pago de {amount}{of} vence el {dueDate}."));
    for (Kind kind : Kind.values()) {
      if (!all.containsKey(kind)) {
        throw new IllegalStateException("The notification " + kind + " has no text");
      }
    }
    return Collections.unmodifiableMap(all);
  }

  /** The text of a kind of notification with its facts. */
  public static Text of(Kind kind, Map<String, String> facts) {
    Template template = Objects.requireNonNull(TEMPLATES.get(kind));
    Matcher fact = FACT.matcher(template.body());
    StringBuilder body = new StringBuilder();
    while (fact.find()) {
      fact.appendReplacement(body, Matcher.quoteReplacement(value(fact.group(1), facts)));
    }
    fact.appendTail(body);
    return new Text(template.title(), body.toString());
  }

  /** A fact as the text shows it: amounts in soles, months and days in Spanish. */
  private static String value(String name, Map<String, String> facts) {
    return switch (name) {
      case "amount", "monthlyAmount" -> soles(facts, name);
      case "month" -> month(facts, name);
      case "of" -> of(facts);
      default -> day(facts, name);
    };
  }

  private static String soles(Map<String, String> facts, String name) {
    return "S/ "
        + new BigDecimal(facts.getOrDefault(name, "0"))
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString();
  }

  /** " de octubre de 2026" for a period of a contract; nothing for a stay. */
  private static String of(Map<String, String> facts) {
    return facts.containsKey("month") ? " de " + month(facts, "month") : "";
  }

  private static String month(Map<String, String> facts, String name) {
    return YearMonth.parse(facts.getOrDefault(name, "2000-01")).format(MONTH);
  }

  private static String day(Map<String, String> facts, String name) {
    return LocalDate.parse(facts.getOrDefault(name, "2000-01-01")).format(DAY);
  }
}

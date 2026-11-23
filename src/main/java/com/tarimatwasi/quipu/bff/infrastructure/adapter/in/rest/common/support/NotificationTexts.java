package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

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

  /** The text of a kind of notification with its facts. */
  public static Text of(Kind kind, Map<String, String> facts) {
    return switch (kind) {
      case FINE_APPLIED ->
          new Text(
              "Nueva multa",
              "Se registró una multa de "
                  + soles(facts, "amount")
                  + " correspondiente a "
                  + month(facts, "month")
                  + ".");
      case CONTRACT_SIGNED ->
          new Text(
              "Contrato registrado",
              "Tu contrato empieza el "
                  + day(facts, "startDate")
                  + ", con una renta mensual de "
                  + soles(facts, "monthlyAmount")
                  + ".");
      case CONTRACT_TERMINATED ->
          new Text("Contrato finalizado", "La administración finalizó tu contrato.");
      case PAYMENT_PROOF_SUBMITTED ->
          new Text(
              "Comprobante por revisar",
              "Un huésped envió un pago de "
                  + soles(facts, "amount")
                  + of(facts)
                  + " para revisar.");
      case ONBOARDING_COMPLETED ->
          new Text("Registro completado", "Un huésped terminó su registro.");
      case GUEST_DOCUMENT_UPLOADED -> new Text("Documento nuevo", "Un huésped subió un documento.");
      case CONTRACT_EXPIRING -> new Text("Contrato por vencer", "Un contrato vence pronto.");
      case PAYMENT_OVERDUE -> new Text("Pago vencido", "Un huésped tiene un pago vencido.");
      case PAYMENT_APPROVED ->
          new Text(
              "Pago aprobado",
              "Tu pago de " + soles(facts, "amount") + of(facts) + " fue aprobado.");
      case PAYMENT_REJECTED ->
          new Text(
              "Pago observado",
              "Tu pago de "
                  + soles(facts, "amount")
                  + of(facts)
                  + " fue observado. Revisa el motivo y envíalo de nuevo.");
      case REFUND_CREATED ->
          new Text(
              "Devolución registrada",
              "Se registró una devolución de " + soles(facts, "amount") + " a tu favor.");
      case PAYMENT_DUE -> new Text("Pago por vencer", "Tienes un pago por vencer.");
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

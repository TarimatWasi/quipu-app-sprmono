package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.FrontendOriginPort;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * TAR-309: the eight emails of the system as the design of the page "Correos" shows them. Every
 * email carries the plain text too, the one that always existed, so a client that does not show
 * HTML loses nothing. The styles are inline because email clients ignore style sheets. Every value
 * that comes from a person or an account is escaped. The emblem is served by the frontend, which is
 * the origin that CORS already trusts ({@link FrontendOriginPort}).
 */
@Component
class EmailTemplates {

  private static final String GEORGIA = "Georgia, 'Times New Roman', serif";
  private static final String SANS = "Arial, Helvetica, sans-serif";
  private static final String INK = "#141c18";
  private static final String MUTED = "#3d4842";
  private static final String GREEN = "#1e6b4a";
  private static final String CREAM = "#f4f1ea";
  private static final String REVIEW = "Revísalo con los demás administradores.";

  private final String frontendOrigin;

  EmailTemplates(FrontendOriginPort frontend) {
    this.frontendOrigin = frontend.frontendOrigin().replaceAll("/+$", "");
  }

  /** The recovery email, also the link of an administrator without name (TAR-127). */
  MailContent recovery(String link, long validMinutes) {
    String text =
        "Hola,\n\n"
            + "Recibimos una solicitud para restablecer tu contraseña de Quipu. Abre este enlace"
            + " para elegir una nueva; vale "
            + validMinutes
            + " minutos y solo se puede usar una vez:\n\n"
            + link
            + "\n\n"
            + "Si no lo pediste, ignora este correo: tu contraseña no cambia.\n";
    String body =
        paragraph("Hola,")
            + paragraph(
                "Recibimos una solicitud para restablecer tu contraseña de Quipu. Abre el enlace"
                    + " para elegir una nueva.")
            + button(link, "Elegir una contraseña nueva")
            + callout(
                false,
                "Antes de abrirlo",
                "El enlace vale " + validMinutes + " minutos y solo se puede usar una vez.")
            + fallback(link)
            + mutedParagraph("Si no lo pediste, ignora este correo: tu contraseña no cambia.");
    return mail(
        "Restablece tu contraseña de Quipu",
        text,
        "Elige una contraseña nueva: el enlace vale " + validMinutes + " minutos.",
        "Tu cuenta",
        "Restablece tu contraseña",
        body,
        "Recibes este correo porque alguien pidió restablecer la contraseña de tu cuenta de"
            + " Quipu.");
  }

  /** The link a new administrator uses to choose its first password (RF-22). */
  MailContent administratorAccess(String link, long validMinutes) {
    String text =
        "Hola,\n\n"
            + "Se creó tu cuenta de administrador de Quipu. Abre este enlace para elegir tu"
            + " contraseña; vale "
            + validMinutes
            + " minutos y solo se puede usar una vez:\n\n"
            + link
            + "\n\n"
            + "Si el enlace vence, usa \"Olvidé mi contraseña\" con este correo para recibir"
            + " otro.\n";
    String body =
        paragraph("Hola,")
            + paragraph(
                "Se creó tu cuenta de administrador de Quipu. Abre el enlace para elegir tu"
                    + " contraseña.")
            + button(link, "Elegir mi contraseña")
            + callout(
                false,
                "Antes de abrirlo",
                "El enlace vale " + validMinutes + " minutos y solo se puede usar una vez.")
            + fallback(link)
            + mutedParagraph(
                "Si el enlace vence, usa «Olvidé mi contraseña» con este correo para recibir"
                    + " otro.");
    return mail(
        "Tu cuenta de administrador de Quipu",
        text,
        "Elige tu contraseña: el enlace vale " + validMinutes + " minutos.",
        "Administración",
        "Ya tienes tu cuenta de administrador",
        body,
        "Recibes este correo porque otro administrador creó una cuenta de administrador de Quipu"
            + " con esta dirección.");
  }

  /** RF-11: the access of a guest as an activation link. */
  MailContent guestAccessLink(String link) {
    String text =
        "Hola, este es tu enlace para activar tu acceso a Quipu y elegir tu contraseña:\n"
            + link
            + "\nVale 30 minutos y solo se puede usar una vez.";
    String body =
        paragraph("Hola,")
            + paragraph("Este es tu enlace para activar tu acceso a Quipu y elegir tu contraseña.")
            + button(link, "Activar mi acceso")
            + callout(false, "Antes de abrirlo", "Vale 30 minutos y solo se puede usar una vez.")
            + fallback(link);
    return mail(
        "Tu acceso a Quipu",
        text,
        "Activa tu acceso y elige tu contraseña: vale 30 minutos.",
        "Tu acceso",
        "Activa tu acceso a Quipu",
        body,
        "Recibes este correo porque la administración de Quipu te dio acceso.");
  }

  /** RF-11: the access of a guest as a temporary password. */
  MailContent guestAccessPassword(String password) {
    String text =
        "Hola, esta es tu contraseña temporal para ingresar a Quipu con tu documento: "
            + password
            + "\nVale 30 minutos; al ingresar elegirás tu propia contraseña.";
    String body =
        paragraph("Hola,")
            + paragraph(
                "Esta es tu contraseña temporal para ingresar a Quipu con tu número de documento."
                    + " Al ingresar elegirás tu propia contraseña.")
            + secret("Tu contraseña temporal", password, "Vale 30 minutos")
            + button(frontendOrigin + "/", "Ir a Quipu")
            + "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;color:"
            + MUTED
            + "\">Escríbela como está, respetando mayúsculas y minúsculas.</p>";
    return mail(
        "Tu acceso a Quipu",
        text,
        "Entra con tu documento y esta contraseña temporal: vale 30 minutos.",
        "Tu acceso",
        "Entra a Quipu con tu contraseña temporal",
        body,
        "Recibes este correo porque la administración de Quipu te dio acceso.");
  }

  /** RF-22: who created which administrator, and nothing that gives access. */
  MailContent administratorCreated(String creatorEmail, String newAdministratorEmail) {
    String text =
        "Hola,\n\n"
            + "El administrador "
            + creatorEmail
            + " creó una cuenta de administrador nueva en Quipu para "
            + newAdministratorEmail
            + ".\n\n"
            + "Si no esperabas este cambio, revísalo con los demás administradores.\n";
    String body =
        paragraph("Hola,")
            + paragraph(
                "El administrador "
                    + strong(creatorEmail)
                    + " creó una cuenta de administrador nueva en Quipu para "
                    + strong(newAdministratorEmail)
                    + ".")
            + callout(false, "Si no esperabas este cambio", REVIEW);
    return mail(
        "Se creó un administrador en Quipu",
        text,
        "Una cuenta de administrador nueva se creó en Quipu.",
        "Aviso de seguridad",
        "Se creó un administrador",
        body,
        "Recibes este aviso porque eres administrador de Quipu.");
  }

  /** TAR-127: who deactivated or reactivated which administrator. */
  MailContent administratorStatusChanged(
      String actorEmail, String administratorEmail, boolean deactivated) {
    String effect =
        deactivated
            ? ": ya no puede iniciar sesión hasta que se reactive."
            : ": puede iniciar sesión de nuevo.";
    String text =
        "Hola,\n\n"
            + "El administrador "
            + actorEmail
            + (deactivated ? " desactivó" : " reactivó")
            + " la cuenta de administrador de "
            + administratorEmail
            + " en Quipu"
            + effect
            + "\n\n"
            + "Si no esperabas este cambio, revísalo con los demás administradores.\n";
    String body =
        paragraph("Hola,")
            + paragraph(
                "El administrador "
                    + strong(actorEmail)
                    + (deactivated ? " desactivó" : " reactivó")
                    + " la cuenta de administrador de "
                    + strong(administratorEmail)
                    + " en Quipu"
                    + esc(effect))
            + callout(deactivated, "Si no esperabas este cambio", REVIEW);
    return mail(
        deactivated
            ? "Se desactivó un administrador en Quipu"
            : "Se reactivó un administrador en Quipu",
        text,
        deactivated
            ? "Una cuenta de administrador se desactivó en Quipu."
            : "Una cuenta de administrador se reactivó en Quipu.",
        "Aviso de seguridad",
        deactivated ? "Se desactivó un administrador" : "Se reactivó un administrador",
        body,
        "Recibes este aviso porque eres administrador de Quipu.");
  }

  /** TAR-229: tells the previous email of a guest that the account email changed. */
  MailContent accountEmailChanged(String maskedNewEmail) {
    String text =
        "Hola,\n\n"
            + "El administrador de Quipu cambió el correo de tu cuenta a "
            + maskedNewEmail
            + ". Desde ahora la recuperación de contraseña se envía a ese correo y no a este.\n\n"
            + "Si no pediste este cambio, comunícate con la administración.\n";
    String body =
        paragraph("Hola,")
            + paragraph(
                "El administrador de Quipu cambió el correo de tu cuenta a "
                    + strong(maskedNewEmail)
                    + ". Desde ahora la recuperación de contraseña se envía a ese correo y no a"
                    + " este.")
            + callout(true, "Si no pediste este cambio", "Comunícate con la administración.");
    return mail(
        "Se cambió el correo de tu cuenta de Quipu",
        text,
        "La recuperación de contraseña ya no llega a este correo.",
        "Aviso de seguridad",
        "Se cambió el correo de tu cuenta",
        body,
        "Recibes este aviso porque este era el correo de tu cuenta de Quipu.");
  }

  private MailContent mail(
      String subject,
      String text,
      String preview,
      String eyebrow,
      String heading,
      String body,
      String why) {
    return new MailContent(subject, page(subject, preview, eyebrow, heading, body, why), text);
  }

  private String page(
      String subject, String preview, String eyebrow, String heading, String body, String why) {
    return "<!doctype html>\n<html lang=\"es\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>"
        + esc(subject)
        + "</title></head>"
        + "<body style=\"margin:0;padding:0;background:#eceee9;font-family:"
        + SANS
        + ";color:"
        + INK
        + "\">"
        + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0\">"
        + esc(preview)
        + "</div>"
        + header()
        + "<tr><td style=\"padding:40px 40px 16px\">"
        + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:800;color:"
        + GREEN
        + "\">"
        + esc(eyebrow)
        + "</p><h1 style=\"margin:0 0 24px;font-family:"
        + GEORGIA
        + ";font-size:30px;font-weight:700;line-height:1.15;color:"
        + INK
        + "\">"
        + esc(heading)
        + "</h1>"
        + body
        + "</td></tr>"
        + footer(why)
        + "</table></td></tr></table></body></html>\n";
  }

  private String header() {
    return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
        + " style=\"border-collapse:collapse;background:#eceee9\"><tr>"
        + "<td align=\"center\" style=\"padding:32px 16px\">"
        + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\""
        + " style=\"border-collapse:collapse;width:100%;max-width:600px;background:#ffffff;"
        + "border-radius:4px\">"
        + "<tr><td style=\"padding:24px 40px;background:"
        + CREAM
        + ";border-bottom:4px solid "
        + GREEN
        + "\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\""
        + " style=\"border-collapse:collapse\"><tr>"
        + "<td style=\"padding:0 14px 0 0;vertical-align:middle\"><img src=\""
        + esc(frontendOrigin)
        + "/emblema.png\" width=\"48\" height=\"48\" alt=\"Emblema de Quipu\""
        + " style=\"display:block;width:48px;height:48px;border:0\"></td>"
        + "<td style=\"vertical-align:middle\"><span style=\"font-family:"
        + GEORGIA
        + ";font-size:28px;font-weight:700;color:"
        + INK
        + "\">Quipu</span></td></tr></table></td></tr>";
  }

  private static String footer(String why) {
    return "<tr><td style=\"padding:20px 40px 24px;background:"
        + CREAM
        + "\"><p style=\"margin:0 0 4px;font-size:13px;line-height:1.5;color:"
        + MUTED
        + "\">"
        + esc(why)
        + "</p><p style=\"margin:0;font-size:13px;line-height:1.5;color:"
        + MUTED
        + "\">Quipu · Tarimat Wasi</p></td></tr>";
  }

  /** Receives HTML already built here (fixed text and escaped values): it is not escaped again. */
  private static String paragraph(String html) {
    return "<p style=\"margin:0 0 16px;font-size:16px;line-height:1.6;color:"
        + INK
        + "\">"
        + html
        + "</p>";
  }

  private static String mutedParagraph(String text) {
    return "<p style=\"margin:0 0 16px;font-size:16px;line-height:1.6;color:"
        + MUTED
        + "\">"
        + esc(text)
        + "</p>";
  }

  private static String strong(String value) {
    return "<strong>" + esc(value) + "</strong>";
  }

  private static String callout(boolean warning, String title, String text) {
    String background = warning ? "#f7e7b3" : "#dfe4dc";
    String color = warning ? "#4a3600" : INK;
    return "<div style=\"margin:8px 0 24px;padding:16px 20px;border-radius:4px;background:"
        + background
        + ";color:"
        + color
        + "\"><p style=\"margin:0 0 4px;font-size:14px;font-weight:800\">"
        + esc(title)
        + "</p><p style=\"margin:0;font-size:16px;line-height:1.5\">"
        + esc(text)
        + "</p></div>";
  }

  private static String button(String url, String label) {
    return "<p style=\"margin:8px 0 24px\"><a href=\""
        + esc(url)
        + "\" style=\"display:inline-block;height:48px;padding:0 22px;border-radius:4px;"
        + "background:"
        + GREEN
        + ";color:"
        + CREAM
        + ";font-size:16px;font-weight:800;line-height:48px;text-decoration:none\">"
        + esc(label)
        + "</a></p>";
  }

  private static String fallback(String url) {
    return "<p style=\"margin:0 0 4px;font-size:14px;line-height:1.5;color:"
        + MUTED
        + "\">Si el botón no funciona, copia este enlace en tu navegador:</p>"
        + "<p style=\"margin:0 0 24px;font-size:14px;line-height:1.5;color:"
        + GREEN
        + ";word-break:break-all\">"
        + esc(url)
        + "</p>";
  }

  private static String secret(String label, String value, String sub) {
    return "<div style=\"margin:8px 0 24px;padding:20px;border-radius:4px;background:"
        + CREAM
        + ";text-align:center\"><p style=\"margin:0 0 6px;font-size:14px;color:"
        + MUTED
        + "\">"
        + esc(label)
        + "</p><p style=\"margin:0;font-family:"
        + GEORGIA
        + ";font-size:32px;letter-spacing:0.06em;color:"
        + INK
        + "\">"
        + esc(value)
        + "</p><p style=\"margin:6px 0 0;font-size:14px;color:"
        + MUTED
        + "\">"
        + esc(sub)
        + "</p></div>";
  }

  private static String esc(String value) {
    return HtmlUtils.htmlEscape(value);
  }
}

package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** TAR-309: every email has its HTML and its plain text, escaped and with the data it needs. */
class EmailTemplatesTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String LINK = ORIGIN + "/reset-password?code=abc-DEF_123";

  private final EmailTemplates templates = new EmailTemplates(() -> ORIGIN + "/");

  private List<MailContent> all() {
    return List.of(
        templates.recovery(LINK, 30),
        templates.administratorAccess(ORIGIN + "/activate-admin?code=abc", 30),
        templates.guestAccessLink(ORIGIN + "/activate#token=abc"),
        templates.guestAccessPassword("K7mQ-4tPz"),
        templates.administratorCreated("a@example.test", "b@example.test"),
        templates.administratorStatusChanged("a@example.test", "b@example.test", true),
        templates.administratorStatusChanged("a@example.test", "b@example.test", false),
        templates.accountEmailChanged("n***@example.org"));
  }

  @Test
  void everyEmailIsCompleteHtmlWithTheEmblemOfTheFrontendAndAPlainTextWithoutTags() {
    for (MailContent mail : all()) {
      assertThat(mail.subject()).isNotBlank().endsWith("Quipu").doesNotContain("<");
      assertThat(mail.html())
          .startsWith("<!doctype html>")
          .contains("<html lang=\"es\">")
          .contains("src=\"" + ORIGIN + "/emblema.png\"")
          .contains("Quipu · Tarimat Wasi")
          .endsWith("</html>\n");
      assertThat(mail.text()).startsWith("Hola").doesNotContain("<").doesNotContain("&amp;");
    }
  }

  @Test
  void theRecoveryCarriesTheLinkAndTheValidityInBothParts() {
    MailContent mail = templates.recovery(LINK, 30);

    assertThat(mail.subject()).isEqualTo("Restablece tu contraseña de Quipu");
    assertThat(mail.text()).contains(LINK).contains("vale 30 minutos");
    assertThat(mail.html())
        .contains("href=\"" + LINK + "\"")
        .contains("El enlace vale 30 minutos y solo se puede usar una vez.")
        .contains("Si el botón no funciona");
  }

  @Test
  void theAccessOfAnAdministratorKeepsItsSubjectAndLink() {
    MailContent mail = templates.administratorAccess(ORIGIN + "/activate-admin?code=abc", 30);

    assertThat(mail.subject()).isEqualTo("Tu cuenta de administrador de Quipu");
    assertThat(mail.text()).contains(ORIGIN + "/activate-admin?code=abc");
    assertThat(mail.html()).contains("href=\"" + ORIGIN + "/activate-admin?code=abc\"");
  }

  @Test
  void theAccessOfAGuestHasALinkOrATemporaryPassword() {
    MailContent link = templates.guestAccessLink(ORIGIN + "/activate#token=abc");
    MailContent password = templates.guestAccessPassword("K7mQ-4tPz");

    assertThat(link.subject()).isEqualTo("Tu acceso a Quipu");
    assertThat(link.text()).contains(ORIGIN + "/activate#token=abc").contains("Vale 30 minutos");
    assertThat(link.html()).contains("href=\"" + ORIGIN + "/activate#token=abc\"");
    assertThat(password.subject()).isEqualTo("Tu acceso a Quipu");
    assertThat(password.text()).contains("K7mQ-4tPz").doesNotContain("http");
    assertThat(password.html()).contains("K7mQ-4tPz").contains("href=\"" + ORIGIN + "/\"");
  }

  @Test
  void theNoticesSayWhoDidWhatAndNothingThatGivesAccess() {
    MailContent created = templates.administratorCreated("a@example.test", "b@example.test");
    MailContent deactivated =
        templates.administratorStatusChanged("a@example.test", "b@example.test", true);
    MailContent reactivated =
        templates.administratorStatusChanged("a@example.test", "b@example.test", false);

    assertThat(created.subject()).isEqualTo("Se creó un administrador en Quipu");
    assertThat(deactivated.subject()).isEqualTo("Se desactivó un administrador en Quipu");
    assertThat(reactivated.subject()).isEqualTo("Se reactivó un administrador en Quipu");
    assertThat(deactivated.text()).contains("a@example.test desactivó").contains("b@example.test");
    assertThat(reactivated.text()).contains("a@example.test reactivó");
    assertThat(deactivated.html()).contains("<strong>a@example.test</strong>").contains("#f7e7b3");
    assertThat(reactivated.html()).contains("#dfe4dc").doesNotContain("#f7e7b3");
    for (MailContent mail : List.of(created, deactivated, reactivated)) {
      assertThat(mail.text()).doesNotContain("http");
      assertThat(mail.html()).doesNotContain("href=");
    }
  }

  @Test
  void theNoticeToThePreviousEmailShowsTheNewOneAsGiven() {
    MailContent mail = templates.accountEmailChanged("n***@example.org");

    assertThat(mail.subject()).isEqualTo("Se cambió el correo de tu cuenta de Quipu");
    assertThat(mail.text()).contains("n***@example.org");
    assertThat(mail.html()).contains("<strong>n***@example.org</strong>");
  }

  @Test
  void valuesFromPeopleAreEscapedInTheHtmlAndLeftAsTheyAreInTheText() {
    String hostile = "<script>alert(1)</script>\"&@example.test";

    MailContent mail = templates.administratorCreated(hostile, "b@example.test");

    assertThat(mail.html())
        .doesNotContain("<script>")
        .contains("&lt;script&gt;alert(1)&lt;/script&gt;&quot;&amp;@example.test");
    assertThat(mail.text()).contains(hostile);
  }

  @Test
  void aLinkWithSpecialCharactersCannotBreakOutOfTheAttribute() {
    MailContent mail = templates.recovery(ORIGIN + "/x?a=1&b=\"2\"", 30);

    assertThat(mail.html()).contains("href=\"" + ORIGIN + "/x?a=1&amp;b=&quot;2&quot;\"");
  }
}

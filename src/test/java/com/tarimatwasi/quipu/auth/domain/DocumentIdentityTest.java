package com.tarimatwasi.quipu.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

class DocumentIdentityTest {

  @Test
  void aSingleTextCarriesTheTypeAndTheNumber() {
    var identity = new DocumentIdentity(DocumentType.DNI, "12345678");

    assertThat(identity.toString()).isEqualTo("DNI:12345678");
  }

  @Test
  void theTextBecomesTheSameObjectAgain() {
    for (DocumentType type : DocumentType.values()) {
      var identity = new DocumentIdentity(type, "A1B2C3");

      assertThat(DocumentIdentity.parse(identity.toString())).isEqualTo(identity);
    }
  }

  @Test
  void theNumberIsKeptAsItIs() {
    var parsed = DocumentIdentity.parse("PASSPORT:ab 12-x");

    assertThat(parsed.type()).isEqualTo(DocumentType.PASSPORT);
    assertThat(parsed.number()).isEqualTo("ab 12-x");
  }

  @Test
  void aTextThatIsNotAnIdentityIsRefused() {
    for (String text : new String[] {"", "12345678", "DNI", "DNI:", ":12345678", "XYZ:12345678"}) {
      assertThatThrownBy(() -> DocumentIdentity.parse(text))
          .as(text)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aNumberWithTheSeparatorCannotBeBuiltSoTheTextIsNeverAmbiguous() {
    assertThatThrownBy(() -> new DocumentIdentity(DocumentType.DNI, "123:456"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DocumentIdentity.parse("DNI:123:456"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankNumberCannotBeBuilt() {
    assertThatThrownBy(() -> new DocumentIdentity(DocumentType.CE, "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void survivesJavaSerialization() throws IOException, ClassNotFoundException {
    var identity = new DocumentIdentity(DocumentType.CE, "000111222");
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(identity);
    }

    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      assertThat(in.readObject()).isEqualTo(identity);
    }
  }

  @Test
  void anAccountKnowsItsIdentity() {
    var account =
        new UserAccount(
            1L,
            "a@example.test",
            DocumentType.DNI,
            "12345678",
            "hash",
            Role.ADMIN,
            null,
            false,
            "ACTIVE",
            0,
            null,
            null);

    assertThat(account.identity()).isEqualTo(new DocumentIdentity(DocumentType.DNI, "12345678"));
  }
}

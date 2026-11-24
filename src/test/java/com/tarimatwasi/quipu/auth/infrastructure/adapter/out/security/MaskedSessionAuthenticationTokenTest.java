package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class MaskedSessionAuthenticationTokenTest {

  private final MaskSession session =
      new IdMasker(
              new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"),
              Optional::empty)
          .session("AAECAwQFBgcICQoLDA0ODw");
  private final Jwt jwt =
      Jwt.withTokenValue("t").header("alg", "HS256").subject("s").claim("sid", "x").build();
  private final List<SimpleGrantedAuthority> admin =
      List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));

  /** The masking follows from the token: two sessions are equal as their tokens are. */
  @Test
  void twoSessionsAreEqualAsTheirTokensAre() {
    var one = new MaskedSessionAuthenticationToken(jwt, admin, "1", session);
    var same = new MaskedSessionAuthenticationToken(jwt, admin, "1", session);
    var other =
        new MaskedSessionAuthenticationToken(
            Jwt.withTokenValue("u").header("alg", "HS256").subject("s").claim("sid", "y").build(),
            admin,
            "1",
            session);

    assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
    assertThat(one).isNotEqualTo(new JwtAuthenticationToken(jwt, admin, "1"));
    assertThat(one.maskSession()).containsSame(session);
  }
}

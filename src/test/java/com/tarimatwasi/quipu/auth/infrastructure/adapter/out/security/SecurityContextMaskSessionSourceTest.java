package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** TAR-227: the masking of a request is the one its session token left, or none at all. */
class SecurityContextMaskSessionSourceTest {

  private final IdMasker masker =
      new IdMasker(
          new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"), Optional::empty);
  private final MaskSession session = masker.session("AAECAwQFBgcICQoLDA0ODw");
  private final SecurityContextMaskSessionSource source = new SecurityContextMaskSessionSource();
  private final Jwt jwt =
      Jwt.withTokenValue("t").header("alg", "HS256").subject("s").claim("sid", "x").build();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theSessionOfTheTokenMasksTheRequest() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new MaskedSessionAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "1", session));

    assertThat(source.current()).containsSame(session);
    assertThat(source.current().orElseThrow().mask(IdKind.GUEST, 3))
        .isEqualTo(session.mask(IdKind.GUEST, 3));
  }

  @Test
  void anyOtherAuthenticationOrNoneHasNoSession() {
    assertThat(source.current()).isEmpty();

    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("user", "pw", "ROLE_ADMIN"));
    assertThat(source.current()).isEmpty();

    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    assertThat(source.current()).isEmpty();
  }

  @Test
  void anAuthenticationThatIsNoLongerTrustedHasNoSession() {
    var token =
        new MaskedSessionAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "1", session);
    token.setAuthenticated(false);
    SecurityContextHolder.getContext().setAuthentication(token);

    assertThat(source.current()).isEmpty();
  }
}

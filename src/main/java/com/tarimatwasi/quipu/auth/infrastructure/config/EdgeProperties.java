package com.tarimatwasi.quipu.auth.infrastructure.config;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The secret that Vercel adds to every request it forwards to {@code /bff/**} (TAR-144, TAR-9).
 *
 * @param secrets {@code app.edge.secrets}: the accepted secrets, comma separated: the current one
 *     and, while it is being rotated, the previous one. Empty (the local profile) turns the gate
 *     off; {@code dev} and {@code prod} read {@code EDGE_SHARED_SECRETS} without a default, so
 *     their startup fails without it (BE-SPR-CFG-05). Each one is 32 to 128 characters of {@code
 *     [A-Za-z0-9._~-]}, so that it travels in a header as it is ({@code openssl rand -hex 32}).
 * @param required {@code app.edge.required}: true in {@code dev} and {@code prod}, whose startup
 *     then fails without a secret: an empty {@code EDGE_SHARED_SECRETS} would leave the API open.
 */
@ConfigurationProperties("app.edge")
@Validated
public record EdgeProperties(
    @DefaultValue List<String> secrets, @DefaultValue("false") boolean required) {

  private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9._~-]{32,128}");
  private static final int MAX_SECRETS = 2;

  public EdgeProperties {
    secrets = secrets.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    if (required && secrets.isEmpty()) {
      throw new IllegalArgumentException(
          "app.edge.secrets (EDGE_SHARED_SECRETS) is required in dev and prod");
    }
    if (secrets.size() > MAX_SECRETS) {
      throw new IllegalArgumentException(
          "app.edge.secrets takes the current secret and, at most, the previous one");
    }
    for (String secret : secrets) {
      if (!SECRET.matcher(secret).matches()) {
        throw new IllegalArgumentException(
            "app.edge.secrets: each secret is 32 to 128 characters of letters, digits and . _ ~ -"
                + " ("
                + diagnose(secret)
                + ")");
      }
    }
  }

  /** What is wrong, never the value: the length and the first character outside the alphabet. */
  private static String diagnose(String secret) {
    String found = "length " + secret.length();
    for (int i = 0; i < secret.length(); i++) {
      char c = secret.charAt(i);
      if (!String.valueOf(c).matches("[A-Za-z0-9._~-]")) {
        return found + ", first invalid character U+%04X at position %d".formatted((int) c, i);
      }
    }
    return found;
  }

  boolean enabled() {
    return !secrets.isEmpty();
  }
}

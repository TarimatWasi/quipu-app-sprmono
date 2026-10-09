package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtProperties;
import com.tarimatwasi.quipu.auth.adapter.out.security.SessionJwt;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/** The session token as Spring Security's resource server reads it and signs it (TAR-164). */
@Configuration(proxyBeanMethods = false)
class SessionJwtConfig {

  @Bean
  JwtDecoder sessionJwtDecoder(JwtProperties properties, Clock clock) {
    return SessionJwt.decoder(SessionJwt.key(properties.secret()), clock);
  }

  @Bean
  JwtEncoder sessionJwtEncoder(JwtProperties properties) {
    return SessionJwt.encoder(SessionJwt.key(properties.secret()));
  }
}

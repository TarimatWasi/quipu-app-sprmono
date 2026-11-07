package com.tarimatwasi.quipu.auth.application.port.out;

/** The primary origin of the frontend, on which the links of the mails are built. */
public interface FrontendOriginPort {

  /** An exact origin, such as {@code https://quipu-dev.tarimatwasi.com}. */
  String frontendOrigin();
}

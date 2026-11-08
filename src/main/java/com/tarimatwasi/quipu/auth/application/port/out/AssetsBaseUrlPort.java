package com.tarimatwasi.quipu.auth.application.port.out;

/** The base of the public images (the emblem of the mails), served apart from the application. */
public interface AssetsBaseUrlPort {

  /** An https address without a trailing slash, such as {@code https://assets.tarimatwasi.com}. */
  String assetsBaseUrl();
}

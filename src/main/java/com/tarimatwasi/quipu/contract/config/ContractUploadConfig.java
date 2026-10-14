package com.tarimatwasi.quipu.contract.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * The limits of the multipart form that loads a contract (RF-15: a PDF of up to 10 MB). They are
 * fixed here and not in the YAML because they are part of the contract of the API, not a deployment
 * setting; the exact check of the file stays in {@code ContractService}.
 */
@Configuration(proxyBeanMethods = false)
class ContractUploadConfig {

  /** The PDF of a contract (RF-15). */
  static final DataSize MAX_FILE = DataSize.ofMegabytes(10);

  /** The PDF plus the other fields of the form. */
  static final DataSize MAX_REQUEST = DataSize.ofMegabytes(11);

  @Bean
  MultipartConfigElement multipartConfigElement() {
    var factory = new MultipartConfigFactory();
    factory.setMaxFileSize(MAX_FILE); // NOSONAR java:S5693 TAR-40: RF-15, solo el ADMIN sube
    factory.setMaxRequestSize(MAX_REQUEST); // NOSONAR java:S5693 TAR-40: PDF más el formulario
    return factory.createMultipartConfig();
  }
}

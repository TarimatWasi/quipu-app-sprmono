package com.tarimatwasi.quipu.contract.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RF-15: the form of a contract takes a PDF of 10 MB, and not one byte more. */
class ContractUploadConfigTest {

  @Test
  void multipartConfigElement_limitsTheFileToTenMegabytesAndTheFormToEleven() {
    var multipart = new ContractUploadConfig().multipartConfigElement();

    assertThat(multipart.getMaxFileSize()).isEqualTo(10L * 1024 * 1024);
    assertThat(multipart.getMaxRequestSize()).isEqualTo(11L * 1024 * 1024);
  }
}

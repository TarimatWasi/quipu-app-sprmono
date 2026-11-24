package com.tarimatwasi.quipu.contract;

import java.nio.file.Path;

/** The BFF contract the build downloads and hash-checks (pom.xml: contract.version/sha256). */
public final class ContractSpec {

  /** Absolute path of target/contract/bff.yaml, as the OpenAPI validators expect it. */
  public static final String PATH = Path.of("target/contract/bff.yaml").toAbsolutePath().toString();

  private ContractSpec() {}
}

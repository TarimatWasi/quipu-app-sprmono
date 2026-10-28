package com.tarimatwasi.quipu.shared.masking;

import java.util.Optional;

/**
 * The masking session of the request being served (TAR-227): the one of its session token, or none
 * (a public endpoint, a thread outside a request). The security layer provides it.
 */
@FunctionalInterface
public interface MaskSessionSource {

  /** The session of the current request, if it has one. */
  Optional<MaskSession> current();
}

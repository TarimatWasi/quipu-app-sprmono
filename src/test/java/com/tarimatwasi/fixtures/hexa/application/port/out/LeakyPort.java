package com.tarimatwasi.fixtures.hexa.application.port.out;

import com.tarimatwasi.fixtures.hexa.infrastructure.adapter.out.persistence.HexAdapter;

/** A port that exposes an infrastructure type. */
public interface LeakyPort {
  HexAdapter adapter();
}

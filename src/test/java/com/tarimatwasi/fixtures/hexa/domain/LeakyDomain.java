package com.tarimatwasi.fixtures.hexa.domain;

import com.tarimatwasi.fixtures.hexa.infrastructure.adapter.out.persistence.HexAdapter;

/** A domain class that depends on infrastructure. */
public class LeakyDomain {
  HexAdapter adapter;
}

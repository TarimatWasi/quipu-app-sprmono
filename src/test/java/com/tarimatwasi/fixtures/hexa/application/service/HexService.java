package com.tarimatwasi.fixtures.hexa.application.service;

import com.tarimatwasi.fixtures.hexa.application.port.in.HexUseCase;
import com.tarimatwasi.fixtures.hexa.application.port.out.HexPort;
import com.tarimatwasi.fixtures.hexa.domain.HexDomain;

public class HexService implements HexUseCase {
  HexPort port;
  HexDomain domain;
}

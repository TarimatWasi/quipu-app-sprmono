package com.tarimatwasi.quipu.guest.application.port.out;

/** RF-14: the version of the consent text in force. */
public interface ConsentVersionPort {

  /** The version whose text is the resource {@code consent/<version>.txt}. */
  String currentVersion();
}

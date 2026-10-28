package com.tarimatwasi.quipu.fine.application.port.out;

/** What the fines need to know about a guest. */
public interface FineGuestPort {

  /** Whether there is a guest with that id, whatever its state. */
  boolean exists(Long guestId);
}

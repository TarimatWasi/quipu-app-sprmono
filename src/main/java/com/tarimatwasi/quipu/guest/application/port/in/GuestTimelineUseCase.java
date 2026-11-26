package com.tarimatwasi.quipu.guest.application.port.in;

import java.time.LocalDate;

/** What a business day makes true about guests: stays about to end and accesses expired unused. */
public interface GuestTimelineUseCase {

  /** States them; stating them again changes nothing. */
  void sweep(LocalDate day);
}

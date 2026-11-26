package com.tarimatwasi.quipu.calendar.application.port.in;

import java.time.LocalDate;

/** States that a business day started; stating the same day again is the same fact. */
public interface StartBusinessDayUseCase {

  /** The day of Lima that started. */
  void start(LocalDate day);
}

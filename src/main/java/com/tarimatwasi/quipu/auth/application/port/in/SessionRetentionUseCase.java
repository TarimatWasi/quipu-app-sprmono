package com.tarimatwasi.quipu.auth.application.port.in;

/** What a business day removes of the sessions: the ones no token can name any more. */
public interface SessionRetentionUseCase {

  /** The most sessions one call removes. */
  int BATCH = 500;

  /** Removes up to {@link #BATCH} ended sessions with their refresh tokens; returns how many. */
  int purgeEnded();
}

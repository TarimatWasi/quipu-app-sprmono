package com.tarimatwasi.quipu.support;

import software.amazon.awssdk.core.exception.SdkClientException;

/** A failure of the R2 client as the SDK reports it: its message names the host and the key. */
public final class R2Failures {

  /** The R2 host carries the account id; it must never reach a log. */
  public static final String HOST = "acct-0123abcd.r2.cloudflarestorage.com";

  private R2Failures() {}

  public static SdkClientException clientFailure(String key) {
    return SdkClientException.builder()
        .message("Unable to execute HTTP request: Connect to " + HOST + "/" + key + " failed")
        .build();
  }
}

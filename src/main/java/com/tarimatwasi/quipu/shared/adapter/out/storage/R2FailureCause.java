package com.tarimatwasi.quipu.shared.adapter.out.storage;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

/**
 * What a log may say about a failure of R2 (TAR-356): the HTTP status and the error code when the
 * bucket answered ({@code status 403 InvalidAccessKeyId}), otherwise the classes of the exception
 * and of its root cause ({@code SdkClientException caused by UnknownHostException}).
 *
 * <p>Never the message of the SDK: it names the host of the bucket, which carries the account id,
 * and the key of the object (TAR-205).
 */
public final class R2FailureCause {

  private static final int MAX_DEPTH = 16;

  private R2FailureCause() {}

  public static String of(Throwable failure) {
    if (failure instanceof AwsServiceException answered) {
      AwsErrorDetails details = answered.awsErrorDetails();
      String code = details == null ? null : details.errorCode();
      return "status " + answered.statusCode() + (code == null ? "" : " " + code);
    }
    Throwable root = failure;
    int depth = 0;
    // Bounded: a chain of causes may loop back on itself.
    for (Throwable next = failure.getCause();
        next != null && depth < MAX_DEPTH;
        next = next.getCause()) {
      root = next;
      depth++;
    }
    String name = failure.getClass().getSimpleName();
    return depth == 0 ? name : name + " caused by " + root.getClass().getSimpleName();
  }
}

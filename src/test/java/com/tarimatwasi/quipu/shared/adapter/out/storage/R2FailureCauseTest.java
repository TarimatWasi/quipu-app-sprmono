package com.tarimatwasi.quipu.shared.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.R2Failures;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** TAR-356: what a log may say about a failure of R2, never its host nor the key of the object. */
class R2FailureCauseTest {

  private static final String KEY = "contracts/7f1c.pdf";

  @Test
  void anAnswerOfTheBucketIsItsStatusAndItsErrorCode() {
    var refused =
        S3Exception.builder()
            .statusCode(403)
            .message("Access denied on " + R2Failures.HOST + "/" + KEY)
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode("InvalidAccessKeyId")
                    .errorMessage("refused for " + KEY)
                    .build())
            .build();

    assertThat(R2FailureCause.of(refused)).isEqualTo("status 403 InvalidAccessKeyId");
  }

  @Test
  void anAnswerWithoutDetailsIsItsStatusAlone() {
    var refused = S3Exception.builder().statusCode(500).build();

    assertThat(R2FailureCause.of(refused)).isEqualTo("status 500");
  }

  @Test
  void noAnswerIsTheClassOfTheFailureAndOfItsRootCauseNeverTheMessage() {
    var unreachable =
        SdkClientException.builder()
            .message("Unable to execute HTTP request: " + R2Failures.HOST + "/" + KEY)
            .cause(new IllegalStateException("wrapped", new UnknownHostException(R2Failures.HOST)))
            .build();

    assertThat(R2FailureCause.of(unreachable))
        .isEqualTo("SdkClientException caused by UnknownHostException")
        .doesNotContain(R2Failures.HOST)
        .doesNotContain(KEY);
  }

  @Test
  void aFailureWithoutCauseIsItsClass() {
    assertThat(R2FailureCause.of(R2Failures.clientFailure(KEY)))
        .isEqualTo("SdkClientException")
        .doesNotContain(R2Failures.HOST);
  }
}

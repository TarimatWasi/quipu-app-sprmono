package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceBusyTest {

  @Test
  void anExhaustedPoolOrACancelledStatementIsBusyHoweverWrapped() {
    assertThat(
            ServiceBusy.causedBy(
                new ServletException(
                    "Request processing failed",
                    new DataAccessResourceFailureException(
                        "Unable to acquire JDBC Connection",
                        new SQLTransientConnectionException("pool timed out")))))
        .isTrue();
    assertThat(
            ServiceBusy.causedBy(
                new PessimisticLockingFailureException(
                    "x", new SQLException("canceling statement", "57014"))))
        .isTrue();
  }

  @Test
  void aLockWaitOrAnyOtherFailureIsNot() {
    assertThat(
            ServiceBusy.causedBy(
                new PessimisticLockingFailureException(
                    "x", new SQLException("lock timeout", "55P03"))))
        .isFalse();
    assertThat(ServiceBusy.causedBy(new DataAccessResourceFailureException("down"))).isFalse();
    assertThat(ServiceBusy.causedBy(new SQLException("other", "23505"))).isFalse();
    assertThat(ServiceBusy.causedBy(new IllegalStateException())).isFalse();
    assertThat(ServiceBusy.causedBy(null)).isFalse();
  }

  /** A chain of causes that loops ends at its depth limit, never in an endless search. */
  @Test
  void aChainOfCausesThatLoopsIsNotBusy() {
    var first = new IllegalStateException("first");
    var second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);

    assertThat(ServiceBusy.causedBy(first)).isFalse();
  }

  @Test
  void theAnswerIsA503ThatCanBeRepeatedInTheShapeOfTheBff() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    ServiceBusy.write(response);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    assertThat(response.getContentAsString())
        .startsWith("{\"code\":\"SERVICE_BUSY\",\"message\":\"")
        .contains("no guardó nada");
  }
}

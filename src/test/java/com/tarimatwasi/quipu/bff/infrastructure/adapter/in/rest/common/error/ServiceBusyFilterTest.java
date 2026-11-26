package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceBusyFilterTest {

  private final DataAccessResourceFailureException busy =
      new DataAccessResourceFailureException(
          "Unable to acquire JDBC Connection", new SQLTransientConnectionException("timed out"));

  private final ServiceBusyFilter filter = new ServiceBusyFilter();

  @Test
  void aBusyDatabaseAnswers503() throws Exception {
    var response = new MockHttpServletResponse();

    filter.doFilter(bff(), response, failingWith(busy));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("\"code\":\"SERVICE_BUSY\"");
  }

  /** Any other failure goes on as it was, for the handlers after it. */
  @Test
  void anyOtherFailureGoesOn() {
    var lock = new CannotAcquireLockException("lock");

    assertThatThrownBy(
            () -> filter.doFilter(bff(), new MockHttpServletResponse(), failingWith(lock)))
        .isSameAs(lock);
  }

  /** Once the answer started, a 503 cannot replace it. */
  @Test
  void anAnswerAlreadyStartedIsLeftAsItIs() throws Exception {
    var response = new MockHttpServletResponse();
    response.getWriter().write("{");
    response.flushBuffer();

    assertThatThrownBy(() -> filter.doFilter(bff(), response, failingWith(busy))).isSameAs(busy);
  }

  /** Only the BFF answers in its shape. */
  @Test
  void outsideTheBffItDoesNothing() {
    var request = new MockHttpServletRequest("GET", "/actuator/health");

    assertThatThrownBy(
            () -> filter.doFilter(request, new MockHttpServletResponse(), failingWith(busy)))
        .isSameAs(busy);
  }

  private static MockHttpServletRequest bff() {
    return new MockHttpServletRequest("GET", "/bff/admin/payments");
  }

  private static FilterChain failingWith(RuntimeException failure) {
    return (request, response) -> {
      throw failure;
    };
  }
}

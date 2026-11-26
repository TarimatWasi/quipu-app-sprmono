package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers {@link ServiceBusy} wherever in a request of the BFF the database failed to serve in
 * time. First of all the filters, so it also covers the security ones: an authenticated request
 * reads its session there, before any controller, and that is where an exhausted pool shows first.
 * A failure of a controller reaches it too, since the dispatcher rethrows (wrapped) what no advice
 * answered. Any other failure goes on as it was.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ServiceBusyFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusyFilter.class);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/bff/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } catch (ServletException | IOException | DataAccessException | TransactionException failure) {
      // A filter throws the failure of the database as it is; the dispatcher, wrapped.
      if (!ServiceBusy.causedBy(failure) || response.isCommitted()) {
        throw failure;
      }
      // The class only: a message may carry internal details (TAR-205).
      LOGGER.warn("The database did not serve in time: 503 ({})", failure.getClass().getName());
      ServiceBusy.write(response);
    }
  }
}

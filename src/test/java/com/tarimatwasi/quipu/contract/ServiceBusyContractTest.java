package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.ServiceBusy;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * v0.29.0 (TAR-204): a request the database cannot serve in time answers 503 SERVICE_BUSY with a
 * Retry-After, in the shape of every error of the BFF, never the default error page of the server.
 * The response is global (ServiceBusy): no operation of the contract references it.
 */
class ServiceBusyContractTest extends PaymentsContractSupport {

  @Autowired HikariDataSource pool;

  @Test
  void withEveryConnectionTakenARequestIsBusyAndCanBeRepeated() throws Exception {
    // The token is signed with the security version of the account, read from the database.
    Cookie session = admin();
    List<Connection> all = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        all.add(pool.getConnection());
      }

      mockMvc
          .perform(get(URL).cookie(session))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
          .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
          .andExpect(jsonPath("$.message").isNotEmpty());
    } finally {
      for (Connection connection : all) {
        connection.close();
      }
    }

    mockMvc.perform(get(URL).cookie(admin())).andExpect(status().isOk());
  }

  /** The code and the response are the ones of the contract, the tag pinned in pom.xml. */
  @Test
  void theContractDeclaresTheGlobalResponse() throws Exception {
    String spec = Files.readString(Path.of(ContractSpec.PATH));

    assertThat(spec).contains("    ServiceBusy:", "`" + ServiceBusy.CODE + "`", "Retry-After");
  }
}

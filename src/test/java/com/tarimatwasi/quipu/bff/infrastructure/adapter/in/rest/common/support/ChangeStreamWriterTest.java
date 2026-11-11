package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.StreamSessionUseCase;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.SessionIds;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.Serial;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Real NIO sockets: a stalled/trickling reader must not block committed producers or leak slots.
 */
class ChangeStreamWriterTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stream_stalledOrTricklingSocket_keepsProducersAvailableAndReleasesSlot(boolean trickle)
      throws Exception {
    Clock clock = Clock.systemUTC();
    var registry =
        new ChangeStreamRegistry(
            new ChangeStreamProperties(
                4, 64, 64, 8192, Duration.ofSeconds(20), Duration.ofSeconds(30)),
            clock);
    try (var transport = new Transport(temporary, writer(registry, clock), authentication(clock))) {
      transport.open(trickle);
      transport.stall(registry);
      transport.assertProducersRemainAvailable(registry);
      assertThat(transport.done.await(6, TimeUnit.SECONDS))
          .as("NIO no-progress timeout releases writer")
          .isTrue();
      assertThat(registry.connectionCount()).isZero();
      assertThat(transport.virtual).isTrue();
    }
  }

  private static ChangeStreamWriter writer(ChangeStreamRegistry registry, Clock clock) {
    var mask =
        new IdMasker(
                new IdMaskProperties("test-only-key-of-at-least-32-characters"), Optional::empty)
            .session(SessionIds.newSessionId());
    var auth = mock(StreamSessionUseCase.class);
    when(auth.authorize(
            ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyBoolean()))
        .thenReturn(new StreamSessionUseCase.Identity(1L, null, true));
    return new ChangeStreamWriter(registry, auth, () -> Optional.of(mask), clock);
  }

  private static JwtAuthenticationToken authentication(Clock clock) {
    var token =
        Jwt.withTokenValue("test")
            .header("alg", "none")
            .subject("masked")
            .issuedAt(clock.instant())
            .expiresAt(clock.instant().plusSeconds(60))
            .claim("sid", SessionIds.newSessionId())
            .claim("sv", 0L)
            .build();
    return new JwtAuthenticationToken(
        token, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "1");
  }

  private static final class Transport implements AutoCloseable {
    private final CountDownLatch done = new CountDownLatch(1);
    private final Semaphore flushed = new Semaphore(0);
    private final AtomicBoolean virtual = new AtomicBoolean();
    private final AtomicBoolean writing = new AtomicBoolean();
    private final CountDownLatch stopReader = new CountDownLatch(1);
    private final Tomcat tomcat = new Tomcat();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Socket socket = new Socket();

    private Transport(
        Path temporary, ChangeStreamWriter writer, JwtAuthenticationToken authentication) {
      tomcat.setBaseDir(temporary.toString());
      tomcat.setPort(0);
      var connector = tomcat.getConnector();
      connector.setProperty("connectionTimeout", "250");
      connector.setProperty("socket.soSndBuf", "1024");
      connector.setProperty("socket.appWriteBufSize", "1024");
      connector.getProtocolHandler().setExecutor(executor);
      var context = tomcat.addContext("", temporary.toString());
      Tomcat.addServlet(
          context,
          "changes",
          new HttpServlet() {
            @Serial private static final long serialVersionUID = 1L;

            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
              virtual.set(Thread.currentThread().isVirtual());
              try {
                writer.stream(authentication, true, observed(response, flushed, writing));
              } finally {
                done.countDown();
              }
            }
          });
      context.addServletMapping("/changes", "changes");
    }

    private void open(boolean trickle) throws Exception {
      tomcat.start();
      socket.setReceiveBufferSize(1024);
      socket.connect(
          new InetSocketAddress(
              InetAddress.getLoopbackAddress(), tomcat.getConnector().getLocalPort()));
      socket.setSoTimeout(2000);
      socket
          .getOutputStream()
          .write(
              "GET /changes HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      assertThat(flushed.tryAcquire(3, TimeUnit.SECONDS)).isTrue();
      if (trickle) {
        executor.execute(this::readSlowly);
      }
    }

    private void readSlowly() {
      try {
        while (!stopReader.await(10, TimeUnit.MILLISECONDS)
            && socket.getInputStream().read() != -1) {
          /* Deliberately one byte per tick. */
        }
      } catch (IOException interruptedRead) {
        /* The writer closed the stream. */
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private void stall(ChangeStreamRegistry registry) throws InterruptedException {
      boolean stalled = false;
      for (int i = 0; i < 20000; i++) {
        commit(
            () ->
                registry.changed(
                    new ChangeSignals.Change(ChangeSignals.Kind.PAYMENT, 5L, true, 7L, 8L)));
        if (!flushed.tryAcquire(100, TimeUnit.MILLISECONDS)) {
          stalled = true;
          break;
        }
      }
      assertThat(stalled).as("the real socket eventually backpressures the writer").isTrue();
      assertThat(writing)
          .as("the writer is inside socket I/O, not idle waiting for an event")
          .isTrue();
      assertThat(done.getCount()).isEqualTo(1);
      assertThat(registry.connectionCount()).isEqualTo(1);
    }

    private void assertProducersRemainAvailable(ChangeStreamRegistry registry) throws Exception {
      try (var producer = Executors.newVirtualThreadPerTaskExecutor()) {
        producer
            .submit(
                () ->
                    commit(
                        () ->
                            registry.changed(
                                new ChangeSignals.Change(
                                    ChangeSignals.Kind.CONTRACT, 5L, true, null, 8L))))
            .get(1, TimeUnit.SECONDS);
        producer.submit(() -> commit(() -> registry.accountLocked(1L))).get(1, TimeUnit.SECONDS);
      }
    }

    @Override
    public void close() throws IOException, LifecycleException {
      stopReader.countDown();
      try {
        socket.close();
      } finally {
        try {
          tomcat.stop();
          tomcat.destroy();
        } finally {
          executor.close();
        }
      }
    }
  }

  private static HttpServletResponse observed(
      HttpServletResponse response, Semaphore flushed, AtomicBoolean writing) {
    return new HttpServletResponseWrapper(response) {
      @Override
      public ServletOutputStream getOutputStream() throws IOException {
        var actual = super.getOutputStream();
        return new ServletOutputStream() {
          @Override
          public boolean isReady() {
            return actual.isReady();
          }

          @Override
          public void setWriteListener(WriteListener listener) {
            actual.setWriteListener(listener);
          }

          @Override
          public void write(int b) throws IOException {
            actual.write(b);
          }

          @Override
          public void write(byte[] b) throws IOException {
            writing.set(true);
            actual.write(b);
          }

          @Override
          public void flush() throws IOException {
            actual.flush();
            writing.set(false);
            flushed.release();
          }
        };
      }
    };
  }

  private static void commit(Runnable action) {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      action.run();
      TransactionSynchronizationUtils.invokeAfterCompletion(
          TransactionSynchronizationManager.getSynchronizations(),
          TransactionSynchronization.STATUS_COMMITTED);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }
}

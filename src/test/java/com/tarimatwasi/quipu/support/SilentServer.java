package com.tarimatwasi.quipu.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A server that takes connections and never answers: the stand-in for a bucket that hangs, to see
 * the client give up on its own (TAR-204).
 */
public final class SilentServer implements AutoCloseable {

  private final ServerSocket socket;
  private final List<Socket> accepted = new CopyOnWriteArrayList<>();

  public SilentServer() {
    try {
      socket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Thread.ofVirtual()
        .start(
            () -> {
              while (!socket.isClosed()) {
                try {
                  accepted.add(socket.accept());
                } catch (IOException closed) {
                  return;
                }
              }
            });
  }

  public String endpoint() {
    return "http://127.0.0.1:" + socket.getLocalPort();
  }

  @Override
  public void close() throws IOException {
    socket.close();
    for (Socket connection : accepted) {
      connection.close();
    }
  }
}

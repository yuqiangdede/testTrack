package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.telemetry.RequestMetricsService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClickHouseHttpClientTest {
  @Test
  void retriesQueryOnceWhenConnectionClosesBeforeHeaders() throws Exception {
    try (OneDropHttpServer server = new OneDropHttpServer("{\"value\":1}\n")) {
      ShipTrackConfig config = new ShipTrackConfig();
      config.clickhouse.jdbcUrl = "jdbc:clickhouse://127.0.0.1:" + server.port() + "/track";
      config.query.logSql = false;

      ShipConfigService configService = mock(ShipConfigService.class);
      when(configService.config()).thenReturn(config);
      ClickHouseHttpClient client = new ClickHouseHttpClient(new ObjectMapper(), configService, new RequestMetricsService());

      List<Map<String, Object>> rows = client.query("SELECT 1 AS value", Map.of(), 5, 5);

      assertThat(rows).containsExactly(Map.of("value", 1));
      assertThat(server.acceptedConnections()).isEqualTo(2);
    }
  }

  private static final class OneDropHttpServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final CompletableFuture<Integer> accepted;
    private final String responseBody;

    private OneDropHttpServer(String responseBody) throws IOException {
      this.serverSocket = new ServerSocket(0);
      this.responseBody = responseBody;
      this.accepted = CompletableFuture.supplyAsync(this::serve);
    }

    private int port() {
      return serverSocket.getLocalPort();
    }

    private int acceptedConnections() throws Exception {
      return accepted.get(5, TimeUnit.SECONDS);
    }

    private int serve() {
      int count = 0;
      try {
        while (count < 2) {
          try (Socket socket = serverSocket.accept()) {
            count++;
            if (count == 1) {
              continue;
            }
            readRequest(socket);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            byte[] response = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(response);
            output.write(body);
            output.flush();
          }
        }
      } catch (IOException ignored) {
        return count;
      }
      return count;
    }

    private void readRequest(Socket socket) throws IOException {
      socket.setSoTimeout((int) Duration.ofSeconds(2).toMillis());
      BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        // Drain headers before writing the response.
      }
    }

    @Override
    public void close() throws Exception {
      serverSocket.close();
      accepted.get(5, TimeUnit.SECONDS);
    }
  }
}

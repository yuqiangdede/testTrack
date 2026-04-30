package com.shiptrack.clickhouse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Service;

@Service
public class ClickHouseHttpClient {
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final ShipTrackConfig config;
  private final ParsedJdbc parsedJdbc;

  public ClickHouseHttpClient(ObjectMapper objectMapper, ShipConfigService configService) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().build();
    this.config = configService.config();
    this.parsedJdbc = parseJdbc(config.clickhouse.jdbcUrl);
  }

  public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
    return query(sql, params, config.query.clickhouseTimeoutSeconds, config.query.clickhouseTimeoutSeconds);
  }

  public List<Map<String, Object>> query(String sql, Map<String, Object> params, int timeoutSeconds, int maxExecutionTime) {
    String text = post(sql + "\nFORMAT JSONEachRow", params, true, timeoutSeconds, maxExecutionTime);
    List<Map<String, Object>> rows = new ArrayList<>();
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return rows;
    }
    for (String line : trimmed.split("\\R")) {
      try {
        rows.add(objectMapper.readValue(line, MAP_TYPE));
      } catch (IOException error) {
        throw new ClickHouseException("Failed to parse ClickHouse JSONEachRow response", error);
      }
    }
    return rows;
  }

  public Map<String, Object> queryOne(String sql) {
    List<Map<String, Object>> rows = query(sql, Map.of());
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  public Map<String, Object> queryOne(String sql, int timeoutSeconds, int maxExecutionTime) {
    List<Map<String, Object>> rows = query(sql, Map.of(), timeoutSeconds, maxExecutionTime);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  public void runSql(String sql, int timeoutSeconds, int maxExecutionTime) {
    post(sql, Map.of(), false, timeoutSeconds, maxExecutionTime);
  }

  private String post(String body, Map<String, Object> params, boolean wrapConnectionError, int timeoutSeconds, int maxExecutionTime) {
    URI uri = URI.create(parsedJdbc.url + "?" + queryString(params, maxExecutionTime));
    String auth = Base64.getEncoder().encodeToString((config.clickhouse.username + ":" + config.clickhouse.password).getBytes(StandardCharsets.UTF_8));
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .header("Authorization", "Basic " + auth)
        .header("Content-Type", "text/plain; charset=utf-8");
    if (timeoutSeconds > 0) {
      request.timeout(Duration.ofSeconds(timeoutSeconds));
    }
    try {
      HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new ClickHouseException(response.body());
      }
      return response.body();
    } catch (IOException error) {
      String cause = error.getClass().getSimpleName().isBlank() ? "" : " (" + error.getClass().getSimpleName() + ")";
      String message = "ClickHouse connection failed" + cause + ". Check " + parsedJdbc.url + " and the connection settings in config/ship-track.config.json.";
      if (wrapConnectionError) {
        throw new ClickHouseException(message, error);
      }
      throw new ClickHouseException(error.getMessage(), error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ClickHouseException("ClickHouse request interrupted", error);
    }
  }

  private String queryString(Map<String, Object> params, int maxExecutionTime) {
    Map<String, String> search = new LinkedHashMap<>();
    search.put("database", parsedJdbc.database);
    search.put("max_execution_time", String.valueOf(maxExecutionTime));
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      search.put("param_" + entry.getKey(), encodeParam(entry.getValue()));
    }
    StringJoiner joiner = new StringJoiner("&");
    for (Map.Entry<String, String> entry : search.entrySet()) {
      joiner.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
    }
    return joiner.toString();
  }

  private String encodeParam(Object value) {
    if (value instanceof Iterable<?> iterable) {
      StringJoiner joiner = new StringJoiner(",", "[", "]");
      for (Object item : iterable) {
        joiner.add(SqlUtil.sqlString(String.valueOf(item)));
      }
      return joiner.toString();
    }
    return String.valueOf(value);
  }

  private String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private ParsedJdbc parseJdbc(String jdbcUrl) {
    String value = jdbcUrl.replaceFirst("^jdbc:clickhouse://", "");
    int slash = value.indexOf('/');
    String host = slash >= 0 ? value.substring(0, slash) : value;
    String database = slash >= 0 ? value.substring(slash + 1) : "default";
    if (database.isBlank()) {
      database = "default";
    }
    return new ParsedJdbc("http://" + host + "/", database);
  }

  private record ParsedJdbc(String url, String database) {}
}

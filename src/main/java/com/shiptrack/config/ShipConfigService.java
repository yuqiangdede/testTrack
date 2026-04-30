package com.shiptrack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ShipConfigService {
  private final Path root = Path.of("").toAbsolutePath().normalize();
  private final Map<String, String> dotEnv;
  private final ShipTrackConfig config;

  public ShipConfigService(ObjectMapper objectMapper) throws IOException {
    this.dotEnv = loadDotEnv(root.resolve(".env"));
    this.config = objectMapper.readValue(root.resolve("config").resolve("ship-track.config.json").toFile(), ShipTrackConfig.class);
    config.clickhouse.jdbcUrl = envOrDefault("CLICKHOUSE_JDBC_URL", config.clickhouse.jdbcUrl);
    config.clickhouse.username = envOrDefault("CLICKHOUSE_USER", config.clickhouse.username);
    config.clickhouse.password = envOrDefault("CLICKHOUSE_PASSWORD", config.clickhouse.password);
    config.query.logSql = envBooleanOrDefault("SHIP_QUERY_LOG_SQL", config.query.logSql);
  }

  public Path root() {
    return root;
  }

  public ShipTrackConfig config() {
    return config;
  }

  public String envOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      value = dotEnv.get(key);
    }
    return value == null || value.isBlank() ? defaultValue : value;
  }

  public boolean envBooleanOrDefault(String key, boolean defaultValue) {
    String value = envOrDefault(key, String.valueOf(defaultValue));
    return Boolean.parseBoolean(value);
  }

  private Map<String, String> loadDotEnv(Path path) throws IOException {
    Map<String, String> values = new HashMap<>();
    if (!Files.exists(path)) {
      return values;
    }
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
        continue;
      }
      int index = trimmed.indexOf('=');
      String key = trimmed.substring(0, index).trim();
      String value = trimmed.substring(index + 1).trim().replaceAll("^[\"']|[\"']$", "");
      values.putIfAbsent(key, value);
    }
    return values;
  }
}

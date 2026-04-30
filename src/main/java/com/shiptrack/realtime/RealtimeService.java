package com.shiptrack.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.track.TrackRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
@ConditionalOnProperty(name = "ship.mode", havingValue = "server", matchIfMissing = true)
public class RealtimeService {
  private static final List<String> COMPACT_FIELDS = List.of("shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis");
  private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final TrackRepository repository;
  private final ShipTrackConfig config;
  private final ObjectMapper objectMapper;
  private final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();
  private final Object lock = new Object();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private boolean ready;
  private boolean warming;
  private int warmSeq;
  private TimeWindow window = TimeWindow.empty();
  private final Map<String, Map<String, Object>> byShip = new LinkedHashMap<>();
  private List<Map<String, Object>> items = List.of();
  private List<List<Object>> rows = List.of();
  private String watermark = "";
  private boolean polling;

  public RealtimeService(TrackRepository repository, ShipConfigService configService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.config = configService.config();
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void startPollLoop() {
    long intervalMs = Math.max(1, config.query.realtimePollSeconds) * 1000L;
    scheduler.scheduleWithFixedDelay(this::safePollRealtimeDeltas, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stopPollLoop() {
    scheduler.shutdownNow();
  }

  @EventListener(ApplicationReadyEvent.class)
  void warmOnStartup() {
    try {
      warmLatestCache(realtimeWindowFromParams(null, null));
    } catch (RuntimeException error) {
      System.err.println("latest cache warm failed: " + error.getMessage());
    }
  }

  public TimeWindow realtimeWindowFromParams(String start, String end) {
    if ((start != null && !start.isBlank()) || (end != null && !end.isBlank())) {
      return validateTimeWindow(start, end);
    }
    String latest = repository.watermark();
    if (latest == null || latest.isBlank()) {
      return TimeWindow.empty();
    }
    LocalDateTime endDate = parseClickHouseLocal(latest);
    LocalDateTime startDate = endDate.minusHours(Math.max(1, config.query.realtimeWindowHours));
    return new TimeWindow(startDate.format(LOCAL_FORMAT), endDate.format(LOCAL_FORMAT));
  }

  public Map<String, Object> latestResponse(TimeWindow timeWindow) {
    if (timeWindow.start().isBlank() || timeWindow.end().isBlank()) {
      return compactLatest("clickhouse", false, timeWindow, "", List.of(), false);
    }
    synchronized (lock) {
      if (ready && sameWindow(window, timeWindow)) {
        return compactLatest("memory", true, window, watermark, rows, false);
      }
    }
    warmLatestCache(timeWindow);
    synchronized (lock) {
      if (ready && sameWindow(window, timeWindow)) {
        return compactLatest("memory", true, window, watermark, rows, false);
      }
    }
    List<Map<String, Object>> fallback = repository.latest("", config.query.maxLatestShips, null, false, timeWindow.start(), timeWindow.end());
    String fallbackWatermark = fallback.stream()
        .map(item -> String.valueOf(item.getOrDefault("time", "")))
        .max(String::compareTo)
        .orElse("");
    List<List<Object>> fallbackRows = fallback.stream().map(this::pointToRealtimeRow).toList();
    synchronized (lock) {
      return compactLatest("clickhouse", false, timeWindow, fallbackWatermark, fallbackRows, warming);
    }
  }

  public void warmLatestCache(TimeWindow timeWindow) {
    if (timeWindow == null || timeWindow.start().isBlank() || timeWindow.end().isBlank()) {
      return;
    }
    int seq;
    synchronized (lock) {
      if (warming && sameWindow(window, timeWindow)) {
        return;
      }
      seq = warmSeq + 1;
      warmSeq = seq;
      warming = true;
      ready = false;
      window = timeWindow;
      byShip.clear();
      items = List.of();
      rows = List.of();
      watermark = "";
    }
    try {
      List<Map<String, Object>> latest = repository.latest("", config.query.maxLatestShips, null, false, timeWindow.start(), timeWindow.end());
      synchronized (lock) {
        if (seq != warmSeq) {
          return;
        }
        upsertLatestCache(latest);
        ready = true;
        System.out.printf("latest cache warmed: %d ships, window=%s..%s, watermark=%s%n", items.size(), timeWindow.start(), timeWindow.end(), watermark);
      }
    } catch (RuntimeException error) {
      synchronized (lock) {
        if (seq == warmSeq) {
          System.err.println("latest cache warm failed: " + error.getMessage());
        }
      }
      throw error;
    } finally {
      synchronized (lock) {
        if (seq == warmSeq) {
          warming = false;
        }
      }
    }
  }

  public void register(WebSocketSession session) {
    clients.add(session);
  }

  public void unregister(WebSocketSession session) {
    clients.remove(session);
  }

  public Map<String, Object> readyPayload() {
    synchronized (lock) {
      return Map.of(
          "type", "ready",
          "source", "memory",
          "cacheReady", ready,
          "window", window,
          "since", watermark);
    }
  }

  public void pollRealtimeDeltas() {
    TimeWindow currentWindow;
    String since;
    synchronized (lock) {
      if (polling || !ready || watermark.isBlank() || clients.isEmpty()) {
        return;
      }
      polling = true;
      currentWindow = window;
      since = watermark;
    }
    try {
      List<Map<String, Object>> deltas = repository.deltas(since, currentWindow.end());
      if (deltas.isEmpty()) {
        return;
      }
      synchronized (lock) {
        upsertLatestCache(deltas);
      }
      broadcast(Map.of("type", "delta", "since", since, "window", currentWindow, "items", deltas));
    } catch (RuntimeException error) {
      broadcast(Map.of("type", "error", "message", error.getMessage() == null ? String.valueOf(error) : error.getMessage()));
    } finally {
      synchronized (lock) {
        polling = false;
      }
    }
  }

  public TimeWindow validateTimeWindow(String start, String end) {
    if (start == null || end == null || start.isBlank() || end.isBlank()) {
      throw new IllegalArgumentException("time window is invalid");
    }
    Instant startInstant = parseInstant(start);
    Instant endInstant = parseInstant(end);
    if (!startInstant.isBefore(endInstant)) {
      throw new IllegalArgumentException("time window range is invalid");
    }
    return new TimeWindow(start, end);
  }

  public int validateZoom(String value) {
    int zoom = value == null || value.isBlank() ? config.map.defaultZoom : Integer.parseInt(value);
    if (zoom < 3 || zoom > 18) {
      throw new IllegalArgumentException("zoom parameter is invalid");
    }
    return zoom;
  }

  private void safePollRealtimeDeltas() {
    try {
      pollRealtimeDeltas();
    } catch (RuntimeException error) {
      System.err.println("realtime poll failed: " + error.getMessage());
    }
  }

  private void upsertLatestCache(List<Map<String, Object>> nextItems) {
    for (Map<String, Object> item : nextItems) {
      String shipId = String.valueOf(item.getOrDefault("shipId", ""));
      if (shipId.isBlank()) {
        continue;
      }
      Map<String, Object> previous = byShip.get(shipId);
      String time = String.valueOf(item.getOrDefault("time", ""));
      if (previous == null || time.compareTo(String.valueOf(previous.getOrDefault("time", ""))) >= 0) {
        byShip.put(shipId, item);
      }
      if (watermark.isBlank() || time.compareTo(watermark) > 0) {
        watermark = time;
      }
    }
    items = byShip.values().stream()
        .sorted(Comparator.comparing(item -> String.valueOf(item.getOrDefault("shipId", ""))))
        .toList();
    rows = items.stream().map(this::pointToRealtimeRow).toList();
  }

  private List<Object> pointToRealtimeRow(Map<String, Object> item) {
    return List.of(
        String.valueOf(item.getOrDefault("shipId", "")),
        String.valueOf(item.getOrDefault("shipName", "")),
        toDouble(item.get("lng")),
        toDouble(item.get("lat")),
        toDouble(item.getOrDefault("speed", 0)),
        toDouble(item.getOrDefault("heading", 0)),
        String.valueOf(item.getOrDefault("time", "")),
        toDouble(item.getOrDefault("isAis", 0)));
  }

  private Map<String, Object> compactLatest(String source, boolean ready, TimeWindow timeWindow, String watermark, List<List<Object>> itemRows, boolean warming) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", source);
    body.put("compact", true);
    body.put("fields", COMPACT_FIELDS);
    body.put("ready", ready);
    if (warming) {
      body.put("warming", true);
    }
    body.put("window", timeWindow);
    body.put("watermark", watermark);
    body.put("items", itemRows);
    body.put("nextCursor", "");
    body.put("hasMore", false);
    return body;
  }

  private void broadcast(Map<String, Object> payload) {
    String text;
    try {
      text = objectMapper.writeValueAsString(payload);
    } catch (IOException error) {
      return;
    }
    TextMessage message = new TextMessage(text);
    List<WebSocketSession> closed = new ArrayList<>();
    for (WebSocketSession client : clients) {
      try {
        if (client.isOpen()) {
          client.sendMessage(message);
        } else {
          closed.add(client);
        }
      } catch (IOException error) {
        closed.add(client);
      }
    }
    clients.removeAll(closed);
  }

  private boolean sameWindow(TimeWindow a, TimeWindow b) {
    return String.valueOf(a == null ? "" : a.start()).equals(String.valueOf(b == null ? "" : b.start()))
        && String.valueOf(a == null ? "" : a.end()).equals(String.valueOf(b == null ? "" : b.end()));
  }

  private double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (RuntimeException error) {
      return 0.0;
    }
  }

  private Instant parseInstant(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return parseClickHouseLocal(value).toInstant(ZoneOffset.ofHours(8));
    }
  }

  private LocalDateTime parseClickHouseLocal(String value) {
    String normalized = value.trim().replace(' ', 'T');
    return LocalDateTime.parse(normalized);
  }
}

package com.shiptrack.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.realtime.RealtimeService;
import com.shiptrack.track.TrackRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class GlobalReplayWebSocketHandler extends TextWebSocketHandler {
  static final int CHUNK_SECONDS = 30 * 60;
  private static final ZoneOffset LOCAL_OFFSET = ZoneOffset.ofHours(8);
  private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final List<String> TRACK_COMPACT_FIELDS = List.of(
      "shipId", "shipName", "lng", "lat", "speed", "heading", "isAis", "shipType", "time", "bucketStart");

  private final TrackRepository repository;
  private final RealtimeService realtimeService;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor;
  private final Map<String, SessionTask> tasks = new ConcurrentHashMap<>();

  @Autowired
  public GlobalReplayWebSocketHandler(TrackRepository repository, RealtimeService realtimeService, ObjectMapper objectMapper) {
    this(repository, realtimeService, objectMapper, Executors.newCachedThreadPool(daemonThreadFactory()));
  }

  GlobalReplayWebSocketHandler(TrackRepository repository, RealtimeService realtimeService, ObjectMapper objectMapper,
      ExecutorService executor) {
    this.repository = repository;
    this.realtimeService = realtimeService;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    StartRequest request = objectMapper.readValue(message.getPayload(), StartRequest.class);
    if (!"start".equals(String.valueOf(request.type))) {
      send(session, Map.of("type", "error", "message", "unsupported global replay message"));
      return;
    }
    cancelTask(session.getId());
    AtomicBoolean cancelled = new AtomicBoolean(false);
    Future<?> future = executor.submit(() -> runReplay(session, request, cancelled));
    tasks.put(session.getId(), new SessionTask(future, cancelled));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    cancelTask(session.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    cancelTask(session.getId());
    super.handleTransportError(session, exception);
  }

  private void runReplay(WebSocketSession session, StartRequest request, AtomicBoolean cancelled) {
    int totalItems = 0;
    try {
      TimeWindow window = realtimeService.globalWindowFromParams(request.timePoint, request.hours);
      int zoom = realtimeService.validateZoom(request.zoom);
      List<TimeWindow> chunks = chunkWindows(window);
      send(session, Map.of(
          "type", "started",
          "window", window,
          "chunkSeconds", CHUNK_SECONDS,
          "totalChunks", chunks.size()));
      for (int index = 0; index < chunks.size(); index += 1) {
        if (cancelled.get() || !session.isOpen()) {
          return;
        }
        TimeWindow chunk = chunks.get(index);
        Map<String, Object> payload = compactTrackRows(repository.globalSegment(
            chunk.start(),
            chunk.end(),
            zoom,
            normalizeSamplingMode(request.samplingMode),
            parseBucketSeconds(request.bucketSeconds)));
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) payload.get("items");
        totalItems += rows.size();
        payload.put("type", "chunk");
        payload.put("chunkIndex", index + 1);
        payload.put("totalChunks", chunks.size());
        payload.put("start", chunk.start());
        payload.put("end", chunk.end());
        payload.put("itemCount", rows.size());
        send(session, payload);
      }
      if (!cancelled.get() && session.isOpen()) {
        send(session, Map.of("type", "complete", "totalItems", totalItems, "totalChunks", chunks.size()));
      }
    } catch (RuntimeException | IOException error) {
      sendError(session, error.getMessage());
    } finally {
      tasks.computeIfPresent(session.getId(), (id, task) -> task.cancelled() == cancelled ? null : task);
    }
  }

  static List<TimeWindow> chunkWindows(TimeWindow window) {
    Instant start = parseTime(window.start());
    Instant end = parseTime(window.end());
    if (!end.isAfter(start)) {
      throw new IllegalArgumentException("global replay window range is invalid");
    }
    List<TimeWindow> chunks = new ArrayList<>();
    Instant cursor = start;
    while (cursor.isBefore(end)) {
      Instant next = cursor.plusSeconds(CHUNK_SECONDS);
      if (next.isAfter(end)) {
        next = end;
      }
      chunks.add(new TimeWindow(formatClickHouseTime(cursor), formatClickHouseTime(next)));
      cursor = next;
    }
    return chunks;
  }

  private Map<String, Object> compactTrackRows(List<Map<String, Object>> items) {
    List<List<Object>> rows = items.stream().map(item -> {
      List<Object> row = new ArrayList<>(TRACK_COMPACT_FIELDS.size());
      row.add(String.valueOf(item.getOrDefault("shipId", "")));
      row.add(String.valueOf(item.getOrDefault("shipName", "")));
      row.add(valueOrZero(item.get("lng")));
      row.add(valueOrZero(item.get("lat")));
      row.add(valueOrZero(item.get("speed")));
      row.add(valueOrZero(item.get("heading")));
      row.add(valueOrZero(item.get("isAis")));
      row.add(valueOrZero(item.get("shipType")));
      row.add(String.valueOf(item.getOrDefault("time", "")));
      row.add(String.valueOf(item.getOrDefault("bucketStart", "")));
      return row;
    }).toList();
    return new LinkedHashMap<>(Map.of("compact", true, "fields", TRACK_COMPACT_FIELDS, "items", rows));
  }

  private Object valueOrZero(Object value) {
    return value == null ? 0 : value;
  }

  private void cancelTask(String sessionId) {
    SessionTask task = tasks.remove(sessionId);
    if (task == null) {
      return;
    }
    task.cancelled().set(true);
    task.future().cancel(true);
  }

  private void send(WebSocketSession session, Object payload) throws IOException {
    if (!session.isOpen()) {
      return;
    }
    synchronized (session) {
      if (session.isOpen()) {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
      }
    }
  }

  private void sendError(WebSocketSession session, String message) {
    try {
      send(session, Map.of("type", "error", "message", message == null || message.isBlank() ? "global replay failed" : message));
    } catch (IOException ignored) {
      // The connection is already broken; cancellation will stop remaining chunks.
    }
  }

  private static String normalizeSamplingMode(String samplingMode) {
    if (samplingMode == null) {
      return "auto";
    }
    String normalized = samplingMode.trim().toLowerCase();
    return switch (normalized) {
      case "raw", "manual", "auto" -> normalized;
      default -> "auto";
    };
  }

  private static Integer parseBucketSeconds(String bucketSeconds) {
    if (bucketSeconds == null || bucketSeconds.isBlank()) {
      return null;
    }
    return Math.max(1, Integer.parseInt(bucketSeconds));
  }

  private static Instant parseTime(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(LOCAL_OFFSET);
    }
  }

  private static String formatClickHouseTime(Instant value) {
    return CLICKHOUSE_TIME.format(LocalDateTime.ofInstant(value, LOCAL_OFFSET));
  }

  private static ThreadFactory daemonThreadFactory() {
    return runnable -> {
      Thread thread = new Thread(runnable, "global-replay-ws");
      thread.setDaemon(true);
      return thread;
    };
  }

  private record SessionTask(Future<?> future, AtomicBoolean cancelled) {}

  static class StartRequest {
    public String type;
    public String timePoint;
    public String hours;
    public String zoom;
    public String samplingMode;
    public String bucketSeconds;
  }
}

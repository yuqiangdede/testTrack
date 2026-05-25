package com.shiptrack.realtime;

import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.track.TrackRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class RealtimeService {
  private static final List<String> COMPACT_FIELDS = List.of("shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis", "shipType");
  private static final List<String> AGGREGATE_FIELDS = List.of("lng", "lat", "count");
  private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
  private static final double INDEX_CELL_SIZE = 0.05;
  private static final int LOW_ZOOM_HEAT_MAX_ZOOM = 8;
  private static final int MAX_VIEWPORT_POINTS = 20000;

  private final TrackRepository repository;
  private final ShipTrackConfig config;
  private final Object lock = new Object();

  private Map<String, Object> databaseStats = Map.of("trackPoints", 0L, "ships", 0L);
  private boolean ready;
  private boolean warming;
  private int warmSeq;
  private TimeWindow window = TimeWindow.empty();
  private final Map<String, Map<String, Object>> byShip = new LinkedHashMap<>();
  private Map<String, List<Map<String, Object>>> grid = Map.of();
  private List<Map<String, Object>> items = List.of();
  private List<List<Object>> rows = List.of();
  private String watermark = "";

  public RealtimeService(TrackRepository repository, ShipConfigService configService) {
    this.repository = repository;
    this.config = configService.config();
  }

  @EventListener(ApplicationReadyEvent.class)
  void warmOnStartup() {
    try {
      warmLatestCache(defaultRealtimeWindow());
    } catch (RuntimeException error) {
      log.warn("latest cache warm failed: {}", error.getMessage(), error);
    }
    CompletableFuture.runAsync(() -> {
      try {
        warmDatabaseStats();
      } catch (RuntimeException error) {
        log.warn("database stats warm failed: {}", error.getMessage(), error);
      }
    });
  }

  public TimeWindow realtimeWindowFromParams(String timePoint, String minutes) {
    if ((timePoint != null && !timePoint.isBlank()) || (minutes != null && !minutes.isBlank())) {
      LocalDateTime endDate = parseRealtimeAnchor(timePoint);
      int lookbackMinutes = resolveRealtimeMinutes(minutes);
      LocalDateTime startDate = endDate.minusMinutes(lookbackMinutes);
      return new TimeWindow(startDate.format(LOCAL_FORMAT), endDate.format(LOCAL_FORMAT));
    }
    return defaultRealtimeWindow();
  }

  public TimeWindow globalWindowFromParams(String timePoint, String hours) {
    LocalDateTime endDate = parseRealtimeAnchor(timePoint);
    int lookbackHours = resolveGlobalHours(hours);
    LocalDateTime startDate = endDate.minusHours(lookbackHours);
    return new TimeWindow(startDate.format(LOCAL_FORMAT), endDate.format(LOCAL_FORMAT));
  }

  public int cachedShipCount() {
    synchronized (lock) {
      return items.size();
    }
  }

  public Map<String, Object> databaseStats() {
    synchronized (lock) {
      return databaseStats;
    }
  }

  public Map<String, Object> latestResponse(TimeWindow timeWindow) {
    if (timeWindow.start().isBlank() || timeWindow.end().isBlank()) {
      return compactLatest("clickhouse", false, timeWindow, "", List.of(), false, cachedShipCount());
    }
    synchronized (lock) {
      if (ready && sameWindow(window, timeWindow)) {
        return compactLatest("memory", true, window, watermark, rows, false, cachedShipCount());
      }
    }
    if (sameWindow(defaultRealtimeWindow(), timeWindow)) {
      synchronized (lock) {
        return compactLatest("memory", false, timeWindow, watermark, rows, warming, cachedShipCount());
      }
    }
    warmLatestCache(timeWindow);
    synchronized (lock) {
      if (ready && sameWindow(window, timeWindow)) {
        return compactLatest("memory", true, window, watermark, rows, false, cachedShipCount());
      }
    }
    List<Map<String, Object>> fallback = repository.latest("", config.query.realtimeCacheMaxShips, null, false, timeWindow.start(), timeWindow.end());
    String fallbackWatermark = fallback.stream()
        .map(item -> String.valueOf(item.getOrDefault("time", "")))
        .max(String::compareTo)
        .orElse("");
    List<List<Object>> fallbackRows = fallback.stream().map(this::pointToRealtimeRow).toList();
    synchronized (lock) {
      return compactLatest("clickhouse", false, timeWindow, fallbackWatermark, fallbackRows, warming, cachedShipCount());
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
      grid = Map.of();
      items = List.of();
      rows = List.of();
      watermark = "";
    }
    try {
      List<Map<String, Object>> latest = repository.latest("", config.query.realtimeCacheMaxShips, null, false, timeWindow.start(), timeWindow.end());
      synchronized (lock) {
        if (seq != warmSeq) {
          return;
        }
        upsertLatestCache(latest);
        ready = true;
        log.info("latest cache warmed ships={} rows={} window={}..{} watermark={}", items.size(), rows.size(), timeWindow.start(), timeWindow.end(), watermark);
      }
    } catch (RuntimeException error) {
      synchronized (lock) {
        if (seq == warmSeq) {
          log.warn("latest cache warm failed: {}", error.getMessage(), error);
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

  private void warmDatabaseStats() {
    Map<String, Object> stats = repository.databaseStats();
    synchronized (lock) {
      databaseStats = Map.copyOf(stats);
    }
  }

  private TimeWindow defaultRealtimeWindow() {
    LocalDateTime endDate = parseClickHouseLocal(config.query.defaultRealtimeAnchor);
    LocalDateTime startDate = endDate.minusMinutes(Math.max(1, config.query.defaultRealtimeWindowMinutes));
    return new TimeWindow(startDate.format(LOCAL_FORMAT), endDate.format(LOCAL_FORMAT));
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

  public Map<String, Object> viewportResponse(TimeWindow timeWindow, BBox bbox, int zoom, Set<String> types) {
    if (timeWindow == null || timeWindow.start().isBlank() || timeWindow.end().isBlank()) {
      return compactViewport("heat", false, timeWindow, "", List.of(), 0, 0, false);
    }
    synchronized (lock) {
      if (!ready || !sameWindow(window, timeWindow)) {
        return compactViewport("heat", false, timeWindow, watermark, List.of(), 0, cachedShipCount(), warming);
      }
      if (bbox == null) {
        return compactViewport("heat", true, window, watermark, List.of(), 0, cachedShipCount(), false);
      }
      List<Map<String, Object>> visible = queryViewportItems(bbox, types);
      if (zoom <= LOW_ZOOM_HEAT_MAX_ZOOM || visible.size() > MAX_VIEWPORT_POINTS) {
        return compactViewport("aggregate", true, window, watermark, aggregateRows(visible, zoom), visible.size(), cachedShipCount(), false);
      }
      List<List<Object>> itemRows = visible.stream().map(this::pointToRealtimeRow).toList();
      return compactViewport("points", true, window, watermark, itemRows, visible.size(), cachedShipCount(), false);
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

  private LocalDateTime parseRealtimeAnchor(String value) {
    if (value == null || value.isBlank()) {
      String latest = repository.watermark();
      if (latest == null || latest.isBlank()) {
        throw new IllegalArgumentException("time point is required");
      }
      return parseClickHouseLocal(latest);
    }
    try {
      return Instant.parse(value).atZone(ZoneOffset.ofHours(8)).toLocalDateTime();
    } catch (RuntimeException ignored) {
      return parseClickHouseLocal(value);
    }
  }

  private int resolveRealtimeMinutes(String value) {
    int minutes = value == null || value.isBlank() ? config.query.realtimeWindowMinutes : Integer.parseInt(value);
    if (minutes < 1) {
      throw new IllegalArgumentException("realtime window minutes is invalid");
    }
    return minutes;
  }

  private int resolveGlobalHours(String value) {
    int hours = value == null || value.isBlank() ? config.query.globalSegmentHours : Integer.parseInt(value);
    if (hours < 1) {
      throw new IllegalArgumentException("global replay hours is invalid");
    }
    return hours;
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
    grid = buildGrid(items);
  }

  private Map<String, List<Map<String, Object>>> buildGrid(List<Map<String, Object>> points) {
    Map<String, List<Map<String, Object>>> nextGrid = new HashMap<>();
    for (Map<String, Object> item : points) {
      double lng = toDouble(item.get("lng"));
      double lat = toDouble(item.get("lat"));
      if (!Double.isFinite(lng) || !Double.isFinite(lat)) {
        continue;
      }
      nextGrid.computeIfAbsent(cellKey(lng, lat, INDEX_CELL_SIZE), ignored -> new ArrayList<>()).add(item);
    }
    return nextGrid;
  }

  private List<Map<String, Object>> queryViewportItems(BBox bbox, Set<String> types) {
    Set<String> selectedTypes = normalizeTypes(types);
    int westCell = (int) Math.floor(bbox.west() / INDEX_CELL_SIZE);
    int eastCell = (int) Math.floor(bbox.east() / INDEX_CELL_SIZE);
    int southCell = (int) Math.floor(bbox.south() / INDEX_CELL_SIZE);
    int northCell = (int) Math.floor(bbox.north() / INDEX_CELL_SIZE);
    List<Map<String, Object>> visible = new ArrayList<>();
    for (int x = westCell; x <= eastCell; x++) {
      for (int y = southCell; y <= northCell; y++) {
        List<Map<String, Object>> bucket = grid.get(x + ":" + y);
        if (bucket == null) {
          continue;
        }
        for (Map<String, Object> item : bucket) {
          double lng = toDouble(item.get("lng"));
          double lat = toDouble(item.get("lat"));
          if (lng >= bbox.west() && lng <= bbox.east()
              && lat >= bbox.south() && lat <= bbox.north()
              && matchesType(item, selectedTypes)) {
            visible.add(item);
          }
        }
      }
    }
    visible.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("shipId", ""))));
    return visible;
  }

  private List<List<Object>> aggregateRows(List<Map<String, Object>> points, int zoom) {
    double cellSize = aggregateCellSize(zoom);
    Map<String, AggregateCell> cells = new LinkedHashMap<>();
    for (Map<String, Object> item : points) {
      double lng = toDouble(item.get("lng"));
      double lat = toDouble(item.get("lat"));
      if (!Double.isFinite(lng) || !Double.isFinite(lat)) {
        continue;
      }
      cells.computeIfAbsent(cellKey(lng, lat, cellSize), ignored -> new AggregateCell()).add(lng, lat);
    }
    return cells.values().stream()
        .sorted(Comparator.comparingLong(AggregateCell::count).reversed())
        .limit(config.query.maxDensityCells)
        .map(AggregateCell::row)
        .toList();
  }

  private double aggregateCellSize(int zoom) {
    if (zoom <= 6) {
      return 0.5;
    }
    if (zoom <= 8) {
      return 0.1;
    }
    if (zoom <= 10) {
      return 0.05;
    }
    return 0.02;
  }

  private String cellKey(double lng, double lat, double cellSize) {
    return (int) Math.floor(lng / cellSize) + ":" + (int) Math.floor(lat / cellSize);
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
        toDouble(item.getOrDefault("isAis", 0)),
        toDouble(item.getOrDefault("shipType", 0)));
  }

  private Map<String, Object> compactLatest(String source, boolean ready, TimeWindow timeWindow, String watermark, List<List<Object>> itemRows, boolean warming, int memoryShips) {
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
    body.put("memoryShips", memoryShips);
    body.put("memoryRows", itemRows.size());
    body.put("items", itemRows);
    body.put("nextCursor", "");
    body.put("hasMore", false);
    return body;
  }

  private Map<String, Object> compactViewport(String mode, boolean ready, TimeWindow timeWindow, String watermark, List<List<Object>> itemRows,
      int total, int memoryShips, boolean warming) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", "memory");
    body.put("compact", true);
    body.put("mode", mode);
    body.put("fields", "points".equals(mode) ? COMPACT_FIELDS : AGGREGATE_FIELDS);
    body.put("ready", ready);
    if (warming) {
      body.put("warming", true);
    }
    body.put("window", timeWindow == null ? TimeWindow.empty() : timeWindow);
    body.put("watermark", watermark);
    body.put("total", total);
    body.put("memoryShips", memoryShips);
    body.put("items", itemRows);
    return body;
  }

  private Set<String> normalizeTypes(Set<String> types) {
    if (types == null || types.isEmpty()) {
      return Set.of();
    }
    Set<String> selected = new HashSet<>();
    for (String type : types) {
      if (type != null && !type.isBlank()) {
        selected.add(type.trim().toLowerCase());
      }
    }
    return selected;
  }

  private boolean matchesType(Map<String, Object> item, Set<String> selectedTypes) {
    return selectedTypes.isEmpty() || selectedTypes.contains(shipTypeId(item));
  }

  private String shipTypeId(Map<String, Object> item) {
    int shipType = (int) toDouble(item.getOrDefault("shipType", 0));
    return switch (shipType) {
      case 1 -> "ais";
      case 2 -> "radar";
      case 3 -> "beidou";
      case 4 -> "aisbeidou";
      case 5 -> "aisradar";
      case 6 -> "beidouradar";
      case 7 -> "beidouradarais";
      default -> toDouble(item.getOrDefault("isAis", 0)) == 1.0 ? "ais" : "radar";
    };
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

  private static final class AggregateCell {
    private double lngSum;
    private double latSum;
    private long count;

    void add(double lng, double lat) {
      lngSum += lng;
      latSum += lat;
      count += 1;
    }

    long count() {
      return count;
    }

    List<Object> row() {
      if (count <= 0) {
        return List.of(0.0, 0.0, 0L);
      }
      return List.of(lngSum / count, latSum / count, count);
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

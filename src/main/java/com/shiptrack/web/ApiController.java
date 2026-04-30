package com.shiptrack.web;

import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.realtime.RealtimeService;
import com.shiptrack.telemetry.RequestMetricsService;
import com.shiptrack.track.TrackRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "ship.mode", havingValue = "server", matchIfMissing = true)
public class ApiController {
  private static final Logger log = LoggerFactory.getLogger(ApiController.class);
  private final ShipConfigService configService;
  private final ShipTrackConfig config;
  private final TrackRepository repository;
  private final RealtimeService realtimeService;
  private final RequestMetricsService requestMetricsService;

  public ApiController(ShipConfigService configService, TrackRepository repository, RealtimeService realtimeService,
      RequestMetricsService requestMetricsService) {
    this.configService = configService;
    this.config = configService.config();
    this.repository = repository;
    this.realtimeService = realtimeService;
    this.requestMetricsService = requestMetricsService;
  }

  @GetMapping("/api/config/map")
  public Map<String, Object> mapConfig() {
    return trace("/api/config/map", () -> {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("coordinateSystem", config.map.coordinateSystem);
      body.put("defaultCenter", config.map.defaultCenter);
      body.put("defaultZoom", config.map.defaultZoom);
      body.put("maxMultiShips", config.query.maxMultiShips);
      body.put("globalSegmentHours", config.query.globalSegmentHours);
      body.put("amapKey", configService.envOrDefault("VITE_AMAP_KEY", ""));
      body.put("amapSecurityJsCode", configService.envOrDefault("VITE_AMAP_SECURITY_JS_CODE", ""));
      return body;
    });
  }

  @GetMapping("/api/realtime/latest")
  public Map<String, Object> latest(@RequestParam(required = false) String start, @RequestParam(required = false) String end,
      @RequestParam(required = false) String timePoint, @RequestParam(required = false) String minutes) {
    return trace("/api/realtime/latest", () -> {
      TimeWindow timeWindow = realtimeWindow(start, end, timePoint, minutes);
      return realtimeService.latestResponse(timeWindow);
    });
  }

  @GetMapping("/api/stats/realtime-summary")
  public Map<String, Object> realtimeSummary(@RequestParam(required = false) String start, @RequestParam(required = false) String end,
      @RequestParam(required = false) String timePoint, @RequestParam(required = false) String minutes,
      @RequestParam(required = false) String west, @RequestParam(required = false) String south,
      @RequestParam(required = false) String east, @RequestParam(required = false) String north,
      @RequestParam(required = false) String zoom) {
    return trace("/api/stats/realtime-summary", () -> {
      TimeWindow timeWindow = realtimeWindow(start, end, timePoint, minutes);
      Map<String, Object> database = realtimeService.databaseStats();
      Map<String, Object> window = timeWindow.start().isBlank() || timeWindow.end().isBlank()
          ? Map.of("trackPoints", 0L, "ships", 0L)
          : repository.windowStats(timeWindow.start(), timeWindow.end());
      int zoomValue = realtimeService.validateZoom(zoom);
      BBox bbox = bboxOrNull(west, south, east, north);
      long windowHeatCells = timeWindow.start().isBlank() || timeWindow.end().isBlank()
          ? 0
          : repository.densityCellCount(timeWindow.start(), timeWindow.end(), null, zoomValue);
      long viewportHeatCells = timeWindow.start().isBlank() || timeWindow.end().isBlank() || bbox == null
          ? 0
          : repository.densityCellCount(timeWindow.start(), timeWindow.end(), bbox, zoomValue);
      return Map.of(
          "databaseTrackPoints", database.get("trackPoints"),
          "databaseShips", database.get("ships"),
          "windowTrackPoints", window.get("trackPoints"),
          "windowShips", window.get("ships"),
          "windowHeatCells", windowHeatCells,
          "viewportHeatCells", viewportHeatCells);
    });
  }

  @GetMapping("/api/stats/database")
  public Map<String, Object> databaseStats() {
    return trace("/api/stats/database", realtimeService::databaseStats);
  }

  @GetMapping("/api/stats/global-summary")
  public Map<String, Object> globalSummary(@RequestParam(required = false) String timePoint,
      @RequestParam(required = false) String hours) {
    return trace("/api/stats/global-summary", () -> {
      TimeWindow timeWindow = realtimeService.globalWindowFromParams(timePoint, hours);
      Map<String, Object> database = realtimeService.databaseStats();
      Map<String, Object> window = repository.windowStats(timeWindow.start(), timeWindow.end());
      return Map.of(
          "databaseTrackPoints", database.get("trackPoints"),
          "databaseShips", database.get("ships"),
          "windowTrackPoints", window.get("trackPoints"));
    });
  }

  @GetMapping("/api/stats/multi-summary")
  public Map<String, Object> multiSummary(@RequestParam String start, @RequestParam String end,
      @RequestParam(required = false) String west, @RequestParam(required = false) String south,
      @RequestParam(required = false) String east, @RequestParam(required = false) String north) {
    return trace("/api/stats/multi-summary", () -> {
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      BBox bbox = bboxOrNull(west, south, east, north);
      return repository.multiStats(time.start(), time.end(), bbox);
    });
  }

  @GetMapping("/api/stats/single-track-points")
  public Map<String, Object> singleTrackPointStats(@RequestParam String shipId, @RequestParam String start,
      @RequestParam String end) {
    return trace("/api/stats/single-track-points", () -> {
      if (shipId == null || shipId.isBlank()) {
        throw new IllegalArgumentException("shipId parameter is required");
      }
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      return Map.of("trackPoints", repository.singleTrackPointCount(shipId, time.start(), time.end()));
    });
  }

  @PostMapping("/api/stats/multi-track-points")
  public Map<String, Object> multiTrackPointStats(@RequestBody MultiTrackRequest body) {
    return trace("/api/stats/multi-track-points", () -> {
      List<String> shipIds = body.shipIds == null ? List.of() : body.shipIds.stream().limit(config.query.maxMultiShips).toList();
      if (shipIds.isEmpty()) {
        throw new IllegalArgumentException("shipIds parameter is required");
      }
      TimeWindow time = realtimeService.validateTimeWindow(body.start, body.end);
      return Map.of("trackPoints", repository.multiTrackPointCount(shipIds, time.start(), time.end()));
    });
  }

  @GetMapping("/api/analysis/density")
  public Map<String, Object> density(@RequestParam(required = false) String start, @RequestParam(required = false) String end,
      @RequestParam(required = false) String timePoint, @RequestParam(required = false) String minutes,
      @RequestParam String west, @RequestParam String south, @RequestParam String east, @RequestParam String north,
      @RequestParam(required = false) String zoom) {
    return trace("/api/analysis/density", () -> {
      TimeWindow time = realtimeWindow(start, end, timePoint, minutes);
      BBox bbox = bbox(west, south, east, north);
      return Map.of("items", repository.density(time.start(), time.end(), bbox, realtimeService.validateZoom(zoom)));
    });
  }

  @GetMapping("/api/tracks/single")
  public Map<String, Object> single(@RequestParam String shipId, @RequestParam String start, @RequestParam String end,
      @RequestParam(required = false) String zoom, @RequestParam(required = false) String samplingMode,
      @RequestParam(required = false) String bucketSeconds) {
    return trace("/api/tracks/single", () -> {
      if (shipId == null || shipId.isBlank()) {
        throw new IllegalArgumentException("shipId parameter is required");
      }
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      return Map.of("items", repository.singleTrackRows(
          shipId,
          time.start(),
          time.end(),
          realtimeService.validateZoom(zoom),
          null,
          normalizeSamplingMode(samplingMode),
          parseBucketSeconds(bucketSeconds)));
    });
  }

  @GetMapping("/api/tracks/candidates")
  public Map<String, Object> candidates(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north,
      @RequestParam(required = false) String page, @RequestParam(required = false) String pageSize,
      @RequestParam(required = false) String shipTypes) {
    return trace("/api/tracks/candidates", () -> {
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      int pageValue = page == null || page.isBlank() ? 1 : Integer.parseInt(page);
      int pageSizeValue = pageSize == null || pageSize.isBlank() ? 100 : Integer.parseInt(pageSize);
      List<String> shipTypeValues = parseShipTypes(shipTypes);
      return Map.of("items", repository.candidates(time.start(), time.end(), bbox(west, south, east, north), pageValue, pageSizeValue, shipTypeValues));
    });
  }

  @PostMapping("/api/tracks/multi")
  public Map<String, Object> multi(@RequestBody MultiTrackRequest body) {
    return trace("/api/tracks/multi", () -> {
      List<String> shipIds = body.shipIds == null ? List.of() : body.shipIds.stream().limit(config.query.maxMultiShips).toList();
      if (shipIds.isEmpty()) {
        throw new IllegalArgumentException("shipIds parameter is required");
      }
      TimeWindow time = realtimeService.validateTimeWindow(body.start, body.end);
      int zoom = body.zoom == null ? config.map.defaultZoom : body.zoom;
      return Map.of("items", repository.trackRows(
          shipIds,
          time.start(),
          time.end(),
          zoom,
          null,
          "multi",
          normalizeSamplingMode(body.samplingMode),
          body.bucketSeconds));
    });
  }

  @GetMapping("/api/tracks/global-segment")
  public Map<String, Object> globalSegment(@RequestParam(required = false) String timePoint,
      @RequestParam(required = false) String hours, @RequestParam(required = false) String zoom,
      @RequestParam(required = false) String samplingMode, @RequestParam(required = false) String bucketSeconds) {
    return trace("/api/tracks/global-segment", () -> {
      TimeWindow time = realtimeService.globalWindowFromParams(timePoint, hours);
      return Map.of("items", repository.globalSegment(
          time.start(),
          time.end(),
          realtimeService.validateZoom(zoom),
          normalizeSamplingMode(samplingMode),
          parseBucketSeconds(bucketSeconds)));
    });
  }

  private BBox bbox(String west, String south, String east, String north) {
    BBox bbox = new BBox(Double.parseDouble(west), Double.parseDouble(south), Double.parseDouble(east), Double.parseDouble(north));
    bbox.validate();
    return bbox;
  }

  private BBox bboxOrNull(String west, String south, String east, String north) {
    if (west == null || west.isBlank() || south == null || south.isBlank() || east == null || east.isBlank() || north == null || north.isBlank()) {
      return null;
    }
    return bbox(west, south, east, north);
  }

  private TimeWindow realtimeWindow(String start, String end, String timePoint, String minutes) {
    if ((timePoint != null && !timePoint.isBlank()) || (minutes != null && !minutes.isBlank())) {
      return realtimeService.realtimeWindowFromParams(timePoint, minutes);
    }
    if ((start != null && !start.isBlank()) || (end != null && !end.isBlank())) {
      return realtimeService.validateTimeWindow(start, end);
    }
    return realtimeService.realtimeWindowFromParams(null, null);
  }

  private List<String> parseShipTypes(String shipTypes) {
    if (shipTypes == null || shipTypes.isBlank()) {
      return List.of("ais");
    }
    List<String> values = new java.util.ArrayList<>();
    for (String value : shipTypes.split(",")) {
      String normalized = value == null ? "" : value.trim().toLowerCase();
      if (("ais".equals(normalized) || "radar".equals(normalized)) && !values.contains(normalized)) {
        values.add(normalized);
      }
    }
    return values.isEmpty() ? List.of("ais") : values;
  }

  private String normalizeSamplingMode(String samplingMode) {
    if (samplingMode == null) {
      return "auto";
    }
    String normalized = samplingMode.trim().toLowerCase();
    return switch (normalized) {
      case "raw", "manual", "auto" -> normalized;
      default -> "auto";
    };
  }

  private Integer parseBucketSeconds(String bucketSeconds) {
    if (bucketSeconds == null || bucketSeconds.isBlank()) {
      return null;
    }
    return Math.max(1, Integer.parseInt(bucketSeconds));
  }

  private <T> T trace(String endpoint, Supplier<T> action) {
    requestMetricsService.start(endpoint);
    long started = System.nanoTime();
    try {
      T result = action.get();
      logSuccess(endpoint, started, result, requestMetricsService.snapshot());
      return result;
    } catch (RuntimeException error) {
      logFailure(endpoint, started, requestMetricsService.snapshot(), error);
      throw error;
    } finally {
      requestMetricsService.stop();
    }
  }

  private void logSuccess(String endpoint, long started, Object result, RequestMetricsService.RequestMetrics metrics) {
    long elapsedMs = RequestMetricsService.nanosToMillis(System.nanoTime() - started);
    long dbElapsedMs = RequestMetricsService.nanosToMillis(metrics.dbElapsedNanos());
    if (result instanceof Map<?, ?> map) {
      long items = itemCount(map.get("items"));
      Object source = map.get("source");
      Object ready = map.get("ready");
      Object warming = map.get("warming");
      Object memoryShips = map.get("memoryShips");
      Object memoryRows = map.get("memoryRows");
      Object watermark = map.get("watermark");
      if (items > 0 || source != null || memoryShips != null) {
        log.info("api request ok endpoint={} elapsedMs={} dbElapsedMs={} dbCalls={} items={} source={} ready={} warming={} memoryShips={} memoryRows={} watermark={}",
            endpoint, elapsedMs, dbElapsedMs, metrics.dbCalls(), items, source, ready, warming, memoryShips, memoryRows, watermark);
        return;
      }
      if (map.containsKey("trackPoints") && map.containsKey("ships") && !map.containsKey("windowTrackPoints")) {
        log.info("api request ok endpoint={} elapsedMs={} dbElapsedMs={} dbCalls={} trackPoints={} ships={}",
            endpoint, elapsedMs, dbElapsedMs, metrics.dbCalls(), map.get("trackPoints"), map.get("ships"));
        return;
      }
      if (map.containsKey("databaseTrackPoints") || map.containsKey("windowTrackPoints")) {
        log.info("api request ok endpoint={} elapsedMs={} dbElapsedMs={} dbCalls={} databaseTrackPoints={} databaseShips={} windowTrackPoints={} windowShips={} windowHeatCells={} viewportHeatCells={}",
            endpoint, elapsedMs, dbElapsedMs, metrics.dbCalls(),
            map.get("databaseTrackPoints"), map.get("databaseShips"), map.get("windowTrackPoints"), map.get("windowShips"),
            map.get("windowHeatCells"), map.get("viewportHeatCells"));
        return;
      }
    }
    log.info("api request ok endpoint={} elapsedMs={} dbElapsedMs={} dbCalls={}", endpoint, elapsedMs, dbElapsedMs, metrics.dbCalls());
  }

  private void logFailure(String endpoint, long started, RequestMetricsService.RequestMetrics metrics, RuntimeException error) {
    long elapsedMs = RequestMetricsService.nanosToMillis(System.nanoTime() - started);
    long dbElapsedMs = RequestMetricsService.nanosToMillis(metrics.dbElapsedNanos());
    log.warn("api request failed endpoint={} elapsedMs={} dbElapsedMs={} dbCalls={} message={}",
        endpoint, elapsedMs, dbElapsedMs, metrics.dbCalls(), error.getMessage(), error);
  }

  private long itemCount(Object value) {
    if (value instanceof List<?> list) {
      return list.size();
    }
    if (value instanceof Map<?, ?> map) {
      Object items = map.get("items");
      return items instanceof List<?> list ? list.size() : 0;
    }
    return 0;
  }

  public static class MultiTrackRequest {
    public List<String> shipIds;
    public String start;
    public String end;
    public Integer zoom;
    public String samplingMode;
    public Integer bucketSeconds;
    public RequestBBox bbox;
  }

  public static class RequestBBox {
    public double west;
    public double south;
    public double east;
    public double north;

    BBox toBBox() {
      BBox value = new BBox(west, south, east, north);
      value.validate();
      return value;
    }
  }
}

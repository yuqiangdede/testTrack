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
      @RequestParam(required = false) String timePoint, @RequestParam(required = false) String minutes) {
    return trace("/api/stats/realtime-summary", () -> {
      TimeWindow timeWindow = realtimeWindow(start, end, timePoint, minutes);
      Map<String, Object> database = realtimeService.databaseStats();
      Map<String, Object> window = timeWindow.start().isBlank() || timeWindow.end().isBlank()
          ? Map.of("trackPoints", 0L, "ships", 0L)
          : repository.windowStats(timeWindow.start(), timeWindow.end());
      return Map.of(
          "databaseTrackPoints", database.get("trackPoints"),
          "databaseShips", database.get("ships"),
          "windowTrackPoints", window.get("trackPoints"),
          "windowShips", window.get("ships"));
    });
  }

  @GetMapping("/api/analysis/density")
  public Map<String, Object> density(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north, @RequestParam(required = false) String zoom) {
    return trace("/api/analysis/density", () -> {
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      BBox bbox = bbox(west, south, east, north);
      return Map.of("items", repository.density(time.start(), time.end(), bbox, realtimeService.validateZoom(zoom)));
    });
  }

  @GetMapping("/api/tracks/single")
  public Map<String, Object> single(@RequestParam String shipId, @RequestParam String start, @RequestParam String end,
      @RequestParam(required = false) String zoom) {
    return trace("/api/tracks/single", () -> {
      if (shipId == null || shipId.isBlank()) {
        throw new IllegalArgumentException("shipId parameter is required");
      }
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      return Map.of("items", repository.trackRows(List.of(shipId), time.start(), time.end(), realtimeService.validateZoom(zoom), null, "single"));
    });
  }

  @GetMapping("/api/tracks/candidates")
  public Map<String, Object> candidates(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north, @RequestParam(required = false) String limit) {
    return trace("/api/tracks/candidates", () -> {
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      int limitValue = limit == null || limit.isBlank() ? config.query.maxMultiShips : Integer.parseInt(limit);
      return Map.of("items", repository.candidates(time.start(), time.end(), bbox(west, south, east, north), limitValue));
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
      BBox bbox = body.bbox == null ? null : body.bbox.toBBox();
      return Map.of("items", repository.trackRows(shipIds, time.start(), time.end(), zoom, bbox, "multi"));
    });
  }

  @GetMapping("/api/tracks/global-segment")
  public Map<String, Object> globalSegment(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north, @RequestParam(required = false) String zoom) {
    return trace("/api/tracks/global-segment", () -> {
      TimeWindow time = realtimeService.validateTimeWindow(start, end);
      return Map.of("items", repository.globalSegment(time.start(), time.end(), bbox(west, south, east, north), realtimeService.validateZoom(zoom)));
    });
  }

  private BBox bbox(String west, String south, String east, String north) {
    BBox bbox = new BBox(Double.parseDouble(west), Double.parseDouble(south), Double.parseDouble(east), Double.parseDouble(north));
    bbox.validate();
    return bbox;
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
      if (map.containsKey("databaseTrackPoints") || map.containsKey("windowTrackPoints")) {
        log.info("api request ok endpoint={} elapsedMs={} dbElapsedMs={} dbCalls={} databaseTrackPoints={} databaseShips={} windowTrackPoints={} windowShips={}",
            endpoint, elapsedMs, dbElapsedMs, metrics.dbCalls(),
            map.get("databaseTrackPoints"), map.get("databaseShips"), map.get("windowTrackPoints"), map.get("windowShips"));
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

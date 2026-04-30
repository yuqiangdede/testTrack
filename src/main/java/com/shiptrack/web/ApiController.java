package com.shiptrack.web;

import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.realtime.RealtimeService;
import com.shiptrack.track.TrackRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final ShipConfigService configService;
  private final ShipTrackConfig config;
  private final TrackRepository repository;
  private final RealtimeService realtimeService;

  public ApiController(ShipConfigService configService, TrackRepository repository, RealtimeService realtimeService) {
    this.configService = configService;
    this.config = configService.config();
    this.repository = repository;
    this.realtimeService = realtimeService;
  }

  @GetMapping("/api/config/map")
  public Map<String, Object> mapConfig() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("coordinateSystem", config.map.coordinateSystem);
    body.put("defaultCenter", config.map.defaultCenter);
    body.put("defaultZoom", config.map.defaultZoom);
    body.put("maxMultiShips", config.query.maxMultiShips);
    body.put("amapKey", configService.envOrDefault("VITE_AMAP_KEY", ""));
    body.put("amapSecurityJsCode", configService.envOrDefault("VITE_AMAP_SECURITY_JS_CODE", ""));
    return body;
  }

  @GetMapping("/api/realtime/latest")
  public Map<String, Object> latest(@RequestParam(required = false) String start, @RequestParam(required = false) String end) {
    TimeWindow timeWindow = realtimeService.realtimeWindowFromParams(start, end);
    return realtimeService.latestResponse(timeWindow);
  }

  @GetMapping("/api/analysis/density")
  public Map<String, Object> density(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north, @RequestParam(required = false) String zoom) {
    TimeWindow time = realtimeService.validateTimeWindow(start, end);
    BBox bbox = bbox(west, south, east, north);
    return Map.of("items", repository.density(time.start(), time.end(), bbox, realtimeService.validateZoom(zoom)));
  }

  @GetMapping("/api/tracks/single")
  public Map<String, Object> single(@RequestParam String shipId, @RequestParam String start, @RequestParam String end,
      @RequestParam(required = false) String zoom) {
    if (shipId == null || shipId.isBlank()) {
      throw new IllegalArgumentException("shipId parameter is required");
    }
    TimeWindow time = realtimeService.validateTimeWindow(start, end);
    return Map.of("items", repository.trackRows(List.of(shipId), time.start(), time.end(), realtimeService.validateZoom(zoom), null, "single"));
  }

  @GetMapping("/api/tracks/candidates")
  public Map<String, Object> candidates(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north, @RequestParam(required = false) String limit) {
    TimeWindow time = realtimeService.validateTimeWindow(start, end);
    int limitValue = limit == null || limit.isBlank() ? config.query.maxMultiShips : Integer.parseInt(limit);
    return Map.of("items", repository.candidates(time.start(), time.end(), bbox(west, south, east, north), limitValue));
  }

  @PostMapping("/api/tracks/multi")
  public Map<String, Object> multi(@RequestBody MultiTrackRequest body) {
    List<String> shipIds = body.shipIds == null ? List.of() : body.shipIds.stream().limit(config.query.maxMultiShips).toList();
    if (shipIds.isEmpty()) {
      throw new IllegalArgumentException("shipIds parameter is required");
    }
    TimeWindow time = realtimeService.validateTimeWindow(body.start, body.end);
    int zoom = body.zoom == null ? config.map.defaultZoom : body.zoom;
    BBox bbox = body.bbox == null ? null : body.bbox.toBBox();
    return Map.of("items", repository.trackRows(shipIds, time.start(), time.end(), zoom, bbox, "multi"));
  }

  @GetMapping("/api/tracks/global-segment")
  public Map<String, Object> globalSegment(@RequestParam String start, @RequestParam String end, @RequestParam String west,
      @RequestParam String south, @RequestParam String east, @RequestParam String north, @RequestParam(required = false) String zoom) {
    TimeWindow time = realtimeService.validateTimeWindow(start, end);
    return Map.of("items", repository.globalSegment(time.start(), time.end(), bbox(west, south, east, north), realtimeService.validateZoom(zoom)));
  }

  private BBox bbox(String west, String south, String east, String north) {
    BBox bbox = new BBox(Double.parseDouble(west), Double.parseDouble(south), Double.parseDouble(east), Double.parseDouble(north));
    bbox.validate();
    return bbox;
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

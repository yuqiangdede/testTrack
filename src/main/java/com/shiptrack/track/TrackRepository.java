package com.shiptrack.track;

import static com.shiptrack.clickhouse.SqlUtil.ident;
import static com.shiptrack.clickhouse.SqlUtil.sqlDateParam;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class TrackRepository {
  private static final String SAMPLING_MODE_RAW = "raw";
  private static final String SAMPLING_MODE_AUTO = "auto";
  private static final String SAMPLING_MODE_MANUAL = "manual";
  private static final int DEFAULT_MANUAL_BUCKET_SECONDS = 60;

  private final ClickHouseHttpClient clickHouse;
  private final ShipTrackConfig config;

  private record SamplingPlan(boolean raw, int bucketSeconds) {}

  public TrackRepository(ClickHouseHttpClient clickHouse, ShipConfigService configService) {
    this.clickHouse = clickHouse;
    this.config = configService.config();
  }

  public List<Map<String, Object>> latest(String afterShipId, int limit, BBox bbox, boolean capToPage, String start, String end) {
    if (start == null || start.isBlank() || end == null || end.isBlank()) {
      throw new IllegalArgumentException("realtime latest time window is required");
    }
    ShipTrackConfig.Columns c = config.columns;
    String cursorFilter = afterShipId == null || afterShipId.isBlank() ? "" : "AND " + ident(c.shipId) + " > {afterShipId: String}";
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    String query = """
        SELECT
          %s AS shipId,
          %s AS shipName,
          argMax(%s, %s) AS lng,
          argMax(%s, %s) AS lat,
          argMax(%s, %s) AS speed,
          argMax(%s, %s) AS heading,
          %s,
          argMax(%s, %s) AS shipType,
          toString(max(%s)) AS time
        FROM %s
        WHERE %s >= %s
          AND %s < %s
          %s
          %s
        GROUP BY %s
        ORDER BY %s ASC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(c.shipId),
        ident(c.shipId),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        isAisSelectExpr(c),
        ident(c.type), ident(c.eventTime),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        cursorFilter,
        bboxFilter,
        ident(c.shipId),
        ident(c.shipId));
    int limitValue = capToPage
        ? Math.min(limitOr(limit, config.query.latestPageSize), config.query.latestPageSize)
        : Math.min(limitOr(limit, config.query.realtimeCacheMaxShips), config.query.realtimeCacheMaxShips);
    Map<String, Object> params = params("start", start, "end", end, "afterShipId", afterShipId == null ? "" : afterShipId, "limit", limitValue);
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public Map<String, Object> databaseStats() {
    ShipTrackConfig.Columns c = config.columns;
    Map<String, Object> row = clickHouse.queryOne("""
        SELECT
          count() AS trackPoints,
          uniqCombined64(%s) AS ships
        FROM %s
        """.formatted(ident(c.shipId), ident(config.tables.track)));
    return Map.of(
        "trackPoints", toLong(row.get("trackPoints")),
        "ships", toLong(row.get("ships")));
  }

  public Map<String, Object> windowStats(String start, String end) {
    return windowStats(start, end, null);
  }

  public Map<String, Object> windowStats(String start, String end, BBox bbox) {
    ShipTrackConfig.Columns c = config.columns;
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    Map<String, Object> params = params("start", start, "end", end);
    putBbox(params, bbox);
    List<Map<String, Object>> rows = clickHouse.query("""
        SELECT
          count() AS trackPoints,
          uniqCombined64(%s) AS ships
        FROM %s
        WHERE %s >= %s
          AND %s < %s
          %s
        """.formatted(
        ident(c.shipId),
        ident(config.tables.track),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter), params);
    Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
    return Map.of(
        "trackPoints", toLong(row.get("trackPoints")),
        "ships", toLong(row.get("ships")));
  }

  public long singleTrackPointCount(String shipId, String start, String end) {
    if (shipId == null || shipId.isBlank()) {
      return 0;
    }
    List<String> shipIdVariants = shipIdVariants(shipId);
    ShipTrackConfig.Columns c = config.columns;
    List<Map<String, Object>> rows = clickHouse.query("""
        SELECT count() AS trackPoints
        FROM %s
        PREWHERE %s IN {shipIdVariants: Array(String)}
        WHERE %s >= %s
          AND %s < %s
        """.formatted(
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end")),
        params("shipIdVariants", shipIdVariants, "start", start, "end", end));
    Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
    return toLong(row.get("trackPoints"));
  }

  public long multiTrackPointCount(List<String> shipIds, String start, String end) {
    if (shipIds == null || shipIds.isEmpty()) {
      return 0;
    }
    List<String> shipIdVariants = shipIdVariants(shipIds);
    ShipTrackConfig.Columns c = config.columns;
    List<Map<String, Object>> rows = clickHouse.query("""
        SELECT count() AS trackPoints
        FROM %s
        WHERE %s IN {shipIdVariants: Array(String)}
          AND %s >= %s
          AND %s < %s
        """.formatted(
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end")),
        params("shipIdVariants", shipIdVariants, "start", start, "end", end));
    Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
    return toLong(row.get("trackPoints"));
  }

  public long densityCellCount(String start, String end, BBox bbox, int zoom) {
    ShipTrackConfig.Columns c = config.columns;
    double grid = densityGridSizeDegrees(zoom);
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    Map<String, Object> params = params("start", start, "end", end, "grid", grid);
    putBbox(params, bbox);
    List<Map<String, Object>> rows = clickHouse.query("""
        SELECT count() AS cells
        FROM
        (
          SELECT
            floor(%s / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lng,
            floor(%s / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lat
          FROM %s
          WHERE bucket_size = 300
            AND bucket_start >= %s - toIntervalSecond(300)
            AND bucket_start < %s
            AND %s >= %s
            AND %s < %s
            %s
          GROUP BY lng, lat
        ) AS density_cells
        """.formatted(
        ident(c.longitude),
        ident(c.latitude),
        ident(config.tables.simplifiedTrack),
        sqlDateParam("start"),
        sqlDateParam("end"),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter), params);
    Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
    return toLong(row.get("cells"));
  }

  public String watermark() {
    ShipTrackConfig.Columns c = config.columns;
    Map<String, Object> row = clickHouse.queryOne("SELECT toString(max(%s)) AS time FROM %s".formatted(ident(c.eventTime), ident(config.tables.track)));
    return String.valueOf(row.getOrDefault("time", ""));
  }

  public List<Map<String, Object>> deltas(String since, String end) {
    ShipTrackConfig.Columns c = config.columns;
    String endFilter = end == null || end.isBlank() ? "" : "AND " + ident(c.eventTime) + " < " + sqlDateParam("end");
    String query = """
        SELECT
          %s AS shipId,
          %s AS shipName,
          argMax(%s, %s) AS lng,
          argMax(%s, %s) AS lat,
          argMax(%s, %s) AS speed,
          argMax(%s, %s) AS heading,
          %s,
          argMax(%s, %s) AS shipType,
          toString(max(%s)) AS time
        FROM %s
        WHERE %s > %s
          %s
        GROUP BY %s
        ORDER BY time DESC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(c.shipId),
        ident(c.shipId),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        isAisSelectExpr(c),
        ident(c.type), ident(c.eventTime),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.eventTime), sqlDateParam("since"),
        endFilter,
        ident(c.shipId));
    return clickHouse.query(query, params("since", since, "end", end == null ? "" : end, "limit", realtimeDeltaLimit()));
  }

  public List<Map<String, Object>> density(String start, String end, BBox bbox, int zoom) {
    return density(start, end, bbox, zoom, null);
  }

  public List<Map<String, Object>> density(String start, String end, BBox bbox, int zoom, Integer stepMinutes) {
    ShipTrackConfig.Columns c = config.columns;
    double grid = densityGridSizeDegrees(zoom);
    Map<String, Object> params = params("start", start, "end", end, "grid", grid, "limit", config.query.maxDensityCells);
    String query = """
        SELECT
          floor(%s / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lng,
          floor(%s / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lat,
          count() AS count,
          uniqCombined64(%s) AS ships
        FROM %s
        WHERE bucket_size = 300
          AND bucket_start >= %s - toIntervalSecond(300)
          AND bucket_start < %s
          AND %s >= %s
          AND %s < %s
          AND %s BETWEEN {west: Float64} AND {east: Float64}
          AND %s BETWEEN {south: Float64} AND {north: Float64}
        GROUP BY lng, lat
        ORDER BY count DESC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(c.longitude),
        ident(c.latitude),
        ident(c.shipId),
        ident(config.tables.simplifiedTrack),
        sqlDateParam("start"),
        sqlDateParam("end"),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        ident(c.longitude),
        ident(c.latitude));
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public long totalTrackPoints() {
    Map<String, Object> row = clickHouse.queryOne("SELECT count() AS points FROM %s".formatted(ident(config.tables.track)));
    return toLong(row.get("points"));
  }

  public List<Map<String, Object>> candidates(String start, String end, BBox bbox, int page, int pageSize) {
    ShipTrackConfig.BucketIndexColumns ic = config.bucketIndexColumns;
    int limit = Math.max(1, Math.min(pageSize, config.query.maxCandidateBatchSize));
    int offset = Math.max(0, (Math.max(1, page) - 1) * limit);
    String query = """
        SELECT
          %s AS shipId,
          %s AS shipName,
          toString(min(%s)) AS firstTime,
          toString(max(%s)) AS lastTime,
          countMerge(point_count) AS points
        FROM %s
        WHERE %s >= %s
          AND %s < %s
          AND grid_05_lng BETWEEN {westGrid05Lng: Int32} AND {eastGrid05Lng: Int32}
          AND grid_05_lat BETWEEN {southGrid05Lat: Int32} AND {northGrid05Lat: Int32}
        GROUP BY %s
        HAVING maxMerge(%s) >= {west: Float64}
          AND minMerge(%s) <= {east: Float64}
          AND maxMerge(%s) >= {south: Float64}
          AND minMerge(%s) <= {north: Float64}
        ORDER BY points DESC, shipId ASC
        LIMIT {limit: UInt32} OFFSET {offset: UInt32}
        """.formatted(
        trimExpr(ident(ic.shipId)),
        trimExpr(ident(ic.shipId)),
        ident(ic.bucketStart),
        ident(ic.bucketStart),
        ident(config.tables.bucketIndex),
        ident(ic.bucketStart), sqlDateParam("start"),
        ident(ic.bucketStart), sqlDateParam("end"),
        trimExpr(ident(ic.shipId)),
        ident(ic.maxLng),
        ident(ic.minLng),
        ident(ic.maxLat),
        ident(ic.minLat));
    Map<String, Object> params = params("start", start, "end", end, "limit", limit, "offset", offset);
    putBbox(params, bbox);
    putFineGrid(params, bbox);
    return clickHouse.query(query, params);
  }

  public Map<String, Object> multiStats(String start, String end, BBox bbox) {
    Map<String, Object> window = windowStats(start, end);
    Map<String, Object> windowBBox = bbox == null ? Map.of("trackPoints", 0L, "ships", 0L) : windowStats(start, end, bbox);
    return Map.of(
        "windowTrackPoints", window.get("trackPoints"),
        "windowShips", window.get("ships"),
        "bboxTrackPoints", windowBBox.get("trackPoints"),
        "bboxShips", windowBBox.get("ships"));
  }

  private SamplingPlan resolveSamplingPlan(String samplingMode, Integer bucketSeconds, int zoom, String start, String end,
      int maxPoints, String mode) {
    String normalizedMode = normalizeSamplingMode(samplingMode);
    if (SAMPLING_MODE_RAW.equals(normalizedMode) && "single".equals(mode)) {
      return new SamplingPlan(true, 0);
    }
    if (SAMPLING_MODE_MANUAL.equals(normalizedMode)) {
      return new SamplingPlan(false, normalizeBucketSeconds(bucketSeconds));
    }
    return new SamplingPlan(false, bucketSizeForZoom(zoom));
  }

  private String normalizeSamplingMode(String samplingMode) {
    if (samplingMode == null) {
      return SAMPLING_MODE_AUTO;
    }
    String normalized = samplingMode.trim().toLowerCase();
    if (SAMPLING_MODE_RAW.equals(normalized) || SAMPLING_MODE_AUTO.equals(normalized) || SAMPLING_MODE_MANUAL.equals(normalized)) {
      return normalized;
    }
    return SAMPLING_MODE_AUTO;
  }

  private int normalizeBucketSeconds(Integer bucketSeconds) {
    int requested = Math.max(1, bucketSeconds == null ? DEFAULT_MANUAL_BUCKET_SECONDS : bucketSeconds);
    int[] fixedBuckets = {60, 300, 1800};
    int closest = fixedBuckets[0];
    for (int bucket : fixedBuckets) {
      if (Math.abs(bucket - requested) < Math.abs(closest - requested)) {
        closest = bucket;
      }
    }
    return closest;
  }

  public int bucketSizeForZoom(int zoom) {
    if (zoom <= 7) return 1800;
    if (zoom <= 10) return 300;
    return 60;
  }

  public List<Map<String, Object>> trackRows(List<String> shipIds, String start, String end, int zoom, BBox bbox,
      String mode, String samplingMode, Integer bucketSeconds) {
    if (shipIds == null || shipIds.isEmpty()) {
      return List.of();
    }
    List<String> normalizedShipIds = normalizeShipIds(shipIds);
    if (normalizedShipIds.isEmpty()) {
      return List.of();
    }
    if ("single".equals(mode) && normalizedShipIds.size() == 1) {
      return singleTrackRows(normalizedShipIds.get(0), start, end, zoom, bbox, samplingMode, bucketSeconds);
    }
    ShipTrackConfig.Columns c = config.columns;
    SamplingPlan sampling = resolveSamplingPlan(samplingMode, bucketSeconds, zoom, start, end, config.query.maxTrackPointsPerShip, mode);
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    if (sampling.raw()) {
      String query = """
          SELECT
            %s AS shipId,
            %s AS shipName,
            %s AS lng,
            %s AS lat,
            %s AS speed,
            %s AS heading,
            %s AS isAis,
            %s AS shipType,
            toString(%s) AS time
          FROM %s
          WHERE %s IN {shipIdVariants: Array(String)}
            AND %s >= %s
            AND %s < %s
            %s
          ORDER BY time ASC, shipId ASC
          """.formatted(
          trimExpr(ident(c.shipId)),
          trimExpr(ident(c.shipId)),
          ident(c.longitude),
          ident(c.latitude),
          ident(c.speed),
          ident(c.heading),
          isAisExpr(ident(c.type)),
          ident(c.type),
          ident(c.eventTime),
          ident(config.tables.track),
          ident(c.shipId),
          ident(c.eventTime), sqlDateParam("start"),
          ident(c.eventTime), sqlDateParam("end"),
          bboxFilter);
      Map<String, Object> params = params("shipIdVariants", shipIdVariants(normalizedShipIds), "start", start, "end", end);
      putBbox(params, bbox);
      return clickHouse.query(query, params);
    }
    return thinTrackRows(normalizedShipIds, start, end, sampling.bucketSeconds(), bbox);
  }

  public List<Map<String, Object>> singleTrackRows(String shipId, String start, String end, int zoom, BBox bbox,
      String samplingMode, Integer bucketSeconds) {
    if (shipId == null || shipId.isBlank()) {
      return List.of();
    }
    String normalizedShipId = shipId.trim();
    ShipTrackConfig.Columns c = config.columns;
    SamplingPlan sampling = resolveSamplingPlan(samplingMode, bucketSeconds, zoom, start, end, config.query.maxSingleTrackPoints, "single");
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    if (sampling.raw()) {
      String query = """
          SELECT
            {shipId: String} AS shipId,
            {shipId: String} AS shipName,
            %s AS lng,
            %s AS lat,
            %s AS speed,
            %s AS heading,
            %s AS isAis,
            %s AS shipType,
            toString(%s) AS time
          FROM %s
           PREWHERE %s IN {shipIdVariants: Array(String)}
           WHERE %s >= %s
             AND %s < %s
             %s
           ORDER BY time ASC
           """.formatted(
          ident(c.longitude),
          ident(c.latitude),
          ident(c.speed),
          ident(c.heading),
          isAisExpr(ident(c.type)),
          ident(c.type),
          ident(c.eventTime),
          ident(config.tables.track),
          ident(c.shipId),
           ident(c.eventTime), sqlDateParam("start"),
           ident(c.eventTime), sqlDateParam("end"),
           bboxFilter);
      Map<String, Object> params = params("shipId", normalizedShipId, "shipIdVariants", shipIdVariants(normalizedShipId), "start", start, "end", end);
      putBbox(params, bbox);
      return clickHouse.query(query, params);
    }
    return thinSingleTrackRows(normalizedShipId, start, end, sampling.bucketSeconds(), bbox);
  }

  public List<Map<String, Object>> globalSegment(String start, String end, int zoom, String samplingMode, Integer bucketSeconds) {
    ShipTrackConfig.Columns c = config.columns;
    SamplingPlan sampling = resolveSamplingPlan(samplingMode, bucketSeconds, zoom, start, end, config.query.maxGlobalSegmentPoints, "global");
    if (sampling.raw()) {
      String query = """
          SELECT
            %s AS shipId,
            %s AS shipName,
            %s AS lng,
            %s AS lat,
            %s AS speed,
            %s AS heading,
            %s AS isAis,
            %s AS shipType,
            toString(%s) AS time
          FROM %s
          WHERE %s >= %s
            AND %s < %s
          ORDER BY time ASC, shipId ASC
          """.formatted(
          ident(c.shipId),
          ident(c.shipId),
          ident(c.longitude),
          ident(c.latitude),
          ident(c.speed),
          ident(c.heading),
          isAisExpr(ident(c.type)),
          ident(c.type),
          ident(c.eventTime),
          ident(config.tables.track),
          ident(c.eventTime), sqlDateParam("start"),
          ident(c.eventTime), sqlDateParam("end"));
      return clickHouse.query(query, params("start", start, "end", end));
    }
    return globalPositionFrames(start, end);
  }

  private List<Map<String, Object>> thinTrackRows(List<String> shipIds, String start, String end, int bucketSeconds, BBox bbox) {
    ShipTrackConfig.Columns c = config.columns;
    List<String> shipIdVariants = shipIdVariants(shipIds);
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    String query = """
        SELECT
          %s AS shipId,
          %s AS shipName,
          %s AS lng,
          %s AS lat,
          %s AS speed,
          %s AS heading,
          %s AS isAis,
          %s AS shipType,
          toString(%s) AS time
        FROM %s
        WHERE bucket_size = {bucketSeconds: UInt32}
          AND %s IN {shipIdVariants: Array(String)}
          AND bucket_start >= %s - toIntervalSecond({bucketSeconds: UInt32})
          AND bucket_start < %s
          AND %s >= %s
          AND %s < %s
          %s
        ORDER BY time ASC, shipId ASC
        """.formatted(
        trimExpr(ident(c.shipId)),
        trimExpr(ident(c.shipId)),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        isAisExpr(ident(c.type)),
        ident(c.type),
        ident(c.eventTime),
        ident(config.tables.simplifiedTrack),
        ident(c.shipId),
        sqlDateParam("start"),
        sqlDateParam("end"),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter);
    Map<String, Object> params = params("shipIdVariants", shipIdVariants, "start", start, "end", end, "bucketSeconds", bucketSeconds);
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  private List<Map<String, Object>> thinSingleTrackRows(String shipId, String start, String end, int bucketSeconds, BBox bbox) {
    ShipTrackConfig.Columns c = config.columns;
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    String query = """
        SELECT
          {shipId: String} AS shipId,
          {shipId: String} AS shipName,
          %s AS lng,
          %s AS lat,
          %s AS speed,
          %s AS heading,
          %s AS isAis,
          %s AS shipType,
          toString(%s) AS time
        FROM %s
        PREWHERE %s IN {shipIdVariants: Array(String)}
        WHERE bucket_size = {bucketSeconds: UInt32}
          AND bucket_start >= %s - toIntervalSecond({bucketSeconds: UInt32})
          AND bucket_start < %s
          AND %s >= %s
          AND %s < %s
          %s
        ORDER BY time ASC
        """.formatted(
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        isAisExpr(ident(c.type)),
        ident(c.type),
        ident(c.eventTime),
        ident(config.tables.simplifiedTrack),
        ident(c.shipId),
        sqlDateParam("start"),
        sqlDateParam("end"),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter);
    String normalizedShipId = shipId.trim();
    Map<String, Object> params = params("shipId", normalizedShipId, "shipIdVariants", shipIdVariants(normalizedShipId), "start", start, "end", end, "bucketSeconds", bucketSeconds);
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  private List<Map<String, Object>> globalPositionFrames(String start, String end) {
    ShipTrackConfig.Columns c = config.columns;
    String query = """
        SELECT
          %s AS shipId,
          %s AS shipName,
          argMax(%s, %s) AS lng,
          argMax(%s, %s) AS lat,
          argMax(%s, %s) AS speed,
          argMax(%s, %s) AS heading,
          argMax(%s, %s) AS isAis,
          argMax(%s, %s) AS shipType,
          toString(max(%s)) AS time,
          toString(bucket_start) AS bucketStart
        FROM %s
        WHERE bucket_size = 1800
          AND bucket_start >= %s - toIntervalSecond(1800)
          AND bucket_start < %s
          AND %s >= %s
          AND %s < %s
        GROUP BY %s, bucket_start
        ORDER BY time ASC, shipId ASC
        """.formatted(
        ident(c.shipId),
        ident(c.shipId),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        isAisExpr(ident(c.type)), ident(c.eventTime),
        ident(c.type), ident(c.eventTime),
        ident(c.eventTime),
        ident(config.tables.simplifiedTrack),
        sqlDateParam("start"),
        sqlDateParam("end"),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        ident(c.shipId));
    return clickHouse.query(query, params("start", start, "end", end));
  }

  public double densityGridSizeDegrees(int zoom) {
    if (zoom >= 11) return 0.05;
    if (zoom >= 8) return 0.1;
    return 0.5;
  }

  public int calculateBucketSizeSeconds(int zoom, String start, String end, int maxPoints, String mode) {
    long spanSeconds = Math.max(1, parseTime(end).getEpochSecond() - parseTime(start).getEpochSecond());
    long base = (long) Math.ceil((double) spanSeconds / Math.max(1, maxPoints));
    int zoomPenalty = zoom >= 13 ? 1 : zoom >= 10 ? 2 : zoom >= 8 ? 5 : 10;
    int modeFactor = "global".equals(mode) ? 4 : "multi".equals(mode) ? 2 : 1;
    long raw = base * zoomPenalty * modeFactor;
    int[] buckets = {1, 5, 10, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400};
    for (int bucket : buckets) {
      if (bucket >= raw) return bucket;
    }
    return 14400;
  }

  private Instant parseTime(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(8));
    }
  }

  private String isAisSelectExpr(ShipTrackConfig.Columns c) {
    return "argMax(%s, %s) AS isAis".formatted(isAisExpr(ident(c.type)), ident(c.eventTime));
  }

  private String isAisExpr(String typeExpr) {
    return "if(%s IN (1, 4, 5, 7), toUInt8(1), toUInt8(0))".formatted(typeExpr);
  }

  private String trimExpr(String expression) {
    return "trim(%s)".formatted(expression);
  }

  private List<String> normalizeShipIds(List<String> shipIds) {
    return shipIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  private List<String> shipIdVariants(List<String> shipIds) {
    Set<String> variants = new LinkedHashSet<>();
    for (String shipId : shipIds) {
      variants.addAll(shipIdVariants(shipId));
    }
    return new ArrayList<>(variants);
  }

  private List<String> shipIdVariants(String shipId) {
    String normalized = shipId == null ? "" : shipId.trim();
    if (normalized.isBlank()) {
      return List.of();
    }
    return List.of(normalized, " " + normalized, normalized + " ", " " + normalized + " ");
  }

  private int realtimeDeltaLimit() {
    return Math.min(config.query.maxRealtimeDeltaShips, config.query.realtimeCacheMaxShips);
  }

  private int limitOr(int value, int defaultValue) {
    return value > 0 ? value : defaultValue;
  }

  private Map<String, Object> params(Object... values) {
    Map<String, Object> params = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      params.put(String.valueOf(values[i]), values[i + 1]);
    }
    return params;
  }

  private void putBbox(Map<String, Object> params, BBox bbox) {
    if (bbox == null) {
      return;
    }
    params.put("west", bbox.west());
    params.put("south", bbox.south());
    params.put("east", bbox.east());
    params.put("north", bbox.north());
  }

  private void putFineGrid(Map<String, Object> params, BBox bbox) {
    if (bbox == null) {
      return;
    }
    params.put("westGrid05Lng", ShipGridUtils.lngGrid(bbox.west(), 0.05));
    params.put("eastGrid05Lng", ShipGridUtils.lngGrid(bbox.east(), 0.05));
    params.put("southGrid05Lat", ShipGridUtils.latGrid(bbox.south(), 0.05));
    params.put("northGrid05Lat", ShipGridUtils.latGrid(bbox.north(), 0.05));
  }

  private long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? 0 : Long.parseLong(text);
  }
}

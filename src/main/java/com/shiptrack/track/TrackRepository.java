package com.shiptrack.track;

import static com.shiptrack.clickhouse.SqlUtil.ident;
import static com.shiptrack.clickhouse.SqlUtil.sqlDateParam;

import com.shiptrack.clickhouse.ClickHouseException;
import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
          argMax(%s, %s) AS shipName,
          argMax(%s, %s) AS lng,
          argMax(%s, %s) AS lat,
          argMax(%s, %s) AS speed,
          argMax(%s, %s) AS heading,
          %s,
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
        ident(c.shipName), ident(c.eventTime),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        isAisSelectExpr(c),
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
    ShipTrackConfig.Columns c = config.columns;
    List<Map<String, Object>> rows = clickHouse.query("""
        SELECT count() AS trackPoints
        FROM %s
        PREWHERE %s = {shipId: String}
        WHERE %s >= %s
          AND %s < %s
        """.formatted(
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end")),
        params("shipId", shipId, "start", start, "end", end));
    Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
    return toLong(row.get("trackPoints"));
  }

  public long multiTrackPointCount(List<String> shipIds, String start, String end) {
    if (shipIds == null || shipIds.isEmpty()) {
      return 0;
    }
    ShipTrackConfig.Columns c = config.columns;
    List<Map<String, Object>> rows = clickHouse.query("""
        SELECT count() AS trackPoints
        FROM %s
        WHERE %s IN {shipIds: Array(String)}
          AND %s >= %s
          AND %s < %s
        """.formatted(
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end")),
        params("shipIds", shipIds, "start", start, "end", end));
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
          WHERE %s >= %s
            AND %s < %s
            %s
          GROUP BY lng, lat
        ) AS density_cells
        """.formatted(
        ident(c.longitude),
        ident(c.latitude),
        ident(config.tables.track),
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
          argMax(%s, %s) AS shipName,
          argMax(%s, %s) AS lng,
          argMax(%s, %s) AS lat,
          argMax(%s, %s) AS speed,
          argMax(%s, %s) AS heading,
          %s,
          toString(max(%s)) AS time
        FROM %s
        WHERE %s > %s
          %s
        GROUP BY %s
        ORDER BY time DESC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(c.shipId),
        ident(c.shipName), ident(c.eventTime),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        isAisSelectExpr(c),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.eventTime), sqlDateParam("since"),
        endFilter,
        ident(c.shipId));
    return clickHouse.query(query, params("since", since, "end", end == null ? "" : end, "limit", realtimeDeltaLimit()));
  }

  public List<Map<String, Object>> density(String start, String end, BBox bbox, int zoom) {
    ShipTrackConfig.Columns c = config.columns;
    double grid = densityGridSizeDegrees(zoom);
    String query = """
        SELECT
          floor(%s / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lng,
          floor(%s / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lat,
          count() AS count,
          uniqCombined64(%s) AS ships
        FROM %s
        WHERE %s >= %s
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
        ident(config.tables.track),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        ident(c.longitude),
        ident(c.latitude));
    Map<String, Object> params = params("start", start, "end", end, "grid", grid, "limit", config.query.maxDensityCells);
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public long totalTrackPoints() {
    Map<String, Object> row = clickHouse.queryOne("SELECT count() AS points FROM %s".formatted(ident(config.tables.track)));
    return toLong(row.get("points"));
  }

  public List<Map<String, Object>> candidates(String start, String end, BBox bbox, int page, int pageSize, List<String> shipTypes) {
    ShipTrackConfig.BucketIndexColumns ic = config.bucketIndexColumns;
    int limit = Math.max(1, Math.min(pageSize, config.query.maxCandidateBatchSize));
    int offset = Math.max(0, (Math.max(1, page) - 1) * limit);
    List<String> normalizedTypes = normalizeShipTypes(shipTypes);
    String typeFilter = normalizedTypes.size() == 1 ? "WHERE shipType = '" + normalizedTypes.get(0) + "'" : "";
    String query = """
        WITH
          base AS (
            SELECT
              %s AS shipId,
              argMax(%s, %s) AS shipName,
              argMax(%s, %s) AS isAis,
              toString(min(%s)) AS firstTime,
              toString(max(%s)) AS lastTime,
              count() AS points
            FROM %s
            WHERE %s >= %s
              AND %s < %s
              AND %s >= {west: Float64}
              AND %s <= {east: Float64}
              AND %s >= {south: Float64}
              AND %s <= {north: Float64}
            GROUP BY %s
          ),
          typed AS (
            SELECT
              shipId,
              ifNull(shipName, '') AS shipName,
              firstTime,
              lastTime,
              points,
              ifNull(isAis, 0) AS isAis,
              if(ifNull(isAis, 0) = 1, 'ais', 'radar') AS shipType
            FROM base
          )
        SELECT
          shipId,
          shipName,
          firstTime,
          lastTime,
          points,
          isAis,
          shipType
        FROM typed
        %s
        ORDER BY if(shipType = 'ais', 0, 1) ASC, points DESC, shipId ASC
        LIMIT {limit: UInt32} OFFSET {offset: UInt32}
        """.formatted(
        ident(ic.shipId),
        ident(ic.shipName), ident(ic.bucketStart),
        ident(ic.isAis), ident(ic.bucketStart),
        ident(ic.bucketStart),
        ident(ic.bucketStart),
        ident(config.tables.bucketIndex),
        ident(ic.bucketStart), sqlDateParam("start"),
        ident(ic.bucketStart), sqlDateParam("end"),
        ident(ic.maxLng),
        ident(ic.minLng),
        ident(ic.maxLat),
        ident(ic.minLat),
        ident(ic.shipId),
        typeFilter);
    Map<String, Object> params = params("start", start, "end", end, "limit", limit, "offset", offset);
    putBbox(params, bbox);
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

  private List<String> normalizeShipTypes(List<String> shipTypes) {
    if (shipTypes == null || shipTypes.isEmpty()) {
      return List.of("ais");
    }
    List<String> normalized = new java.util.ArrayList<>();
    for (String shipType : shipTypes) {
      if (shipType == null) {
        continue;
      }
      String value = shipType.trim().toLowerCase();
      if ("ais".equals(value) || "radar".equals(value)) {
        if (!normalized.contains(value)) {
          normalized.add(value);
        }
      }
    }
    if (normalized.isEmpty()) {
      normalized.add("ais");
    }
    return normalized;
  }

  private SamplingPlan resolveSamplingPlan(String samplingMode, Integer bucketSeconds, int zoom, String start, String end,
      int maxPoints, String mode) {
    String normalizedMode = normalizeSamplingMode(samplingMode);
    if (SAMPLING_MODE_RAW.equals(normalizedMode)) {
      return new SamplingPlan(true, 0);
    }
    if (SAMPLING_MODE_MANUAL.equals(normalizedMode)) {
      return new SamplingPlan(false, normalizeBucketSeconds(bucketSeconds));
    }
    return new SamplingPlan(false, calculateBucketSizeSeconds(zoom, start, end, maxPoints, mode));
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
    return Math.max(1, bucketSeconds == null ? DEFAULT_MANUAL_BUCKET_SECONDS : bucketSeconds);
  }

  public int simplifyLevelForZoom(int zoom) {
    if (zoom <= 7) return 3;
    if (zoom <= 10) return 2;
    if (zoom <= 13) return 1;
    return 0;
  }

  public List<Map<String, Object>> trackRows(List<String> shipIds, String start, String end, int zoom, BBox bbox,
      String mode, String samplingMode, Integer bucketSeconds) {
    if (shipIds == null || shipIds.isEmpty()) {
      return List.of();
    }
    if ("single".equals(mode) && shipIds.size() == 1) {
      return singleTrackRows(shipIds.get(0), start, end, zoom, bbox, samplingMode, bucketSeconds);
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
            toString(%s) AS time
          FROM %s
          WHERE %s IN {shipIds: Array(String)}
            AND %s >= %s
            AND %s < %s
            %s
          ORDER BY time ASC, shipId ASC
          """.formatted(
          ident(c.shipId),
          ident(c.shipName),
          ident(c.longitude),
          ident(c.latitude),
          ident(c.speed),
          ident(c.heading),
          ident(c.eventTime),
          ident(config.tables.track),
          ident(c.shipId),
          ident(c.eventTime), sqlDateParam("start"),
          ident(c.eventTime), sqlDateParam("end"),
          bboxFilter);
      Map<String, Object> params = params("shipIds", shipIds, "start", start, "end", end);
      putBbox(params, bbox);
      return clickHouse.query(query, params);
    }
    List<Map<String, Object>> simplified = safeSimplifiedTrackRows(shipIds, start, end, zoom, bbox);
    if (!simplified.isEmpty()) {
      return simplified;
    }
    String query = """
        SELECT
          %s AS shipId,
          argMin(%s, %s) AS shipName,
          argMin(%s, %s) AS lng,
          argMin(%s, %s) AS lat,
          argMin(%s, %s) AS speed,
          argMin(%s, %s) AS heading,
          intDiv(toUnixTimestamp(%s), {bucketSeconds: UInt32}) AS bucket,
          toString(min(%s)) AS time
        FROM %s
        WHERE %s IN {shipIds: Array(String)}
          AND %s >= %s
          AND %s < %s
          %s
        GROUP BY %s, bucket
        ORDER BY %s, time ASC
        """.formatted(
        ident(c.shipId),
        ident(c.shipName), ident(c.eventTime),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        ident(c.eventTime),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter,
        ident(c.shipId),
        ident(c.shipId));
    Map<String, Object> params = params("shipIds", shipIds, "start", start, "end", end, "bucketSeconds", sampling.bucketSeconds());
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public List<Map<String, Object>> singleTrackRows(String shipId, String start, String end, int zoom, BBox bbox,
      String samplingMode, Integer bucketSeconds) {
    if (shipId == null || shipId.isBlank()) {
      return List.of();
    }
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
            %s AS shipName,
            %s AS lng,
            %s AS lat,
            %s AS speed,
            %s AS heading,
            toString(%s) AS time
          FROM %s
           PREWHERE %s = {shipId: String}
           WHERE %s >= %s
             AND %s < %s
             %s
           ORDER BY time ASC
           """.formatted(
          ident(c.shipName),
          ident(c.longitude),
          ident(c.latitude),
          ident(c.speed),
          ident(c.heading),
          ident(c.eventTime),
          ident(config.tables.track),
          ident(c.shipId),
           ident(c.eventTime), sqlDateParam("start"),
           ident(c.eventTime), sqlDateParam("end"),
           bboxFilter);
      Map<String, Object> params = params("shipId", shipId, "start", start, "end", end);
      putBbox(params, bbox);
      return clickHouse.query(query, params);
    }
    List<Map<String, Object>> simplified = safeSimplifiedSingleTrackRows(shipId, start, end, zoom, bbox);
    if (!simplified.isEmpty()) {
      return simplified;
    }
    String query = """
        SELECT
          {shipId: String} AS shipId,
          argMin(%s, %s) AS shipName,
          argMin(%s, %s) AS lng,
          argMin(%s, %s) AS lat,
          argMin(%s, %s) AS speed,
          argMin(%s, %s) AS heading,
          intDiv(toUnixTimestamp(%s), {bucketSeconds: UInt32}) AS bucket,
          toString(min(%s)) AS time
        FROM %s
        PREWHERE %s = {shipId: String}
        WHERE %s >= %s
          AND %s < %s
          %s
        GROUP BY bucket
        ORDER BY time ASC
        """.formatted(
        ident(c.shipName), ident(c.eventTime),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        ident(c.eventTime),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter);
    Map<String, Object> params = params("shipId", shipId, "start", start, "end", end, "bucketSeconds", sampling.bucketSeconds());
    putBbox(params, bbox);
    return clickHouse.query(query, params);
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
            toString(%s) AS time
          FROM %s
          WHERE %s >= %s
            AND %s < %s
          ORDER BY time ASC, shipId ASC
          """.formatted(
          ident(c.shipId),
          ident(c.shipName),
          ident(c.longitude),
          ident(c.latitude),
          ident(c.speed),
          ident(c.heading),
          ident(c.eventTime),
          ident(config.tables.track),
          ident(c.eventTime), sqlDateParam("start"),
          ident(c.eventTime), sqlDateParam("end"));
      return clickHouse.query(query, params("start", start, "end", end));
    }
    List<Map<String, Object>> simplified = safeSimplifiedGlobalSegment(start, end, zoom);
    if (!simplified.isEmpty()) {
      return simplified;
    }
    String query = """
        SELECT
          %s AS shipId,
          argMin(%s, %s) AS shipName,
          argMin(%s, %s) AS lng,
          argMin(%s, %s) AS lat,
          argMin(%s, %s) AS speed,
          argMin(%s, %s) AS heading,
          intDiv(toUnixTimestamp(%s), {bucketSeconds: UInt32}) AS bucket,
          toString(min(%s)) AS time
        FROM %s
        WHERE %s >= %s
          AND %s < %s
        GROUP BY %s, bucket
        ORDER BY time ASC, shipId ASC
        """.formatted(
        ident(c.shipId),
        ident(c.shipName), ident(c.eventTime),
        ident(c.longitude), ident(c.eventTime),
        ident(c.latitude), ident(c.eventTime),
        ident(c.speed), ident(c.eventTime),
        ident(c.heading), ident(c.eventTime),
        ident(c.eventTime),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        ident(c.shipId));
    return clickHouse.query(query, params("start", start, "end", end, "bucketSeconds", sampling.bucketSeconds()));
  }

  private List<Map<String, Object>> safeSimplifiedTrackRows(List<String> shipIds, String start, String end, int zoom, BBox bbox) {
    try {
      return simplifiedTrackRows(shipIds, start, end, zoom, bbox);
    } catch (ClickHouseException ignored) {
      return List.of();
    }
  }

  private List<Map<String, Object>> safeSimplifiedSingleTrackRows(String shipId, String start, String end, int zoom, BBox bbox) {
    try {
      return simplifiedSingleTrackRows(shipId, start, end, zoom, bbox);
    } catch (ClickHouseException ignored) {
      return List.of();
    }
  }

  private List<Map<String, Object>> safeSimplifiedGlobalSegment(String start, String end, int zoom) {
    try {
      return simplifiedGlobalSegment(start, end, zoom);
    } catch (ClickHouseException ignored) {
      return List.of();
    }
  }

  private List<Map<String, Object>> simplifiedTrackRows(List<String> shipIds, String start, String end, int zoom, BBox bbox) {
    ShipTrackConfig.Columns c = config.columns;
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
          isAis AS isAis,
          toString(%s) AS time
        FROM %s
        WHERE simplify_level = {level: UInt8}
          AND %s IN {shipIds: Array(String)}
          AND %s >= %s
          AND %s < %s
          %s
        ORDER BY time ASC, shipId ASC
        """.formatted(
        ident(c.shipId),
        ident(c.shipName),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        ident(c.eventTime),
        ident(config.tables.simplifiedTrack),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter);
    Map<String, Object> params = params("shipIds", shipIds, "start", start, "end", end, "level", simplifyLevelForZoom(zoom));
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  private List<Map<String, Object>> simplifiedSingleTrackRows(String shipId, String start, String end, int zoom, BBox bbox) {
    ShipTrackConfig.Columns c = config.columns;
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
    String query = """
        SELECT
          {shipId: String} AS shipId,
          %s AS shipName,
          %s AS lng,
          %s AS lat,
          %s AS speed,
          %s AS heading,
          isAis AS isAis,
          toString(%s) AS time
        FROM %s
        PREWHERE %s = {shipId: String}
        WHERE simplify_level = {level: UInt8}
          AND %s >= %s
          AND %s < %s
          %s
        ORDER BY time ASC
        """.formatted(
        ident(c.shipName),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        ident(c.eventTime),
        ident(config.tables.simplifiedTrack),
        ident(c.shipId),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"),
        bboxFilter);
    Map<String, Object> params = params("shipId", shipId, "start", start, "end", end, "level", simplifyLevelForZoom(zoom));
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  private List<Map<String, Object>> simplifiedGlobalSegment(String start, String end, int zoom) {
    ShipTrackConfig.Columns c = config.columns;
    String query = """
        SELECT
          %s AS shipId,
          %s AS shipName,
          %s AS lng,
          %s AS lat,
          %s AS speed,
          %s AS heading,
          isAis AS isAis,
          toString(%s) AS time
        FROM %s
        WHERE simplify_level = {level: UInt8}
          AND %s >= %s
          AND %s < %s
        ORDER BY time ASC, shipId ASC
        """.formatted(
        ident(c.shipId),
        ident(c.shipName),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        ident(c.eventTime),
        ident(config.tables.simplifiedTrack),
        ident(c.eventTime), sqlDateParam("start"),
        ident(c.eventTime), sqlDateParam("end"));
    return clickHouse.query(query, params("start", start, "end", end, "level", simplifyLevelForZoom(zoom)));
  }

  public double densityGridSizeDegrees(int zoom) {
    if (zoom >= 13) return 0.0025;
    if (zoom >= 11) return 0.005;
    if (zoom >= 9) return 0.01;
    if (zoom >= 7) return 0.03;
    return 0.08;
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
    return "argMax(isAis, %s) AS isAis".formatted(ident(c.eventTime));
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

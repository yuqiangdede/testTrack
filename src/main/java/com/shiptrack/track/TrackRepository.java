package com.shiptrack.track;

import static com.shiptrack.clickhouse.SqlUtil.ident;
import static com.shiptrack.clickhouse.SqlUtil.sqlDateParam;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class TrackRepository {
  private static final String SHIP_ID_HAS_LETTER_PATTERN = "[A-Za-z]";

  private final ClickHouseHttpClient clickHouse;
  private final ShipTrackConfig config;

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
          AND isFinite(%s) AND isFinite(%s)
          AND %s BETWEEN -180 AND 180
          AND %s BETWEEN -90 AND 90
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
        ident(c.longitude), ident(c.latitude),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.shipId),
        ident(c.shipId));
    int limitValue = capToPage
        ? Math.min(limitOr(limit, config.query.latestPageSize), config.query.latestPageSize)
        : Math.min(limitOr(limit, config.query.maxLatestShips), config.query.maxLatestShips);
    Map<String, Object> params = params("start", start, "end", end, "afterShipId", afterShipId == null ? "" : afterShipId, "limit", limitValue);
    putBbox(params, bbox);
    return clickHouse.query(query, params);
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
          AND isFinite(%s) AND isFinite(%s)
          AND %s BETWEEN -180 AND 180
          AND %s BETWEEN -90 AND 90
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
        ident(c.longitude), ident(c.latitude),
        ident(c.longitude),
        ident(c.latitude),
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

  public List<Map<String, Object>> candidates(String start, String end, BBox bbox, int limit) {
    ShipTrackConfig.BucketIndexColumns ic = config.bucketIndexColumns;
    String query = """
        SELECT
          %s AS shipId,
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
        ORDER BY points DESC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(ic.shipId),
        ident(ic.bucketStart),
        ident(ic.bucketStart),
        ident(config.tables.bucketIndex),
        ident(ic.bucketStart), sqlDateParam("start"),
        ident(ic.bucketStart), sqlDateParam("end"),
        ident(ic.maxLng),
        ident(ic.minLng),
        ident(ic.maxLat),
        ident(ic.minLat),
        ident(ic.shipId));
    Map<String, Object> params = params("start", start, "end", end, "limit", Math.min(limitOr(limit, config.query.maxMultiShips), config.query.maxMultiShips));
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public List<Map<String, Object>> trackRows(List<String> shipIds, String start, String end, int zoom, BBox bbox, String mode) {
    if (shipIds == null || shipIds.isEmpty()) {
      return List.of();
    }
    ShipTrackConfig.Columns c = config.columns;
    int bucketSeconds = calculateBucketSizeSeconds(zoom, start, end, config.query.maxTrackPointsPerShip, mode);
    String bboxFilter = bbox == null ? "" : """
        AND %s BETWEEN {west: Float64} AND {east: Float64}
        AND %s BETWEEN {south: Float64} AND {north: Float64}
        """.formatted(ident(c.longitude), ident(c.latitude));
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
        LIMIT {limit: UInt32}
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
    Map<String, Object> params = params("shipIds", shipIds, "start", start, "end", end, "bucketSeconds", bucketSeconds,
        "limit", config.query.maxTrackPointsPerShip * Math.max(1, shipIds.size()));
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public List<Map<String, Object>> globalSegment(String start, String end, BBox bbox, int zoom) {
    ShipTrackConfig.Columns c = config.columns;
    ShipTrackConfig.BucketIndexColumns ic = config.bucketIndexColumns;
    int bucketSeconds = calculateBucketSizeSeconds(zoom, start, end, config.query.maxTrackPointsPerShip, "global");
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
          AND %s BETWEEN {west: Float64} AND {east: Float64}
          AND %s BETWEEN {south: Float64} AND {north: Float64}
          AND %s IN (
            SELECT %s
            FROM %s
            WHERE %s >= %s
              AND %s < %s
              AND %s >= {west: Float64}
              AND %s <= {east: Float64}
              AND %s >= {south: Float64}
              AND %s <= {north: Float64}
            GROUP BY %s
            ORDER BY count() DESC
            LIMIT 5000
          )
        GROUP BY shipId, bucket
        ORDER BY time ASC
        LIMIT {limit: UInt32}
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
        ident(c.longitude),
        ident(c.latitude),
        ident(c.shipId),
        ident(ic.shipId),
        ident(config.tables.bucketIndex),
        ident(ic.bucketStart), sqlDateParam("start"),
        ident(ic.bucketStart), sqlDateParam("end"),
        ident(ic.maxLng),
        ident(ic.minLng),
        ident(ic.maxLat),
        ident(ic.minLat),
        ident(ic.shipId));
    Map<String, Object> params = params("start", start, "end", end, "bucketSeconds", bucketSeconds,
        "limit", config.query.maxGlobalSegmentPoints);
    putBbox(params, bbox);
    return clickHouse.query(query, params);
  }

  public double densityGridSizeDegrees(int zoom) {
    if (zoom >= 13) return 0.0025;
    if (zoom >= 11) return 0.005;
    if (zoom >= 9) return 0.01;
    if (zoom >= 7) return 0.03;
    return 0.08;
  }

  public int calculateBucketSizeSeconds(int zoom, String start, String end, int maxPoints, String mode) {
    long spanSeconds = Math.max(1, Instant.parse(end).getEpochSecond() - Instant.parse(start).getEpochSecond());
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

  private String isAisSelectExpr(ShipTrackConfig.Columns c) {
    return "if(not(match(toString(%s), '%s')), 0, argMax(isAis, %s)) AS isAis"
        .formatted(ident(c.shipId), SHIP_ID_HAS_LETTER_PATTERN, ident(c.eventTime));
  }

  private int realtimeDeltaLimit() {
    return Math.min(config.query.maxRealtimeDeltaShips, config.query.maxLatestShips);
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
}

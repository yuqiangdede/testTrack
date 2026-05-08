package com.shiptrack.track;

import static com.shiptrack.clickhouse.SqlUtil.ident;
import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TrackSimplificationService {
  private static final Logger log = LoggerFactory.getLogger(TrackSimplificationService.class);
  private static final int LEVEL_COUNT = 4;

  private final ClickHouseHttpClient clickHouse;
  private final ShipTrackConfig config;

  public TrackSimplificationService(ClickHouseHttpClient clickHouse, ShipConfigService configService) {
    this.clickHouse = clickHouse;
    this.config = configService.config();
  }

  @Scheduled(fixedDelayString = "300000", initialDelayString = "300000")
  public void runIncrementalSchedule() {
    if (!config.simplify.enabled) {
      return;
    }
    try {
      int ships = processIncremental(false);
      if (ships > 0) {
        log.info("track simplify incremental completed ships={}", ships);
      }
    } catch (RuntimeException error) {
      log.warn("track simplify incremental failed: {}", error.getMessage(), error);
    }
  }

  public int backfillAll() {
    int totalShips = 0;
    while (true) {
      int ships = processIncremental(true);
      totalShips += ships;
      if (ships == 0) {
        return totalShips;
      }
    }
  }

  public int processIncremental(boolean backfillMode) {
    List<String> shipIds = nextShipIds();
    for (String shipId : shipIds) {
      processShip(shipId);
    }
    return shipIds.size();
  }

  public List<String> nextShipIds() {
    ShipTrackConfig.Columns c = config.columns;
    String query = """
        WITH offsets AS (
          SELECT
            ship_serial_no AS shipId,
            argMax(last_event_time, updated_at) AS lastEventTime
          FROM %s
          GROUP BY ship_serial_no
        )
        SELECT
          base.shipId AS shipId
        FROM
        (
          SELECT
            %s AS shipId,
            max(%s) AS maxEventTime
          FROM %s
          GROUP BY %s
        ) AS base
        LEFT JOIN offsets ON offsets.shipId = base.shipId
        WHERE offsets.shipId IS NULL OR base.maxEventTime > offsets.lastEventTime
        ORDER BY base.shipId ASC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(config.tables.simplifyOffset),
        ident(c.shipId),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.shipId));
    return clickHouse.query(query, Map.of("limit", Math.max(1, config.simplify.shipBatchSize))).stream()
        .map(row -> String.valueOf(row.getOrDefault("shipId", "")))
        .filter(value -> !value.isBlank())
        .toList();
  }

  public void processShip(String shipId) {
    List<Map<String, Object>> source = sourceRows(shipId);
    if (source.isEmpty()) {
      return;
    }
    List<Map<String, Object>> simplified = new ArrayList<>();
    for (int level = 0; level < LEVEL_COUNT; level += 1) {
      List<Map<String, Object>> levelRows = simplifyRows(source, toleranceForLevel(level));
      for (Map<String, Object> row : levelRows) {
        simplified.add(simplifiedRow(row, level));
      }
    }
    if (!simplified.isEmpty()) {
      insertSimplifiedRows(simplified);
    }
    Map<String, Object> last = source.get(source.size() - 1);
    insertOffset(shipId, String.valueOf(last.getOrDefault("time", "")), source.size());
  }

  public List<Map<String, Object>> simplifyRows(List<Map<String, Object>> rows, double tolerance) {
    if (rows == null || rows.size() <= 2 || tolerance <= 0) {
      return rows == null ? List.of() : rows;
    }
    boolean[] keep = new boolean[rows.size()];
    keep[0] = true;
    keep[rows.size() - 1] = true;
    simplifyRange(rows, 0, rows.size() - 1, tolerance * tolerance, keep);
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < rows.size(); i += 1) {
      if (keep[i]) {
        result.add(rows.get(i));
      }
    }
    return result;
  }

  private void simplifyRange(List<Map<String, Object>> rows, int start, int end, double toleranceSquared, boolean[] keep) {
    if (end <= start + 1) {
      return;
    }
    double maxDistance = -1;
    int maxIndex = -1;
    Point a = point(rows.get(start));
    Point b = point(rows.get(end));
    for (int i = start + 1; i < end; i += 1) {
      double distance = squaredDistanceToSegment(point(rows.get(i)), a, b);
      if (distance > maxDistance) {
        maxDistance = distance;
        maxIndex = i;
      }
    }
    if (maxIndex >= 0 && maxDistance > toleranceSquared) {
      keep[maxIndex] = true;
      simplifyRange(rows, start, maxIndex, toleranceSquared, keep);
      simplifyRange(rows, maxIndex, end, toleranceSquared, keep);
    }
  }

  private List<Map<String, Object>> sourceRows(String shipId) {
    ShipTrackConfig.Columns c = config.columns;
    String query = """
        WITH offset AS (
          SELECT argMax(last_event_time, updated_at) AS lastEventTime
          FROM %s
          WHERE ship_serial_no = {shipId: String}
        )
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
        PREWHERE %s = {shipId: String}
        WHERE %s > ifNull((SELECT lastEventTime FROM offset), toDateTime64('1970-01-01 00:00:00', 3, 'Asia/Shanghai'))
        ORDER BY %s ASC
        LIMIT {limit: UInt32}
        """.formatted(
        ident(config.tables.simplifyOffset),
        ident(c.shipId),
        ident(c.shipName),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.shipId),
        ident(c.eventTime),
        ident(c.eventTime));
    return clickHouse.query(query, Map.of(
        "shipId", shipId,
        "limit", Math.max(1, config.simplify.rowsPerShipBatch)));
  }

  private void insertSimplifiedRows(List<Map<String, Object>> rows) {
    ShipTrackConfig.Columns c = config.columns;
    String sql = """
        INSERT INTO %s
        (%s, simplify_level, %s, %s, %s, %s, %s, %s, isAis)
        """.formatted(
        ident(config.tables.simplifiedTrack),
        ident(c.shipId),
        ident(c.eventTime),
        ident(c.longitude),
        ident(c.latitude),
        ident(c.speed),
        ident(c.heading),
        ident(c.shipName));
    clickHouse.insertJsonEachRow(sql, rows, config.query.clickhouseTimeoutSeconds, config.query.clickhouseTimeoutSeconds);
  }

  private void insertOffset(String shipId, String lastEventTime, int processedRows) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ship_serial_no", shipId);
    row.put("last_event_time", lastEventTime);
    row.put("processed_rows", processedRows);
    clickHouse.insertJsonEachRow("INSERT INTO " + ident(config.tables.simplifyOffset)
        + " (ship_serial_no, last_event_time, processed_rows)", List.of(row),
        config.query.clickhouseTimeoutSeconds, config.query.clickhouseTimeoutSeconds);
  }

  private Map<String, Object> simplifiedRow(Map<String, Object> row, int level) {
    ShipTrackConfig.Columns c = config.columns;
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(c.shipId, row.get("shipId"));
    value.put("simplify_level", level);
    value.put(c.eventTime, row.get("time"));
    value.put(c.longitude, row.get("lng"));
    value.put(c.latitude, row.get("lat"));
    value.put(c.speed, row.get("speed"));
    value.put(c.heading, row.get("heading"));
    value.put(c.shipName, row.get("shipName"));
    value.put("isAis", row.get("isAis"));
    return value;
  }

  private double toleranceForLevel(int level) {
    if (config.simplify.levelTolerances == null || config.simplify.levelTolerances.isEmpty()) {
      return 0;
    }
    int index = Math.max(0, Math.min(level, config.simplify.levelTolerances.size() - 1));
    return Math.max(0, config.simplify.levelTolerances.get(index));
  }

  private Point point(Map<String, Object> row) {
    return new Point(toDouble(row.get("lng")), toDouble(row.get("lat")));
  }

  private double squaredDistanceToSegment(Point p, Point a, Point b) {
    double dx = b.x() - a.x();
    double dy = b.y() - a.y();
    if (dx == 0 && dy == 0) {
      return squaredDistance(p, a);
    }
    double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / (dx * dx + dy * dy);
    t = Math.max(0, Math.min(1, t));
    return squaredDistance(p, new Point(a.x() + t * dx, a.y() + t * dy));
  }

  private double squaredDistance(Point a, Point b) {
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    return dx * dx + dy * dy;
  }

  private double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private record Point(double x, double y) {}
}

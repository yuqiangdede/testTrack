package com.shiptrack.backfill;

import static com.shiptrack.clickhouse.SqlUtil.ident;
import static com.shiptrack.clickhouse.SqlUtil.sqlString;
import static com.shiptrack.clickhouse.SqlUtil.toDateTime;
import static com.shiptrack.clickhouse.SqlUtil.toDateTime64;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import java.io.File;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ship.mode", havingValue = "backfill")
public class BackfillRunner implements CommandLineRunner, ExitCodeGenerator {
  private static final double GIB = Math.pow(1024, 3);

  private final ClickHouseHttpClient clickHouse;
  private final ShipTrackConfig config;
  private int exitCode;

  public BackfillRunner(ClickHouseHttpClient clickHouse, ShipConfigService configService) {
    this.clickHouse = clickHouse;
    this.config = configService.config();
  }

  @Override
  public void run(String... args) {
    try {
      runBackfill(args);
    } catch (RuntimeException error) {
      exitCode = 1;
      System.err.println(error.getMessage() == null ? String.valueOf(error) : error.getMessage());
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private void runBackfill(String... args) {
    BackfillOptions options = parseArgs(args);
    validateOptions(options);
    printRunHeader(options);

    List<Map<String, Object>> tables = tableStats(options);
    TableStat mainStats = tableStat(tables, config.tables.track);
    TableStat bucketStats = tableStat(tables, config.tables.bucketIndex);
    TableStat sourceSizeStats = sourcePartitionStats(options);
    TableStat estimateStats = sourceSizeStats.totalBytes > 0 ? sourceSizeStats : new TableStat(mainStats.totalRows + bucketStats.totalRows, mainStats.totalBytes + bucketStats.totalBytes);
    Map<String, Object> currentRange = queryOne("""
        SELECT
          count() AS rows,
          min(%s) AS min_time,
          max(%s) AS max_time,
          uniqExact(toDate(%s)) AS days
        FROM %s
        """.formatted(ident(config.columns.eventTime), ident(config.columns.eventTime), ident(config.columns.eventTime), ident(config.tables.track)), options);
    Map<String, Object> sourceRange = queryOne("""
        SELECT count() AS rows
        FROM %s
        WHERE %s >= %s
          AND %s < %s
        """.formatted(
        ident(config.tables.track),
        ident(config.columns.eventTime), toDateTime64(options.sourceStart),
        ident(config.columns.eventTime), toDateTime64(addDays(options.sourceStart, options.sourceDays))), options);
    DateRange targetRange = dateRange(options.targetStart, options.targetDays);
    Map<String, Object> existingTarget = countTargetRows(targetRange.start, targetRange.end, options);

    Long hostFree = hostDriveFreeBytes(options.hostDrive);
    Long clickHouseFree = clickHouseFreeBytes(options);
    Long effectiveFree = minKnown(hostFree, clickHouseFree);
    Estimate estimatedTotal = estimateBytes(estimateStats, options.targetDays, options);

    System.out.println("Current main range: " + currentRange);
    System.out.println("Source rows in configured 3-day window: " + sourceRange.get("rows"));
    System.out.println("Existing target rows: " + existingTarget);
    System.out.println("Table size: " + orderedMap(
        "main", formatGiB(mainStats.totalBytes),
        "bucket", formatGiB(bucketStats.totalBytes),
        "total", formatGiB(mainStats.totalBytes + bucketStats.totalBytes)));
    System.out.println("Source partition size used for estimates: " + orderedMap(
        "total", formatGiB(estimateStats.totalBytes),
        "days", options.sourceDays));
    System.out.println("Free space: " + orderedMap(
        "hostDrive", hostFree == null ? "unknown" : formatGiB(hostFree),
        "clickHouseDisk", clickHouseFree == null ? "unknown" : formatGiB(clickHouseFree),
        "effective", effectiveFree == null ? "unknown" : formatGiB(effectiveFree)));
    System.out.println("Estimated 100-day add: " + orderedMap(
        "raw", formatGiB(estimatedTotal.raw),
        "withSafetyFactor", formatGiB(estimatedTotal.withSafety)));

    if (toLong(sourceRange.get("rows")) <= 0) {
      throw new IllegalStateException("Source window has no rows; aborting.");
    }
    if (!options.resume && (toLong(existingTarget.get("mainRows")) > 0 || toLong(existingTarget.get("bucketRows")) > 0)) {
      throw new IllegalStateException("Target range already contains rows. Use --resume only if you intentionally want to fill missing days.");
    }
    if (!options.execute) {
      System.out.println("Dry run only. Add --execute to insert data.");
      return;
    }

    ensureDateMathWorks(options);
    for (Batch batch : buildBatches(options.targetStart, options.targetDays, options.batchDays)) {
      Long freeBefore = minKnown(hostDriveFreeBytes(options.hostDrive), clickHouseFreeBytes(options));
      double estimatedBatch = estimateBytes(estimateStats, batch.days.size(), options).withSafety;
      if (freeBefore != null && freeBefore - estimatedBatch < options.reserveGiB * GIB) {
        throw new IllegalStateException("Free space guard stopped before %s: free=%s, estimatedBatch=%s, reserve=%s GiB."
            .formatted(batch.start, formatGiB(freeBefore), formatGiB(estimatedBatch), options.reserveGiB));
      }

      System.out.printf("Batch %s..%s (%d days) started.%n", batch.start, batch.endExclusive, batch.days.size());
      for (String targetDate : batch.days) {
        Map<String, Object> dayStatus = countTargetRows(targetDate, addDays(targetDate, 1), options);
        if (toLong(dayStatus.get("mainRows")) > 0 && toLong(dayStatus.get("bucketRows")) > 0) {
          if (!options.resume) {
            throw new IllegalStateException("Target day " + targetDate + " already contains rows.");
          }
          System.out.println("  " + targetDate + ": skipped, main and bucket rows already exist.");
          continue;
        }
        if (toLong(dayStatus.get("mainRows")) == 0) {
          String sourceDate = mappedSourceDate(targetDate, options);
          System.out.printf("  %s: inserting main rows from %s.%n", targetDate, sourceDate);
          insertMainDay(sourceDate, targetDate, options);
        } else if (!options.resume) {
          throw new IllegalStateException("Target day " + targetDate + " already contains main rows.");
        } else {
          System.out.println("  " + targetDate + ": main rows exist, only filling bucket index.");
        }

        if (toLong(dayStatus.get("bucketRows")) == 0) {
          System.out.println("  " + targetDate + ": inserting bucket rows.");
          insertBucketDay(targetDate, options);
        }
        validateDay(targetDate, options);
      }

      Map<String, Object> after = countTargetRows(batch.start, batch.endExclusive, options);
      Long freeAfter = minKnown(hostDriveFreeBytes(options.hostDrive), clickHouseFreeBytes(options));
      System.out.println("Batch %s..%s done. %s".formatted(batch.start, batch.endExclusive, orderedMap(
          "mainRows", after.get("mainRows"),
          "bucketRows", after.get("bucketRows"),
          "effectiveFree", freeAfter == null ? "unknown" : formatGiB(freeAfter))));
    }

    Map<String, Object> finalRows = countTargetRows(targetRange.start, targetRange.end, options);
    System.out.println("Backfill complete. " + finalRows);
  }

  private BackfillOptions parseArgs(String[] args) {
    BackfillOptions result = new BackfillOptions();
    for (String arg : args) {
      if ("backfill".equals(arg)) {
        continue;
      } else if ("--execute".equals(arg)) {
        result.execute = true;
      } else if ("--resume".equals(arg)) {
        result.resume = true;
      } else if (arg.startsWith("--target-start=")) {
        result.targetStart = value(arg);
      } else if (arg.startsWith("--target-days=")) {
        result.targetDays = Integer.parseInt(value(arg));
      } else if (arg.startsWith("--batch-days=")) {
        result.batchDays = Integer.parseInt(value(arg));
      } else if (arg.startsWith("--reserve-gib=")) {
        result.reserveGiB = Double.parseDouble(value(arg));
      } else if (arg.startsWith("--safety-factor=")) {
        result.safetyFactor = Double.parseDouble(value(arg));
      } else if (arg.startsWith("--host-drive=")) {
        result.hostDrive = value(arg).replace(":", "");
      } else {
        throw new IllegalArgumentException("Unknown argument: " + arg);
      }
    }
    return result;
  }

  private String value(String arg) {
    return arg.substring(arg.indexOf('=') + 1);
  }

  private void validateOptions(BackfillOptions value) {
    if (!value.sourceStart.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new IllegalArgumentException("Invalid sourceStart: " + value.sourceStart);
    if (!value.targetStart.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new IllegalArgumentException("Invalid targetStart: " + value.targetStart);
    if (value.sourceDays <= 0) throw new IllegalArgumentException("Invalid sourceDays: " + value.sourceDays);
    if (value.targetDays <= 0) throw new IllegalArgumentException("Invalid targetDays: " + value.targetDays);
    if (value.batchDays <= 0) throw new IllegalArgumentException("Invalid batchDays: " + value.batchDays);
    if (!Double.isFinite(value.reserveGiB) || value.reserveGiB < 0) throw new IllegalArgumentException("Invalid reserveGiB: " + value.reserveGiB);
    if (!Double.isFinite(value.safetyFactor) || value.safetyFactor < 1) throw new IllegalArgumentException("Invalid safetyFactor: " + value.safetyFactor);
    if (!value.hostDrive.matches("^[A-Za-z]$")) throw new IllegalArgumentException("Invalid hostDrive: " + value.hostDrive);
  }

  private void printRunHeader(BackfillOptions options) {
    DateRange target = dateRange(options.targetStart, options.targetDays);
    System.out.println(orderedMap(
        "mode", options.execute ? "execute" : "dry-run",
        "resume", options.resume,
        "sourceStart", options.sourceStart,
        "sourceDays", options.sourceDays,
        "targetStart", target.start,
        "targetEndExclusive", target.end,
        "targetDays", options.targetDays,
        "batchDays", options.batchDays,
        "reserveGiB", options.reserveGiB,
        "safetyFactor", options.safetyFactor,
        "hostDrive", options.hostDrive + ":"));
  }

  private List<Map<String, Object>> tableStats(BackfillOptions options) {
    return query("""
        SELECT name, total_rows, total_bytes
        FROM system.tables
        WHERE database = currentDatabase()
          AND name IN (%s, %s)
        """.formatted(sqlString(config.tables.track), sqlString(config.tables.bucketIndex)), options);
  }

  private TableStat sourcePartitionStats(BackfillOptions options) {
    List<String> partitions = new ArrayList<>();
    for (int i = 0; i < options.sourceDays; i++) {
      partitions.add(sqlString(addDays(options.sourceStart, i)));
    }
    Map<String, Object> row = queryOne("""
        SELECT sum(data_compressed_bytes) AS total_bytes
        FROM system.parts
        WHERE active
          AND database = currentDatabase()
          AND table IN (%s, %s)
          AND partition IN (%s)
        """.formatted(sqlString(config.tables.track), sqlString(config.tables.bucketIndex), String.join(", ", partitions)), options);
    return new TableStat(0, toLong(row.get("total_bytes")));
  }

  private TableStat tableStat(List<Map<String, Object>> rows, String name) {
    for (Map<String, Object> row : rows) {
      if (name.equals(String.valueOf(row.get("name")))) {
        return new TableStat(toLong(row.get("total_rows")), toLong(row.get("total_bytes")));
      }
    }
    throw new IllegalStateException("Table not found: " + name);
  }

  private Map<String, Object> countTargetRows(String start, String end, BackfillOptions options) {
    return queryOne("""
        SELECT
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
          ) AS mainRows,
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
          ) AS bucketRows
        """.formatted(
        ident(config.tables.track),
        ident(config.columns.eventTime), toDateTime64(start),
        ident(config.columns.eventTime), toDateTime64(end),
        ident(config.tables.bucketIndex),
        ident(config.bucketIndexColumns.bucketStart), toDateTime(start),
        ident(config.bucketIndexColumns.bucketStart), toDateTime(end)), options);
  }

  private Long clickHouseFreeBytes(BackfillOptions options) {
    List<Map<String, Object>> rows = query("""
        SELECT free_space
        FROM system.disks
        ORDER BY free_space DESC
        LIMIT 1
        """, options);
    return rows.isEmpty() ? null : toLong(rows.get(0).get("free_space"));
  }

  private Long hostDriveFreeBytes(String drive) {
    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
      return null;
    }
    File root = new File(drive + ":\\");
    long value = root.getUsableSpace();
    return value > 0 ? value : null;
  }

  private void ensureDateMathWorks(BackfillOptions options) {
    queryOne("""
        SELECT
          addMilliseconds(%s, toInt64(1)) AS shifted,
          toUnixTimestamp64Milli(addMilliseconds(%s, toInt64(1))) AS shifted_ms
        """.formatted(toDateTime64(options.targetStart), toDateTime64(options.targetStart)), options);
  }

  private void insertMainDay(String sourceDate, String targetDate, BackfillOptions options) {
    ShipTrackConfig.Columns c = config.columns;
    String sourceEnd = addDays(sourceDate, 1);
    String targetCompact = targetDate.replace("-", "");
    String suffix = sqlString("#bf#" + targetCompact);
    String sourceStart = toDateTime64(sourceDate);
    String targetStart = toDateTime64(targetDate);
    String deltaLng = "(toFloat64(cityHash64(%s, ais_id, toString(%s), %s, 'lng') %% 1000000) / 1000000.0 - 0.5) * 0.006"
        .formatted(ident(c.shipId), ident(c.eventTime), sqlString(targetDate));
    String deltaLat = "(toFloat64(cityHash64(%s, ais_id, toString(%s), %s, 'lat') %% 1000000) / 1000000.0 - 0.5) * 0.006"
        .formatted(ident(c.shipId), ident(c.eventTime), sqlString(targetDate));
    String speedFactor = "1.0 + ((toFloat64(cityHash64(%s, ais_id, toString(%s), %s, 'spd') %% 1000000) / 1000000.0 - 0.5) * 0.10)"
        .formatted(ident(c.shipId), ident(c.eventTime), sqlString(targetDate));
    String courseDelta = "(toFloat64(cityHash64(%s, ais_id, toString(%s), %s, 'crs') %% 1000000) / 1000000.0 - 0.5) * 4.0"
        .formatted(ident(c.shipId), ident(c.eventTime), sqlString(targetDate));

    runSql("""
        INSERT INTO %s
          (ais_id, %s, %s, longitude, latitude, %s, %s,
           %s, %s, course, alarm_flag, ais_type, target_id,
           %s, event_time_utc, isAis)
        SELECT
          concat(toString(ais_id), %s) AS ais_id,
          %s,
          %s,
          greatest(-180.0, least(180.0, longitude + %s)) AS longitude,
          greatest(-90.0, least(90.0, latitude + %s)) AS latitude,
          greatest(-180.0, least(180.0, %s + %s)) AS %s,
          greatest(-90.0, least(90.0, %s + %s)) AS %s,
          toFloat32(greatest(0.0, toFloat64(%s) * %s)) AS %s,
          toFloat32(modulo(toFloat64(%s) + %s + 360.0, 360.0)) AS %s,
          toFloat32(modulo(toFloat64(course) + %s + 360.0, 360.0)) AS course,
          alarm_flag,
          ais_type,
          concat(toString(target_id), %s) AS target_id,
          new_event_time AS %s,
          toUnixTimestamp64Milli(new_event_time) AS event_time_utc,
          isAis
        FROM
        (
          SELECT
            *,
            addMilliseconds(
              %s,
              toInt64(toUnixTimestamp64Milli(%s) - toUnixTimestamp64Milli(%s))
            ) AS new_event_time
          FROM %s
          WHERE %s >= %s
            AND %s < %s
        )
        """.formatted(
        ident(config.tables.track),
        ident(c.shipId), ident(c.shipName), ident(c.longitude), ident(c.latitude),
        ident(c.speed), ident(c.heading), ident(c.eventTime),
        suffix,
        ident(c.shipId),
        ident(c.shipName),
        deltaLng,
        deltaLat,
        ident(c.longitude), deltaLng, ident(c.longitude),
        ident(c.latitude), deltaLat, ident(c.latitude),
        ident(c.speed), speedFactor, ident(c.speed),
        ident(c.heading), courseDelta, ident(c.heading),
        courseDelta,
        suffix,
        ident(c.eventTime),
        targetStart,
        ident(c.eventTime), sourceStart,
        ident(config.tables.track),
        ident(c.eventTime), sourceStart,
        ident(c.eventTime), toDateTime64(sourceEnd)), options);
  }

  private void insertBucketDay(String targetDate, BackfillOptions options) {
    ShipTrackConfig.Columns c = config.columns;
    ShipTrackConfig.BucketIndexColumns ic = config.bucketIndexColumns;
    String targetEnd = addDays(targetDate, 1);
    long version = System.currentTimeMillis();
    runSql("""
        INSERT INTO %s
          (%s, %s, %s, %s, %s, %s, version)
        SELECT
          %s AS %s,
          toStartOfInterval(%s, INTERVAL 2 minute) AS %s,
          min(%s) AS %s,
          max(%s) AS %s,
          min(%s) AS %s,
          max(%s) AS %s,
          toUInt64(%d) AS version
        FROM %s
        WHERE %s >= %s
          AND %s < %s
        GROUP BY %s, %s
        """.formatted(
        ident(config.tables.bucketIndex),
        ident(ic.shipId), ident(ic.bucketStart), ident(ic.minLng), ident(ic.maxLng), ident(ic.minLat), ident(ic.maxLat),
        ident(c.shipId), ident(ic.shipId),
        ident(c.eventTime), ident(ic.bucketStart),
        ident(c.longitude), ident(ic.minLng),
        ident(c.longitude), ident(ic.maxLng),
        ident(c.latitude), ident(ic.minLat),
        ident(c.latitude), ident(ic.maxLat),
        version,
        ident(config.tables.track),
        ident(c.eventTime), toDateTime64(targetDate),
        ident(c.eventTime), toDateTime64(targetEnd),
        ident(c.shipId), ident(ic.bucketStart)), options);
  }

  private void validateDay(String targetDate, BackfillOptions options) {
    String targetEnd = addDays(targetDate, 1);
    ShipTrackConfig.Columns c = config.columns;
    ShipTrackConfig.BucketIndexColumns ic = config.bucketIndexColumns;
    Map<String, Object> result = queryOne("""
        SELECT
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
          ) AS mainRows,
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
          ) AS bucketRows,
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
              AND event_time_utc != toUnixTimestamp64Milli(%s)
          ) AS badUtcRows,
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
              AND (%s < -180 OR %s > 180 OR %s < -90 OR %s > 90)
          ) AS badCoordRows,
          (
            SELECT count()
            FROM %s
            WHERE %s >= %s
              AND %s < %s
              AND (toSecond(%s) != 0 OR modulo(toMinute(%s), 2) != 0)
          ) AS badBucketRows
        """.formatted(
        ident(config.tables.track),
        ident(c.eventTime), toDateTime64(targetDate),
        ident(c.eventTime), toDateTime64(targetEnd),
        ident(config.tables.bucketIndex),
        ident(ic.bucketStart), toDateTime(targetDate),
        ident(ic.bucketStart), toDateTime(targetEnd),
        ident(config.tables.track),
        ident(c.eventTime), toDateTime64(targetDate),
        ident(c.eventTime), toDateTime64(targetEnd),
        ident(c.eventTime),
        ident(config.tables.track),
        ident(c.eventTime), toDateTime64(targetDate),
        ident(c.eventTime), toDateTime64(targetEnd),
        ident(c.longitude), ident(c.longitude), ident(c.latitude), ident(c.latitude),
        ident(config.tables.bucketIndex),
        ident(ic.bucketStart), toDateTime(targetDate),
        ident(ic.bucketStart), toDateTime(targetEnd),
        ident(ic.bucketStart), ident(ic.bucketStart)), options);
    if (toLong(result.get("mainRows")) <= 0) throw new IllegalStateException(targetDate + ": main validation failed, no rows.");
    if (toLong(result.get("bucketRows")) <= 0) throw new IllegalStateException(targetDate + ": bucket validation failed, no rows.");
    if (toLong(result.get("badUtcRows")) > 0) throw new IllegalStateException(targetDate + ": bad event_time_utc rows: " + result.get("badUtcRows"));
    if (toLong(result.get("badCoordRows")) > 0) throw new IllegalStateException(targetDate + ": bad coordinate rows: " + result.get("badCoordRows"));
    if (toLong(result.get("badBucketRows")) > 0) throw new IllegalStateException(targetDate + ": bad bucket rows: " + result.get("badBucketRows"));
    System.out.printf("  %s: validated main=%s, bucket=%s.%n", targetDate, result.get("mainRows"), result.get("bucketRows"));
  }

  private String mappedSourceDate(String targetDate, BackfillOptions options) {
    long offset = daysBetween(options.targetStart, targetDate) % options.sourceDays;
    return addDays(options.sourceStart, (int) offset);
  }

  private List<Batch> buildBatches(String start, int days, int batchSize) {
    List<Batch> batches = new ArrayList<>();
    for (int offset = 0; offset < days; offset += batchSize) {
      int batchDays = Math.min(batchSize, days - offset);
      String batchStart = addDays(start, offset);
      List<String> dayList = new ArrayList<>();
      for (int i = 0; i < batchDays; i++) {
        dayList.add(addDays(batchStart, i));
      }
      batches.add(new Batch(batchStart, addDays(batchStart, batchDays), dayList));
    }
    return batches;
  }

  private DateRange dateRange(String start, int days) {
    return new DateRange(start, addDays(start, days));
  }

  private String addDays(String date, int days) {
    return LocalDate.parse(date).plusDays(days).toString();
  }

  private long daysBetween(String start, String end) {
    return ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
  }

  private Long minKnown(Long... values) {
    Long min = null;
    for (Long value : values) {
      if (value != null) {
        min = min == null ? value : Math.min(min, value);
      }
    }
    return min;
  }

  private Estimate estimateBytes(TableStat stats, int days, BackfillOptions options) {
    double raw = ((double) stats.totalBytes / options.sourceDays) * days;
    return new Estimate(raw, raw * options.safetyFactor);
  }

  private String formatGiB(double bytes) {
    return "%.2f GiB".formatted(bytes / GIB);
  }

  private List<Map<String, Object>> query(String sql, BackfillOptions options) {
    return clickHouse.query(sql, Map.of(), options.selectTimeoutSeconds, options.selectTimeoutSeconds);
  }

  private Map<String, Object> queryOne(String sql, BackfillOptions options) {
    return clickHouse.queryOne(sql, options.selectTimeoutSeconds, options.selectTimeoutSeconds);
  }

  private void runSql(String sql, BackfillOptions options) {
    clickHouse.runSql(sql, options.insertTimeoutSeconds, options.insertTimeoutSeconds);
  }

  private long toLong(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value);
    if (text.isBlank() || "null".equalsIgnoreCase(text)) {
      return 0;
    }
    return Long.parseLong(text);
  }

  private Map<String, Object> orderedMap(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      map.put(String.valueOf(values[i]), values[i + 1]);
    }
    return map;
  }

  private record TableStat(long totalRows, long totalBytes) {}

  private record Estimate(double raw, double withSafety) {}

  private record DateRange(String start, String end) {}

  private record Batch(String start, String endExclusive, List<String> days) {}
}

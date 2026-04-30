import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { request as httpRequest } from "node:http";
import { resolve } from "node:path";

const GiB = 1024 ** 3;
const DEFAULTS = {
  sourceStart: "2026-04-17",
  sourceDays: 3,
  targetStart: "2026-01-07",
  targetDays: 100,
  batchDays: 5,
  reserveGiB: 20,
  safetyFactor: 1.2,
  hostDrive: "D",
  insertTimeoutSeconds: 0,
  selectTimeoutSeconds: 120
};

const root = process.cwd();
loadDotEnv();
const config = loadConfig();
const args = parseArgs(process.argv.slice(2));
const options = { ...DEFAULTS, ...args };
const parsedClickHouse = parseJdbc(config.clickhouse.jdbcUrl);

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});

async function main() {
  validateOptions(options);
  printRunHeader();

  const tables = await tableStats();
  const mainStats = tableStat(tables, config.tables.track);
  const bucketStats = tableStat(tables, config.tables.bucketIndex);
  const sourceSizeStats = await sourcePartitionStats();
  const estimateStats = sourceSizeStats.total_bytes > 0 ? sourceSizeStats : { total_bytes: mainStats.total_bytes + bucketStats.total_bytes };
  const currentRange = await queryOne(`
    SELECT
      count() AS rows,
      min(${ident(config.columns.eventTime)}) AS min_time,
      max(${ident(config.columns.eventTime)}) AS max_time,
      uniqExact(toDate(${ident(config.columns.eventTime)})) AS days
    FROM ${ident(config.tables.track)}
  `);
  const sourceRange = await queryOne(`
    SELECT count() AS rows
    FROM ${ident(config.tables.track)}
    WHERE ${ident(config.columns.eventTime)} >= ${toDateTime64(options.sourceStart)}
      AND ${ident(config.columns.eventTime)} < ${toDateTime64(addDays(options.sourceStart, options.sourceDays))}
  `);
  const targetRange = dateRange(options.targetStart, options.targetDays);
  const existingTarget = await countTargetRows(targetRange.start, targetRange.end);

  const hostFree = hostDriveFreeBytes(options.hostDrive);
  const clickHouseFree = await clickHouseFreeBytes();
  const effectiveFree = minKnown(hostFree, clickHouseFree);
  const estimatedTotal = estimateBytes(estimateStats, options.targetDays);

  console.log("Current main range:", currentRange);
  console.log("Source rows in configured 3-day window:", sourceRange.rows);
  console.log("Existing target rows:", existingTarget);
  console.log("Table size:", {
    main: formatGiB(mainStats.total_bytes),
    bucket: formatGiB(bucketStats.total_bytes),
    total: formatGiB(mainStats.total_bytes + bucketStats.total_bytes)
  });
  console.log("Source partition size used for estimates:", {
    total: formatGiB(estimateStats.total_bytes),
    days: options.sourceDays
  });
  console.log("Free space:", {
    hostDrive: hostFree == null ? "unknown" : formatGiB(hostFree),
    clickHouseDisk: clickHouseFree == null ? "unknown" : formatGiB(clickHouseFree),
    effective: effectiveFree == null ? "unknown" : formatGiB(effectiveFree)
  });
  console.log("Estimated 100-day add:", {
    raw: formatGiB(estimatedTotal.raw),
    withSafetyFactor: formatGiB(estimatedTotal.withSafety)
  });

  if (Number(sourceRange.rows) <= 0) {
    throw new Error("Source window has no rows; aborting.");
  }
  if (!options.resume && (Number(existingTarget.mainRows) > 0 || Number(existingTarget.bucketRows) > 0)) {
    throw new Error("Target range already contains rows. Use --resume only if you intentionally want to fill missing days.");
  }
  if (!options.execute) {
    console.log("Dry run only. Add --execute to insert data.");
    return;
  }

  await ensureDateMathWorks();
  const batches = buildBatches(options.targetStart, options.targetDays, options.batchDays);
  for (const batch of batches) {
    const freeBefore = minKnown(hostDriveFreeBytes(options.hostDrive), await clickHouseFreeBytes());
    const estimatedBatch = estimateBytes(estimateStats, batch.days.length).withSafety;
    if (freeBefore != null && freeBefore - estimatedBatch < options.reserveGiB * GiB) {
      throw new Error(
        `Free space guard stopped before ${batch.start}: free=${formatGiB(freeBefore)}, ` +
          `estimatedBatch=${formatGiB(estimatedBatch)}, reserve=${options.reserveGiB} GiB.`
      );
    }

    console.log(`Batch ${batch.start}..${batch.endExclusive} (${batch.days.length} days) started.`);
    for (const targetDate of batch.days) {
      const dayStatus = await countTargetRows(targetDate, addDays(targetDate, 1));
      if (Number(dayStatus.mainRows) > 0 && Number(dayStatus.bucketRows) > 0) {
        if (!options.resume) throw new Error(`Target day ${targetDate} already contains rows.`);
        console.log(`  ${targetDate}: skipped, main and bucket rows already exist.`);
        continue;
      }
      if (Number(dayStatus.mainRows) === 0) {
        const sourceDate = mappedSourceDate(targetDate);
        console.log(`  ${targetDate}: inserting main rows from ${sourceDate}.`);
        await insertMainDay(sourceDate, targetDate);
      } else if (!options.resume) {
        throw new Error(`Target day ${targetDate} already contains main rows.`);
      } else {
        console.log(`  ${targetDate}: main rows exist, only filling bucket index.`);
      }

      if (Number(dayStatus.bucketRows) === 0) {
        console.log(`  ${targetDate}: inserting bucket rows.`);
        await insertBucketDay(targetDate);
      }

      await validateDay(targetDate);
    }

    const after = await countTargetRows(batch.start, batch.endExclusive);
    const freeAfter = minKnown(hostDriveFreeBytes(options.hostDrive), await clickHouseFreeBytes());
    console.log(`Batch ${batch.start}..${batch.endExclusive} done.`, {
      mainRows: after.mainRows,
      bucketRows: after.bucketRows,
      effectiveFree: freeAfter == null ? "unknown" : formatGiB(freeAfter)
    });
  }

  const finalRows = await countTargetRows(targetRange.start, targetRange.end);
  console.log("Backfill complete.", finalRows);
}

function loadDotEnv() {
  const envPath = resolve(root, ".env");
  if (!existsSync(envPath)) return;
  const lines = readFileSync(envPath, "utf-8").split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) continue;
    const index = trimmed.indexOf("=");
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1).trim().replace(/^["']|["']$/g, "");
    if (!process.env[key]) process.env[key] = value;
  }
}

function loadConfig() {
  const fileConfig = JSON.parse(readFileSync(resolve(root, "config", "ship-track.config.json"), "utf-8"));
  return {
    ...fileConfig,
    clickhouse: {
      ...fileConfig.clickhouse,
      jdbcUrl: process.env.CLICKHOUSE_JDBC_URL || fileConfig.clickhouse.jdbcUrl,
      username: process.env.CLICKHOUSE_USER || fileConfig.clickhouse.username,
      password: process.env.CLICKHOUSE_PASSWORD || fileConfig.clickhouse.password
    }
  };
}

function parseArgs(argv) {
  const result = {};
  for (const arg of argv) {
    if (arg === "--execute") result.execute = true;
    else if (arg === "--resume") result.resume = true;
    else if (arg.startsWith("--target-start=")) result.targetStart = arg.split("=")[1];
    else if (arg.startsWith("--target-days=")) result.targetDays = Number(arg.split("=")[1]);
    else if (arg.startsWith("--batch-days=")) result.batchDays = Number(arg.split("=")[1]);
    else if (arg.startsWith("--reserve-gib=")) result.reserveGiB = Number(arg.split("=")[1]);
    else if (arg.startsWith("--safety-factor=")) result.safetyFactor = Number(arg.split("=")[1]);
    else if (arg.startsWith("--host-drive=")) result.hostDrive = arg.split("=")[1].replace(/:$/, "");
    else throw new Error(`Unknown argument: ${arg}`);
  }
  return result;
}

function validateOptions(value) {
  for (const key of ["sourceStart", "targetStart"]) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value[key])) throw new Error(`Invalid ${key}: ${value[key]}`);
  }
  for (const key of ["sourceDays", "targetDays", "batchDays"]) {
    if (!Number.isInteger(value[key]) || value[key] <= 0) throw new Error(`Invalid ${key}: ${value[key]}`);
  }
  if (!Number.isFinite(value.reserveGiB) || value.reserveGiB < 0) throw new Error(`Invalid reserveGiB: ${value.reserveGiB}`);
  if (!Number.isFinite(value.safetyFactor) || value.safetyFactor < 1) throw new Error(`Invalid safetyFactor: ${value.safetyFactor}`);
  if (!/^[A-Za-z]$/.test(value.hostDrive)) throw new Error(`Invalid hostDrive: ${value.hostDrive}`);
}

function printRunHeader() {
  const target = dateRange(options.targetStart, options.targetDays);
  console.log({
    mode: options.execute ? "execute" : "dry-run",
    resume: Boolean(options.resume),
    sourceStart: options.sourceStart,
    sourceDays: options.sourceDays,
    targetStart: target.start,
    targetEndExclusive: target.end,
    targetDays: options.targetDays,
    batchDays: options.batchDays,
    reserveGiB: options.reserveGiB,
    safetyFactor: options.safetyFactor,
    hostDrive: `${options.hostDrive}:`
  });
}

function parseJdbc(jdbcUrl) {
  const value = jdbcUrl.replace(/^jdbc:clickhouse:\/\//, "");
  const slash = value.indexOf("/");
  const host = slash >= 0 ? value.slice(0, slash) : value;
  const database = slash >= 0 ? value.slice(slash + 1) || "default" : "default";
  return { url: `http://${host}/`, database };
}

function ident(value) {
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(value)) throw new Error(`Invalid ClickHouse identifier: ${value}`);
  return `\`${value}\``;
}

function sqlString(value) {
  return `'${String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

function toDateTime64(date) {
  return `toDateTime64(${sqlString(`${date} 00:00:00`)}, 3, 'Asia/Shanghai')`;
}

function toDateTime(date) {
  return `toDateTime(${sqlString(`${date} 00:00:00`)}, 'Asia/Shanghai')`;
}

function mappedSourceDate(targetDate) {
  const offset = daysBetween(options.targetStart, targetDate) % options.sourceDays;
  return addDays(options.sourceStart, offset);
}

function buildBatches(start, days, batchSize) {
  const batches = [];
  for (let offset = 0; offset < days; offset += batchSize) {
    const batchDays = Math.min(batchSize, days - offset);
    const batchStart = addDays(start, offset);
    batches.push({
      start: batchStart,
      endExclusive: addDays(batchStart, batchDays),
      days: Array.from({ length: batchDays }, (_, index) => addDays(batchStart, index))
    });
  }
  return batches;
}

function dateRange(start, days) {
  return { start, end: addDays(start, days) };
}

function addDays(date, days) {
  const value = new Date(`${date}T00:00:00.000Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function daysBetween(start, end) {
  const a = Date.parse(`${start}T00:00:00.000Z`);
  const b = Date.parse(`${end}T00:00:00.000Z`);
  return Math.floor((b - a) / 86400000);
}

async function postClickHouse(sql, { json = false, timeoutSeconds = options.selectTimeoutSeconds, maxExecutionTime = timeoutSeconds } = {}) {
  const auth = Buffer.from(`${config.clickhouse.username}:${config.clickhouse.password}`).toString("base64");
  const search = new URLSearchParams({
    database: parsedClickHouse.database,
    max_execution_time: String(maxExecutionTime)
  });
  const body = json ? `${sql}\nFORMAT JSONEachRow` : sql;
  return new Promise((resolvePost, reject) => {
    const target = new URL(`${parsedClickHouse.url}?${search}`);
    const req = httpRequest(
      {
        method: "POST",
        hostname: target.hostname,
        port: target.port || 80,
        path: `${target.pathname}${target.search}`,
        headers: {
          Authorization: `Basic ${auth}`,
          "Content-Type": "text/plain; charset=utf-8",
          "Content-Length": Buffer.byteLength(body)
        }
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf-8");
          if (res.statusCode < 200 || res.statusCode >= 300) {
            reject(new Error(text || `ClickHouse HTTP ${res.statusCode}`));
            return;
          }
          resolvePost(text);
        });
      }
    );
    if (timeoutSeconds > 0) {
      req.setTimeout(timeoutSeconds * 1000, () => req.destroy(new Error(`ClickHouse timeout after ${timeoutSeconds}s`)));
    }
    req.on("error", reject);
    req.end(body);
  });
}

async function queryJson(sql) {
  const text = await postClickHouse(sql, { json: true });
  return text.trim() ? text.trim().split("\n").map((line) => JSON.parse(line)) : [];
}

async function queryOne(sql) {
  const rows = await queryJson(sql);
  return rows[0] || {};
}

async function runSql(sql) {
  await postClickHouse(sql, {
    json: false,
    timeoutSeconds: options.insertTimeoutSeconds,
    maxExecutionTime: options.insertTimeoutSeconds
  });
}

async function tableStats() {
  return queryJson(`
    SELECT name, total_rows, total_bytes
    FROM system.tables
    WHERE database = currentDatabase()
      AND name IN (${sqlString(config.tables.track)}, ${sqlString(config.tables.bucketIndex)})
  `);
}

async function sourcePartitionStats() {
  const sourcePartitions = Array.from({ length: options.sourceDays }, (_, index) => addDays(options.sourceStart, index));
  const rows = await queryJson(`
    SELECT sum(data_compressed_bytes) AS total_bytes
    FROM system.parts
    WHERE active
      AND database = currentDatabase()
      AND table IN (${sqlString(config.tables.track)}, ${sqlString(config.tables.bucketIndex)})
      AND partition IN (${sourcePartitions.map(sqlString).join(", ")})
  `);
  return { total_bytes: Number(rows[0]?.total_bytes || 0) };
}

function tableStat(rows, name) {
  const row = rows.find((item) => item.name === name);
  if (!row) throw new Error(`Table not found: ${name}`);
  return {
    total_rows: Number(row.total_rows || 0),
    total_bytes: Number(row.total_bytes || 0)
  };
}

async function countTargetRows(start, end) {
  const c = config.columns;
  const ic = config.bucketIndexColumns;
  return queryOne(`
    SELECT
      (
        SELECT count()
        FROM ${ident(config.tables.track)}
        WHERE ${ident(c.eventTime)} >= ${toDateTime64(start)}
          AND ${ident(c.eventTime)} < ${toDateTime64(end)}
      ) AS mainRows,
      (
        SELECT count()
        FROM ${ident(config.tables.bucketIndex)}
        WHERE ${ident(ic.bucketStart)} >= ${toDateTime(start)}
          AND ${ident(ic.bucketStart)} < ${toDateTime(end)}
      ) AS bucketRows
  `);
}

async function clickHouseFreeBytes() {
  const rows = await queryJson(`
    SELECT free_space
    FROM system.disks
    ORDER BY free_space DESC
    LIMIT 1
  `);
  return rows.length ? Number(rows[0].free_space) : null;
}

function hostDriveFreeBytes(drive) {
  if (process.platform !== "win32") return null;
  const command = `(Get-PSDrive -Name ${drive}).Free`;
  const result = spawnSync("powershell", ["-NoProfile", "-Command", command], {
    encoding: "utf-8",
    timeout: 10000
  });
  if (result.status !== 0) return null;
  const value = Number(String(result.stdout).trim());
  return Number.isFinite(value) ? value : null;
}

function minKnown(...values) {
  const known = values.filter((value) => value != null && Number.isFinite(value));
  return known.length ? Math.min(...known) : null;
}

function estimateBytes(stats, days) {
  const raw = (stats.total_bytes / options.sourceDays) * days;
  return { raw, withSafety: raw * options.safetyFactor };
}

function formatGiB(bytes) {
  return `${(Number(bytes || 0) / GiB).toFixed(2)} GiB`;
}

async function ensureDateMathWorks() {
  await queryOne(`
    SELECT
      addMilliseconds(${toDateTime64(options.targetStart)}, toInt64(1)) AS shifted,
      toUnixTimestamp64Milli(addMilliseconds(${toDateTime64(options.targetStart)}, toInt64(1))) AS shifted_ms
  `);
}

async function insertMainDay(sourceDate, targetDate) {
  const c = config.columns;
  const sourceEnd = addDays(sourceDate, 1);
  const targetCompact = targetDate.replace(/-/g, "");
  const suffix = sqlString(`#bf#${targetCompact}`);
  const sourceStart = toDateTime64(sourceDate);
  const targetStart = toDateTime64(targetDate);
  const deltaLng = `(toFloat64(cityHash64(${ident(c.shipId)}, ais_id, toString(${ident(c.eventTime)}), ${sqlString(targetDate)}, 'lng') % 1000000) / 1000000.0 - 0.5) * 0.006`;
  const deltaLat = `(toFloat64(cityHash64(${ident(c.shipId)}, ais_id, toString(${ident(c.eventTime)}), ${sqlString(targetDate)}, 'lat') % 1000000) / 1000000.0 - 0.5) * 0.006`;
  const speedFactor = `1.0 + ((toFloat64(cityHash64(${ident(c.shipId)}, ais_id, toString(${ident(c.eventTime)}), ${sqlString(targetDate)}, 'spd') % 1000000) / 1000000.0 - 0.5) * 0.10)`;
  const courseDelta = `(toFloat64(cityHash64(${ident(c.shipId)}, ais_id, toString(${ident(c.eventTime)}), ${sqlString(targetDate)}, 'crs') % 1000000) / 1000000.0 - 0.5) * 4.0`;

  await runSql(`
    INSERT INTO ${ident(config.tables.track)}
      (ais_id, ${ident(c.shipId)}, ${ident(c.shipName)}, longitude, latitude, ${ident(c.longitude)}, ${ident(c.latitude)},
       ${ident(c.speed)}, ${ident(c.heading)}, course, alarm_flag, ais_type, target_id,
       ${ident(c.eventTime)}, event_time_utc, isAis)
    SELECT
      concat(toString(ais_id), ${suffix}) AS ais_id,
      ${ident(c.shipId)},
      ${ident(c.shipName)},
      greatest(-180.0, least(180.0, longitude + ${deltaLng})) AS longitude,
      greatest(-90.0, least(90.0, latitude + ${deltaLat})) AS latitude,
      greatest(-180.0, least(180.0, ${ident(c.longitude)} + ${deltaLng})) AS ${ident(c.longitude)},
      greatest(-90.0, least(90.0, ${ident(c.latitude)} + ${deltaLat})) AS ${ident(c.latitude)},
      toFloat32(greatest(0.0, toFloat64(${ident(c.speed)}) * ${speedFactor})) AS ${ident(c.speed)},
      toFloat32(modulo(toFloat64(${ident(c.heading)}) + ${courseDelta} + 360.0, 360.0)) AS ${ident(c.heading)},
      toFloat32(modulo(toFloat64(course) + ${courseDelta} + 360.0, 360.0)) AS course,
      alarm_flag,
      ais_type,
      concat(toString(target_id), ${suffix}) AS target_id,
      new_event_time AS ${ident(c.eventTime)},
      toUnixTimestamp64Milli(new_event_time) AS event_time_utc,
      isAis
    FROM
    (
      SELECT
        *,
        addMilliseconds(
          ${targetStart},
          toInt64(toUnixTimestamp64Milli(${ident(c.eventTime)}) - toUnixTimestamp64Milli(${sourceStart}))
        ) AS new_event_time
      FROM ${ident(config.tables.track)}
      WHERE ${ident(c.eventTime)} >= ${sourceStart}
        AND ${ident(c.eventTime)} < ${toDateTime64(sourceEnd)}
    )
  `);
}

async function insertBucketDay(targetDate) {
  const c = config.columns;
  const ic = config.bucketIndexColumns;
  const targetEnd = addDays(targetDate, 1);
  const version = Date.now();
  await runSql(`
    INSERT INTO ${ident(config.tables.bucketIndex)}
      (${ident(ic.shipId)}, ${ident(ic.bucketStart)}, ${ident(ic.minLng)}, ${ident(ic.maxLng)}, ${ident(ic.minLat)}, ${ident(ic.maxLat)}, version)
    SELECT
      ${ident(c.shipId)} AS ${ident(ic.shipId)},
      toStartOfInterval(${ident(c.eventTime)}, INTERVAL 2 minute) AS ${ident(ic.bucketStart)},
      min(${ident(c.longitude)}) AS ${ident(ic.minLng)},
      max(${ident(c.longitude)}) AS ${ident(ic.maxLng)},
      min(${ident(c.latitude)}) AS ${ident(ic.minLat)},
      max(${ident(c.latitude)}) AS ${ident(ic.maxLat)},
      toUInt64(${version}) AS version
    FROM ${ident(config.tables.track)}
    WHERE ${ident(c.eventTime)} >= ${toDateTime64(targetDate)}
      AND ${ident(c.eventTime)} < ${toDateTime64(targetEnd)}
    GROUP BY ${ident(c.shipId)}, ${ident(ic.bucketStart)}
  `);
}

async function validateDay(targetDate) {
  const targetEnd = addDays(targetDate, 1);
  const c = config.columns;
  const ic = config.bucketIndexColumns;
  const result = await queryOne(`
    SELECT
      (
        SELECT count()
        FROM ${ident(config.tables.track)}
        WHERE ${ident(c.eventTime)} >= ${toDateTime64(targetDate)}
          AND ${ident(c.eventTime)} < ${toDateTime64(targetEnd)}
      ) AS mainRows,
      (
        SELECT count()
        FROM ${ident(config.tables.bucketIndex)}
        WHERE ${ident(ic.bucketStart)} >= ${toDateTime(targetDate)}
          AND ${ident(ic.bucketStart)} < ${toDateTime(targetEnd)}
      ) AS bucketRows,
      (
        SELECT count()
        FROM ${ident(config.tables.track)}
        WHERE ${ident(c.eventTime)} >= ${toDateTime64(targetDate)}
          AND ${ident(c.eventTime)} < ${toDateTime64(targetEnd)}
          AND event_time_utc != toUnixTimestamp64Milli(${ident(c.eventTime)})
      ) AS badUtcRows,
      (
        SELECT count()
        FROM ${ident(config.tables.track)}
        WHERE ${ident(c.eventTime)} >= ${toDateTime64(targetDate)}
          AND ${ident(c.eventTime)} < ${toDateTime64(targetEnd)}
          AND (${ident(c.longitude)} < -180 OR ${ident(c.longitude)} > 180 OR ${ident(c.latitude)} < -90 OR ${ident(c.latitude)} > 90)
      ) AS badCoordRows,
      (
        SELECT count()
        FROM ${ident(config.tables.bucketIndex)}
        WHERE ${ident(ic.bucketStart)} >= ${toDateTime(targetDate)}
          AND ${ident(ic.bucketStart)} < ${toDateTime(targetEnd)}
          AND (toSecond(${ident(ic.bucketStart)}) != 0 OR modulo(toMinute(${ident(ic.bucketStart)}), 2) != 0)
      ) AS badBucketRows
  `);
  if (Number(result.mainRows) <= 0) throw new Error(`${targetDate}: main validation failed, no rows.`);
  if (Number(result.bucketRows) <= 0) throw new Error(`${targetDate}: bucket validation failed, no rows.`);
  if (Number(result.badUtcRows) > 0) throw new Error(`${targetDate}: bad event_time_utc rows: ${result.badUtcRows}`);
  if (Number(result.badCoordRows) > 0) throw new Error(`${targetDate}: bad coordinate rows: ${result.badCoordRows}`);
  if (Number(result.badBucketRows) > 0) throw new Error(`${targetDate}: bad bucket rows: ${result.badBucketRows}`);
  console.log(`  ${targetDate}: validated main=${result.mainRows}, bucket=${result.bucketRows}.`);
}

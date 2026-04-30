import { createHash } from "node:crypto";
import { createReadStream, existsSync, readFileSync } from "node:fs";
import { extname, join, resolve } from "node:path";
import { createServer, request as httpRequest } from "node:http";
import { gzipSync } from "node:zlib";

const root = process.cwd();
loadDotEnv();
const config = loadConfig();
const clients = new Set();
const latestCache = {
  ready: false,
  warming: false,
  warmSeq: 0,
  warmPromise: null,
  window: {
    start: "",
    end: ""
  },
  byShip: new Map(),
  items: [],
  rows: [],
  watermark: "",
  lastWarmError: "",
  polling: false
};

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

function sqlDateParam(name) {
  return `parseDateTime64BestEffort({${name}: String}, 3, 'Asia/Shanghai')`;
}

function postText(url, body, headers = {}, timeoutMs = 30000) {
  return new Promise((resolvePost, reject) => {
    const target = new URL(url);
    const req = httpRequest(
      {
        method: "POST",
        hostname: target.hostname,
        port: target.port || 80,
        path: `${target.pathname}${target.search}`,
        headers: {
          ...headers,
          "Content-Length": Buffer.byteLength(body)
        },
        timeout: timeoutMs
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          resolvePost({
            ok: res.statusCode >= 200 && res.statusCode < 300,
            statusCode: res.statusCode,
            text: Buffer.concat(chunks).toString("utf-8")
          });
        });
      }
    );
    req.on("timeout", () => {
      req.destroy(new Error(`ClickHouse request timeout after ${timeoutMs}ms`));
    });
    req.on("error", reject);
    req.end(body);
  });
}

async function clickhouse(query, params = {}) {
  const parsed = parseJdbc(config.clickhouse.jdbcUrl);
  const auth = Buffer.from(`${config.clickhouse.username}:${config.clickhouse.password}`).toString("base64");
  const body = `${query}\nFORMAT JSONEachRow`;
  const search = new URLSearchParams({
    database: parsed.database,
    max_execution_time: String(config.query.clickhouseTimeoutSeconds || 30)
  });
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) {
      const items = value.map((item) => `'${String(item).replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`);
      search.set(`param_${key}`, `[${items.join(",")}]`);
    } else {
      search.set(`param_${key}`, String(value));
    }
  }
  let response;
  try {
    response = await postText(
      `${parsed.url}?${search}`,
      body,
      {
        Authorization: `Basic ${auth}`,
        "Content-Type": "text/plain; charset=utf-8"
      },
      (config.query.clickhouseTimeoutSeconds || 30) * 1000
    );
  } catch (error) {
    const cause = error?.code ? ` (${error.code})` : "";
    throw new Error(`ClickHouse connection failed${cause}. Check ${parsed.url} and the connection settings in config/ship-track.config.json.`);
  }
  const text = response.text;
  if (!response.ok) throw new Error(text);
  return text.trim() ? text.trim().split("\n").map((line) => JSON.parse(line)) : [];
}

function densityGridSizeDegrees(zoom) {
  if (zoom >= 13) return 0.0025;
  if (zoom >= 11) return 0.005;
  if (zoom >= 9) return 0.01;
  if (zoom >= 7) return 0.03;
  return 0.08;
}

function calculateBucketSizeSeconds({ zoom, start, end, maxPoints = 2500, mode = "single" }) {
  const spanSeconds = Math.max(1, Math.ceil((end.getTime() - start.getTime()) / 1000));
  const base = Math.ceil(spanSeconds / maxPoints);
  const zoomPenalty = zoom >= 13 ? 1 : zoom >= 10 ? 2 : zoom >= 8 ? 5 : 10;
  const modeFactor = mode === "global" ? 4 : mode === "multi" ? 2 : 1;
  const raw = base * zoomPenalty * modeFactor;
  return [1, 5, 10, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400].find((item) => item >= raw) || 14400;
}

function realtimeDeltaLimit() {
  return Math.min(Number(config.query.maxRealtimeDeltaShips || 10000), Number(config.query.maxLatestShips || 300000));
}

function toLocalDateTimeString(date) {
  const pad = (value, size = 2) => String(value).padStart(size, "0");
  return [
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  ].join(" ");
}

function sameRealtimeWindow(a, b) {
  return String(a?.start || "") === String(b?.start || "") && String(a?.end || "") === String(b?.end || "");
}

function isAisSelectExpr(c) {
  return `if(match(toString(${ident(c.shipId)}), '^[0-9]+[-_][0-9]+$'), 0, argMax(isAis, ${ident(c.eventTime)})) AS isAis`;
}

function bboxFromParams(params) {
  const bbox = {
    west: Number(params.get("west")),
    south: Number(params.get("south")),
    east: Number(params.get("east")),
    north: Number(params.get("north"))
  };
  if (![bbox.west, bbox.south, bbox.east, bbox.north].every(Number.isFinite)) throw new Error("bbox parameters are required");
  if (bbox.west >= bbox.east || bbox.south >= bbox.north) throw new Error("bbox range is invalid");
  return bbox;
}

function timeWindowFromParams(params) {
  const start = params.get("start");
  const end = params.get("end");
  if (!start || !end || Number.isNaN(Date.parse(start)) || Number.isNaN(Date.parse(end))) throw new Error("time window is invalid");
  if (Date.parse(start) >= Date.parse(end)) throw new Error("time window range is invalid");
  return { start, end };
}

function zoomFromParams(params) {
  const zoom = Number(params.get("zoom") || config.map.defaultZoom);
  if (!Number.isInteger(zoom) || zoom < 3 || zoom > 18) throw new Error("zoom parameter is invalid");
  return zoom;
}

async function realtimeWindowFromParams(params) {
  const start = params.get("start");
  const end = params.get("end");
  if (start || end) return timeWindowFromParams(params);

  const watermark = await repo.watermark();
  if (!watermark || Number.isNaN(Date.parse(watermark))) return { start: "", end: "" };

  const endDate = new Date(watermark.replace(" ", "T"));
  const hours = Math.max(1, Number(config.query.realtimeWindowHours || 1));
  const startDate = new Date(endDate.getTime() - hours * 60 * 60 * 1000);
  return {
    start: toLocalDateTimeString(startDate),
    end: toLocalDateTimeString(endDate)
  };
}

const repo = {
  async latest({ afterShipId = "", limit = config.query.latestPageSize || 30000, bbox = null, capToPage = true, start = "", end = "" } = {}) {
    const c = config.columns;
    const cursorFilter = afterShipId ? `AND ${ident(c.shipId)} > {afterShipId: String}` : "";
    const bboxFilter = bbox
      ? `AND ${ident(c.longitude)} BETWEEN {west: Float64} AND {east: Float64}
         AND ${ident(c.latitude)} BETWEEN {south: Float64} AND {north: Float64}`
      : "";
    if (!start || !end) throw new Error("realtime latest time window is required");
    const query = `
      SELECT
        ${ident(c.shipId)} AS shipId,
        argMax(${ident(c.shipName)}, ${ident(c.eventTime)}) AS shipName,
        argMax(${ident(c.longitude)}, ${ident(c.eventTime)}) AS lng,
        argMax(${ident(c.latitude)}, ${ident(c.eventTime)}) AS lat,
        argMax(${ident(c.speed)}, ${ident(c.eventTime)}) AS speed,
        argMax(${ident(c.heading)}, ${ident(c.eventTime)}) AS heading,
        ${isAisSelectExpr(c)},
        toString(max(${ident(c.eventTime)})) AS time
      FROM ${ident(config.tables.track)}
      WHERE ${ident(c.eventTime)} >= ${sqlDateParam("start")}
        AND ${ident(c.eventTime)} < ${sqlDateParam("end")}
        ${cursorFilter}
        ${bboxFilter}
        AND isFinite(${ident(c.longitude)}) AND isFinite(${ident(c.latitude)})
        AND ${ident(c.longitude)} BETWEEN -180 AND 180
        AND ${ident(c.latitude)} BETWEEN -90 AND 90
      GROUP BY ${ident(c.shipId)}
      ORDER BY ${ident(c.shipId)} ASC
      LIMIT {limit: UInt32}`;
    const limitValue = capToPage
      ? Math.min(Number(limit) || config.query.latestPageSize || 30000, config.query.latestPageSize || 30000)
      : Math.min(Number(limit) || config.query.maxLatestShips || 300000, config.query.maxLatestShips || 300000);
    return clickhouse(query, {
      start,
      end,
      afterShipId,
      ...(bbox || {}),
      limit: limitValue
    });
  },
  async watermark() {
    const c = config.columns;
    const rows = await clickhouse(`SELECT toString(max(${ident(c.eventTime)})) AS time FROM ${ident(config.tables.track)}`);
    return rows[0]?.time || "";
  },
  async deltas(since, timeWindow = {}) {
    const c = config.columns;
    const endFilter = timeWindow.end ? `AND ${ident(c.eventTime)} < ${sqlDateParam("end")}` : "";
    const query = `
      SELECT
        ${ident(c.shipId)} AS shipId,
        argMax(${ident(c.shipName)}, ${ident(c.eventTime)}) AS shipName,
        argMax(${ident(c.longitude)}, ${ident(c.eventTime)}) AS lng,
        argMax(${ident(c.latitude)}, ${ident(c.eventTime)}) AS lat,
        argMax(${ident(c.speed)}, ${ident(c.eventTime)}) AS speed,
        argMax(${ident(c.heading)}, ${ident(c.eventTime)}) AS heading,
        ${isAisSelectExpr(c)},
        toString(max(${ident(c.eventTime)})) AS time
      FROM ${ident(config.tables.track)}
      WHERE ${ident(c.eventTime)} > ${sqlDateParam("since")}
        ${endFilter}
        AND isFinite(${ident(c.longitude)}) AND isFinite(${ident(c.latitude)})
        AND ${ident(c.longitude)} BETWEEN -180 AND 180
        AND ${ident(c.latitude)} BETWEEN -90 AND 90
      GROUP BY ${ident(c.shipId)}
      ORDER BY time DESC
      LIMIT {limit: UInt32}`;
    return clickhouse(query, { since, end: timeWindow.end || "", limit: realtimeDeltaLimit() });
  },
  async density({ start, end, bbox, zoom }) {
    const c = config.columns;
    const grid = densityGridSizeDegrees(zoom);
    const query = `
      SELECT
        floor(${ident(c.longitude)} / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lng,
        floor(${ident(c.latitude)} / {grid: Float64}) * {grid: Float64} + ({grid: Float64} / 2) AS lat,
        count() AS count,
        uniqCombined64(${ident(c.shipId)}) AS ships
      FROM ${ident(config.tables.track)}
      WHERE ${ident(c.eventTime)} >= ${sqlDateParam("start")}
        AND ${ident(c.eventTime)} < ${sqlDateParam("end")}
        AND ${ident(c.longitude)} BETWEEN {west: Float64} AND {east: Float64}
        AND ${ident(c.latitude)} BETWEEN {south: Float64} AND {north: Float64}
      GROUP BY lng, lat
      ORDER BY count DESC
      LIMIT {limit: UInt32}`;
    return clickhouse(query, { start, end, ...bbox, grid, limit: config.query.maxDensityCells });
  },
  async candidates({ start, end, bbox, limit }) {
    const ic = config.bucketIndexColumns;
    const query = `
      SELECT
        ${ident(ic.shipId)} AS shipId,
        toString(min(${ident(ic.bucketStart)})) AS firstTime,
        toString(max(${ident(ic.bucketStart)})) AS lastTime,
        count() AS points
      FROM ${ident(config.tables.bucketIndex)}
      WHERE ${ident(ic.bucketStart)} >= ${sqlDateParam("start")}
        AND ${ident(ic.bucketStart)} < ${sqlDateParam("end")}
        AND ${ident(ic.maxLng)} >= {west: Float64}
        AND ${ident(ic.minLng)} <= {east: Float64}
        AND ${ident(ic.maxLat)} >= {south: Float64}
        AND ${ident(ic.minLat)} <= {north: Float64}
      GROUP BY ${ident(ic.shipId)}
      ORDER BY points DESC
      LIMIT {limit: UInt32}`;
    return clickhouse(query, { start, end, ...bbox, limit: Math.min(limit || config.query.maxMultiShips, config.query.maxMultiShips) });
  },
  async trackRows({ shipIds, start, end, zoom, bbox, mode }) {
    if (!shipIds.length) return [];
    const c = config.columns;
    const bucketSeconds = calculateBucketSizeSeconds({
      zoom,
      start: new Date(start),
      end: new Date(end),
      maxPoints: config.query.maxTrackPointsPerShip,
      mode
    });
    const bboxFilter = bbox
      ? `AND ${ident(c.longitude)} BETWEEN {west: Float64} AND {east: Float64}
         AND ${ident(c.latitude)} BETWEEN {south: Float64} AND {north: Float64}`
      : "";
    const query = `
      SELECT
        ${ident(c.shipId)} AS shipId,
        argMin(${ident(c.shipName)}, ${ident(c.eventTime)}) AS shipName,
        argMin(${ident(c.longitude)}, ${ident(c.eventTime)}) AS lng,
        argMin(${ident(c.latitude)}, ${ident(c.eventTime)}) AS lat,
        argMin(${ident(c.speed)}, ${ident(c.eventTime)}) AS speed,
        argMin(${ident(c.heading)}, ${ident(c.eventTime)}) AS heading,
        intDiv(toUnixTimestamp(${ident(c.eventTime)}), {bucketSeconds: UInt32}) AS bucket,
        toString(min(${ident(c.eventTime)})) AS time
      FROM ${ident(config.tables.track)}
      WHERE ${ident(c.shipId)} IN {shipIds: Array(String)}
        AND ${ident(c.eventTime)} >= ${sqlDateParam("start")}
        AND ${ident(c.eventTime)} < ${sqlDateParam("end")}
        ${bboxFilter}
      GROUP BY ${ident(c.shipId)}, bucket
      ORDER BY ${ident(c.shipId)}, time ASC
      LIMIT {limit: UInt32}`;
    return clickhouse(query, {
      shipIds,
      start,
      end,
      ...(bbox || {}),
      bucketSeconds,
      limit: config.query.maxTrackPointsPerShip * Math.max(1, shipIds.length)
    });
  },
  async globalSegment({ start, end, bbox, zoom }) {
    const c = config.columns;
    const ic = config.bucketIndexColumns;
    const bucketSeconds = calculateBucketSizeSeconds({
      zoom,
      start: new Date(start),
      end: new Date(end),
      maxPoints: config.query.maxTrackPointsPerShip,
      mode: "global"
    });
    const query = `
      SELECT
        ${ident(c.shipId)} AS shipId,
        argMin(${ident(c.shipName)}, ${ident(c.eventTime)}) AS shipName,
        argMin(${ident(c.longitude)}, ${ident(c.eventTime)}) AS lng,
        argMin(${ident(c.latitude)}, ${ident(c.eventTime)}) AS lat,
        argMin(${ident(c.speed)}, ${ident(c.eventTime)}) AS speed,
        argMin(${ident(c.heading)}, ${ident(c.eventTime)}) AS heading,
        intDiv(toUnixTimestamp(${ident(c.eventTime)}), {bucketSeconds: UInt32}) AS bucket,
        toString(min(${ident(c.eventTime)})) AS time
      FROM ${ident(config.tables.track)}
      WHERE ${ident(c.eventTime)} >= ${sqlDateParam("start")}
        AND ${ident(c.eventTime)} < ${sqlDateParam("end")}
        AND ${ident(c.longitude)} BETWEEN {west: Float64} AND {east: Float64}
        AND ${ident(c.latitude)} BETWEEN {south: Float64} AND {north: Float64}
        AND ${ident(c.shipId)} IN (
          SELECT ${ident(ic.shipId)}
          FROM ${ident(config.tables.bucketIndex)}
          WHERE ${ident(ic.bucketStart)} >= ${sqlDateParam("start")}
            AND ${ident(ic.bucketStart)} < ${sqlDateParam("end")}
            AND ${ident(ic.maxLng)} >= {west: Float64}
            AND ${ident(ic.minLng)} <= {east: Float64}
            AND ${ident(ic.maxLat)} >= {south: Float64}
            AND ${ident(ic.minLat)} <= {north: Float64}
          GROUP BY ${ident(ic.shipId)}
          ORDER BY count() DESC
          LIMIT 5000
        )
      GROUP BY shipId, bucket
      ORDER BY time ASC
      LIMIT {limit: UInt32}`;
    return clickhouse(query, { start, end, ...bbox, bucketSeconds, limit: config.query.maxGlobalSegmentPoints || 50000 });
  }
};

function compareTime(a, b) {
  return String(a || "").localeCompare(String(b || ""));
}

function rebuildLatestCacheItems() {
  latestCache.items = Array.from(latestCache.byShip.values()).sort((a, b) => String(a.shipId).localeCompare(String(b.shipId)));
  latestCache.rows = latestCache.items.map(pointToRealtimeRow);
}

function pointToRealtimeRow(item) {
  return [
    item.shipId,
    item.shipName || "",
    Number(item.lng),
    Number(item.lat),
    Number(item.speed || 0),
    Number(item.heading || 0),
    item.time,
    Number(item.isAis || 0)
  ];
}

function upsertLatestCache(items) {
  for (const item of items) {
    const previous = latestCache.byShip.get(item.shipId);
    if (!previous || compareTime(item.time, previous.time) >= 0) {
      latestCache.byShip.set(item.shipId, item);
    }
    if (!latestCache.watermark || compareTime(item.time, latestCache.watermark) > 0) {
      latestCache.watermark = item.time;
    }
  }
  rebuildLatestCacheItems();
}

async function warmLatestCache(timeWindow = null) {
  const windowValue = timeWindow || (await realtimeWindowFromParams(new URLSearchParams()));
  if (!windowValue.start || !windowValue.end) return;
  if (latestCache.warming && sameRealtimeWindow(latestCache.window, windowValue)) return latestCache.warmPromise;

  const seq = latestCache.warmSeq + 1;
  latestCache.warmSeq = seq;
  latestCache.warming = true;
  latestCache.ready = false;
  latestCache.lastWarmError = "";
  latestCache.window = windowValue;
  latestCache.byShip.clear();
  latestCache.items = [];
  latestCache.rows = [];
  latestCache.watermark = "";

  latestCache.warmPromise = (async () => {
    try {
      const items = await repo.latest({
        start: windowValue.start,
        end: windowValue.end,
        limit: config.query.maxLatestShips || 300000,
        capToPage: false
      });
      if (seq !== latestCache.warmSeq) return;
      upsertLatestCache(items);
      latestCache.ready = true;
      console.log(`latest cache warmed: ${latestCache.items.length} ships, window=${windowValue.start}..${windowValue.end}, watermark=${latestCache.watermark}`);
    } catch (error) {
      if (seq !== latestCache.warmSeq) return;
      latestCache.lastWarmError = error.message || String(error);
      console.error(`latest cache warm failed: ${latestCache.lastWarmError}`);
    } finally {
      if (seq === latestCache.warmSeq) latestCache.warming = false;
    }
  })();

  return latestCache.warmPromise;
}

async function pollRealtimeDeltas() {
  if (latestCache.polling) return;
  if (!latestCache.ready || !latestCache.watermark) return;
  if (!clients.size) return;
  latestCache.polling = true;
  try {
    const since = latestCache.watermark;
    const items = await repo.deltas(since, latestCache.window);
    if (!items.length) return;
    upsertLatestCache(items);
    const payload = { type: "delta", since, window: latestCache.window, items };
    for (const socket of clients) {
      if (!socket.destroyed) wsSend(socket, payload);
    }
  } catch (error) {
    const payload = { type: "error", message: error.message || String(error) };
    for (const socket of clients) {
      if (!socket.destroyed) wsSend(socket, payload);
    }
  } finally {
    latestCache.polling = false;
  }
}

function startRealtimePollLoop() {
  const intervalMs = Math.max(1, Number(config.query.realtimePollSeconds || 5)) * 1000;
  setTimeout(async () => {
    try {
      await pollRealtimeDeltas();
    } finally {
      startRealtimePollLoop();
    }
  }, intervalMs);
}

function startMemoryLog() {
  if (!config.query.logMemorySeconds) return;
  setInterval(() => {
    const memory = process.memoryUsage();
    const mb = (value) => Math.round(value / 1024 / 1024);
    console.log(`memory rss=${mb(memory.rss)}MB heap=${mb(memory.heapUsed)}/${mb(memory.heapTotal)}MB clients=${clients.size}`);
  }, Math.max(10, Number(config.query.logMemorySeconds)) * 1000);
}

async function routeApi(req, res, url) {
  if (url.pathname === "/api/config/map") {
    return json(res, {
      coordinateSystem: config.map.coordinateSystem,
      defaultCenter: config.map.defaultCenter,
      defaultZoom: config.map.defaultZoom,
      maxMultiShips: config.query.maxMultiShips,
      amapKey: process.env.VITE_AMAP_KEY || "",
      amapSecurityJsCode: process.env.VITE_AMAP_SECURITY_JS_CODE || ""
    });
  }
  if (url.pathname === "/api/realtime/latest") {
    const timeWindow = await realtimeWindowFromParams(url.searchParams);
    if (!timeWindow.start || !timeWindow.end) {
      return json(res, {
        source: "clickhouse",
        compact: true,
        fields: ["shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis"],
        ready: false,
        window: timeWindow,
        watermark: "",
        items: [],
        nextCursor: "",
        hasMore: false
      });
    }
    if (latestCache.ready && sameRealtimeWindow(latestCache.window, timeWindow)) {
      return json(res, {
        source: "memory",
        compact: true,
        fields: ["shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis"],
        ready: true,
        window: latestCache.window,
        watermark: latestCache.watermark,
        items: latestCache.rows,
        nextCursor: "",
        hasMore: false
      });
    }
    await warmLatestCache(timeWindow);
    if (latestCache.ready && sameRealtimeWindow(latestCache.window, timeWindow)) {
      return json(res, {
        source: "memory",
        compact: true,
        fields: ["shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis"],
        ready: true,
        window: latestCache.window,
        watermark: latestCache.watermark,
        items: latestCache.rows,
        nextCursor: "",
        hasMore: false
      });
    }
    const items = await repo.latest({
      start: timeWindow.start,
      end: timeWindow.end,
      limit: config.query.maxLatestShips || 300000,
      capToPage: false
    });
    const watermark = items.reduce((max, item) => (compareTime(item.time, max) > 0 ? item.time : max), "");
    return json(res, {
      source: "clickhouse",
      compact: true,
      fields: ["shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis"],
      ready: false,
      warming: latestCache.warming,
      window: timeWindow,
      watermark,
      items: items.map(pointToRealtimeRow),
      nextCursor: "",
      hasMore: false
    });
  }
  if (url.pathname === "/api/analysis/density") {
    const time = timeWindowFromParams(url.searchParams);
    return json(res, { items: await repo.density({ ...time, bbox: bboxFromParams(url.searchParams), zoom: zoomFromParams(url.searchParams) }) });
  }
  if (url.pathname === "/api/tracks/single") {
    const time = timeWindowFromParams(url.searchParams);
    const shipId = url.searchParams.get("shipId");
    if (!shipId) throw new Error("shipId parameter is required");
    return json(res, { items: await repo.trackRows({ shipIds: [shipId], ...time, zoom: zoomFromParams(url.searchParams), mode: "single" }) });
  }
  if (url.pathname === "/api/tracks/candidates") {
    const time = timeWindowFromParams(url.searchParams);
    return json(res, { items: await repo.candidates({ ...time, bbox: bboxFromParams(url.searchParams), limit: Number(url.searchParams.get("limit") || config.query.maxMultiShips) }) });
  }
  if (url.pathname === "/api/tracks/multi" && req.method === "POST") {
    const body = await readJson(req);
    const shipIds = Array.isArray(body.shipIds) ? body.shipIds.slice(0, config.query.maxMultiShips) : [];
    if (!shipIds.length) throw new Error("shipIds parameter is required");
    return json(res, {
      items: await repo.trackRows({
        shipIds,
        start: body.start,
        end: body.end,
        zoom: Number(body.zoom || config.map.defaultZoom),
        bbox: body.bbox,
        mode: "multi"
      })
    });
  }
  if (url.pathname === "/api/tracks/global-segment") {
    const time = timeWindowFromParams(url.searchParams);
    return json(res, { items: await repo.globalSegment({ ...time, bbox: bboxFromParams(url.searchParams), zoom: zoomFromParams(url.searchParams) }) });
  }
  notFound(res);
}

function readJson(req) {
  return new Promise((resolveJson, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1024 * 1024) reject(new Error("request body is too large"));
    });
    req.on("end", () => resolveJson(body ? JSON.parse(body) : {}));
    req.on("error", reject);
  });
}

function json(res, body, status = 200) {
  const text = JSON.stringify(body);
  const encoded = gzipSync(Buffer.from(text));
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Encoding": "gzip",
    "Vary": "Accept-Encoding",
    "Access-Control-Allow-Origin": "*"
  });
  res.end(encoded);
}

function errorStatus(error) {
  const message = error?.message || "";
  if (message.includes("ClickHouse connection failed")) return 503;
  return 500;
}

function notFound(res) {
  json(res, { error: "Not found" }, 404);
}

function serveStatic(req, res, url) {
  const publicRoot = resolve(root, "public");
  const requested = url.pathname === "/" ? "/index.html" : decodeURIComponent(url.pathname);
  const filePath = resolve(join(publicRoot, requested));
  if (!filePath.startsWith(publicRoot) || !existsSync(filePath)) return notFound(res);
  const type = { ".html": "text/html", ".css": "text/css", ".js": "text/javascript", ".json": "application/json" }[extname(filePath)] || "application/octet-stream";
  res.writeHead(200, { "Content-Type": `${type}; charset=utf-8` });
  createReadStream(filePath).pipe(res);
}

function wsAcceptKey(key) {
  return createHash("sha1").update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest("base64");
}

function wsSend(socket, payload) {
  const data = Buffer.from(JSON.stringify(payload));
  let header;
  if (data.length < 126) {
    header = Buffer.from([0x81, data.length]);
  } else if (data.length <= 65535) {
    header = Buffer.from([0x81, 126, data.length >> 8, data.length & 255]);
  } else {
    header = Buffer.allocUnsafe(10);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(data.length), 2);
  }
  try {
    socket.write(Buffer.concat([header, data]));
  } catch {
    clients.delete(socket);
    socket.destroy();
  }
}

async function handleWebSocket(req, socket) {
  const key = req.headers["sec-websocket-key"];
  socket.write([
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    `Sec-WebSocket-Accept: ${wsAcceptKey(key)}`,
    "",
    ""
  ].join("\r\n"));
  clients.add(socket);
  wsSend(socket, {
    type: "ready",
    source: "memory",
    cacheReady: latestCache.ready,
    window: latestCache.window,
    since: latestCache.watermark
  });
  pollRealtimeDeltas();
  socket.on("close", () => {
    clients.delete(socket);
  });
  socket.on("error", () => {
    clients.delete(socket);
  });
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://${req.headers.host}`);
    if (req.method === "OPTIONS") return json(res, {});
    if (url.pathname.startsWith("/api/")) return await routeApi(req, res, url);
    serveStatic(req, res, url);
  } catch (error) {
    json(res, { error: error.message || "server error" }, errorStatus(error));
  }
});

warmLatestCache();
startRealtimePollLoop();
startMemoryLog();

server.on("upgrade", (req, socket) => {
  if ((req.url || "").startsWith("/ws/realtime")) handleWebSocket(req, socket);
  else socket.destroy();
});

const port = Number(process.env.PORT || 3001);
server.listen(port, "0.0.0.0", () => {
  console.log(`Ship track replay server started: http://127.0.0.1:${port}`);
});

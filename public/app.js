const state = {
  mode: "realtime",
  config: null,
  map: null,
  latest: [],
  realtimeWindow: null,
  density: [],
  trackPoints: [],
  candidates: [],
  selectedShips: [],
  playing: false,
  playIndex: 0,
  speed: 2,
  stats: {
    databaseTrackPoints: 0,
    databaseShips: 0,
    windowTrackPoints: 0,
    windowShips: 0,
    memoryShips: 0,
    viewportShips: 0,
    summaryTimer: null,
    viewportTimer: null,
    summarySeq: 0,
    viewportSeq: 0
  },
  layers: {
    heat: null,
    trackSource: null,
    trackLayer: null,
    markerSource: null,
    markerLayer: null,
    rectangleSource: null,
    rectangleLayer: null,
    drawBoxInteraction: null,
    lines: [],
    lineQueue: [],
    lineTimer: null,
    realtimeRenderTimer: null,
    realtimeCanvas: null,
    realtimeCtx: null,
    realtimeFrame: null,
    realtimeRenderSeq: 0,
    realtimePanFrame: null,
    realtimeRenderAnchor: null,
    realtimeRenderAnchorPixel: null,
    realtimeHits: [],
    realtimeHitGrid: new Map(),
    realtimeVisibleCount: 0,
    realtimeConfirmShipId: "",
    markers: [],
    rectangle: null
  },
  realtimeStore: {
    items: [],
    byShip: new Map(),
    grid: new Map(),
    itemCells: new Map(),
    cellSize: 0.05
  }
};

const REALTIME_INDEX_CELL_SIZE = 0.05;
const REALTIME_HIT_CELL_SIZE = 32;
const REALTIME_HIT_RADIUS = 12;
const REALTIME_DRAW_BUDGET_MS = 8;
const REALTIME_DRAW_BATCH = 2500;
const RADAR_SHIP_ID_PATTERN = /^\d+(?:[-_]\d+)+$/;

const AMAP_TILE_URL = "https://webrd0{sub}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}";

const $ = (id) => document.getElementById(id);

function setStatus(text) {
  $("status").textContent = text;
}

function showError(text) {
  const panel = $("error");
  panel.textContent = text;
  panel.classList.remove("hidden");
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    const text = await response.text();
    let message = text;
    try {
      message = JSON.parse(text).error || text;
    } catch {}
    throw new Error(message);
  }
  return response.json();
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const text = await response.text();
    let message = text;
    try {
      message = JSON.parse(text).error || text;
    } catch {}
    throw new Error(message);
  }
  return response.json();
}

function outOfChina(lng, lat) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x, y) {
  let ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3;
  ret += ((20 * Math.sin(y * Math.PI) + 40 * Math.sin((y / 3) * Math.PI)) * 2) / 3;
  ret += ((160 * Math.sin((y / 12) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30)) * 2) / 3;
  return ret;
}

function transformLng(x, y) {
  let ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3;
  ret += ((20 * Math.sin(x * Math.PI) + 40 * Math.sin((x / 3) * Math.PI)) * 2) / 3;
  ret += ((150 * Math.sin((x / 12) * Math.PI) + 300 * Math.sin((x / 30) * Math.PI)) * 2) / 3;
  return ret;
}

function wgs84ToGcj02(lng, lat) {
  if (outOfChina(lng, lat)) return [lng, lat];
  const a = 6378245.0;
  const ee = 0.006693421622965943;
  let dLat = transformLat(lng - 105, lat - 35);
  let dLng = transformLng(lng - 105, lat - 35);
  const radLat = (lat / 180) * Math.PI;
  let magic = Math.sin(radLat);
  magic = 1 - ee * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180) / (((a * (1 - ee)) / (magic * sqrtMagic)) * Math.PI);
  dLng = (dLng * 180) / ((a / sqrtMagic) * Math.cos(radLat) * Math.PI);
  return [lng + dLng, lat + dLat];
}

function gcj02ToWgs84(lng, lat) {
  const [gLng, gLat] = wgs84ToGcj02(lng, lat);
  return [lng * 2 - gLng, lat * 2 - gLat];
}

function toMapPoint(point) {
  return state.config?.coordinateSystem === "wgs84" ? wgs84ToGcj02(Number(point.lng), Number(point.lat)) : [Number(point.lng), Number(point.lat)];
}

function toMapCoordinate(point) {
  return ol.proj.fromLonLat(toMapPoint(point));
}

function toQueryBBoxFromLngLat(sw, ne) {
  const qsw = state.config?.coordinateSystem === "wgs84" ? gcj02ToWgs84(sw[0], sw[1]) : sw;
  const qne = state.config?.coordinateSystem === "wgs84" ? gcj02ToWgs84(ne[0], ne[1]) : ne;
  return { west: qsw[0], south: qsw[1], east: qne[0], north: qne[1] };
}

function currentDataBBox() {
  if (!state.map) return null;
  const size = state.map.getSize();
  if (!size) return null;
  const extent = state.map.getView().calculateExtent(size);
  return toQueryBBoxFromLngLat(ol.proj.toLonLat([extent[0], extent[1]]), ol.proj.toLonLat([extent[2], extent[3]]));
}

function getMapZoom() {
  return Math.round(state.map?.getView().getZoom() || state.config?.defaultZoom || 0);
}

function qs(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => search.set(key, String(value)));
  return search.toString();
}

function toIso(value) {
  return new Date(value).toISOString();
}

function toLocalDatetime(date) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function syncRealtimeWindowInputs(windowValue) {
  if (!windowValue?.start || !windowValue?.end) return;
  $("realtime-start").value = toLocalDatetime(new Date(String(windowValue.start).replace(" ", "T")));
  $("realtime-end").value = toLocalDatetime(new Date(String(windowValue.end).replace(" ", "T")));
}

function realtimeWindowQuery() {
  const start = $("realtime-start")?.value;
  const end = $("realtime-end")?.value;
  if (!start || !end) return "";
  return qs({ start: toIso(start), end: toIso(end) });
}

function sameWindow(a, b) {
  return String(a?.start || "") === String(b?.start || "") && String(a?.end || "") === String(b?.end || "");
}

function groupByShip(points) {
  const grouped = new Map();
  points.forEach((point) => {
    if (!grouped.has(point.shipId)) grouped.set(point.shipId, []);
    grouped.get(point.shipId).push(point);
  });
  grouped.forEach((items) => items.sort((a, b) => String(a.time).localeCompare(String(b.time))));
  return grouped;
}

function normalizeRealtimeItem(item) {
  if (Array.isArray(item)) {
    item = {
      shipId: item[0],
      shipName: item[1],
      lng: item[2],
      lat: item[3],
      speed: item[4],
      heading: item[5],
      time: item[6],
      isAis: Number(item[7] || 0)
    };
  }
  const lng = Number(item.lng);
  const lat = Number(item.lat);
  const [mapLng, mapLat] = toMapPoint({ lng, lat });
  return {
    shipId: String(item.shipId || ""),
    shipName: item.shipName || item.shipId || "",
    lng,
    lat,
    mapLng,
    mapLat,
    speed: Number(item.speed || 0),
    heading: Number(item.heading || 0),
    time: item.time || "",
    isAis: RADAR_SHIP_ID_PATTERN.test(String(item.shipId || "")) ? 0 : Number(item.isAis || 0)
  };
}

function realtimeCellKey(lng, lat, cellSize = state.realtimeStore.cellSize) {
  return `${Math.floor(lng / cellSize)}:${Math.floor(lat / cellSize)}`;
}

function addRealtimeIndexItem(index, item) {
  const key = realtimeCellKey(item.lng, item.lat);
  if (!state.realtimeStore.grid.has(key)) state.realtimeStore.grid.set(key, []);
  state.realtimeStore.grid.get(key).push(index);
  state.realtimeStore.itemCells.set(item.shipId, key);
}

function removeRealtimeIndexItem(shipId, index) {
  const key = state.realtimeStore.itemCells.get(shipId);
  if (!key) return;
  const bucket = state.realtimeStore.grid.get(key);
  if (!bucket) return;
  const pos = bucket.indexOf(index);
  if (pos >= 0) bucket.splice(pos, 1);
  if (!bucket.length) state.realtimeStore.grid.delete(key);
  state.realtimeStore.itemCells.delete(shipId);
}

function buildRealtimeStore(items) {
  const store = {
    items: [],
    byShip: new Map(),
    grid: new Map(),
    itemCells: new Map(),
    cellSize: REALTIME_INDEX_CELL_SIZE
  };
  for (const raw of items || []) {
    const item = normalizeRealtimeItem(raw);
    if (!item.shipId || !Number.isFinite(item.lng) || !Number.isFinite(item.lat)) continue;
    const previousIndex = store.byShip.get(item.shipId);
    if (previousIndex !== undefined) {
      const previous = store.items[previousIndex];
      if (String(previous.time || "").localeCompare(String(item.time || "")) > 0) continue;
      store.items[previousIndex] = item;
      continue;
    }
    const index = store.items.length;
    store.items.push(item);
    store.byShip.set(item.shipId, index);
  }
  store.items.forEach((item, index) => {
    const key = realtimeCellKey(item.lng, item.lat, store.cellSize);
    if (!store.grid.has(key)) store.grid.set(key, []);
    store.grid.get(key).push(index);
    store.itemCells.set(item.shipId, key);
  });
  state.realtimeStore = store;
  state.latest = store.items;
  state.stats.memoryShips = store.items.length;
}

function upsertRealtimeItems(items) {
  if (!state.realtimeStore.items.length) {
    buildRealtimeStore(items);
    return;
  }
  for (const raw of items || []) {
    const item = normalizeRealtimeItem(raw);
    if (!item.shipId || !Number.isFinite(item.lng) || !Number.isFinite(item.lat)) continue;
    const index = state.realtimeStore.byShip.get(item.shipId);
    if (index === undefined) {
      const nextIndex = state.realtimeStore.items.length;
      state.realtimeStore.items.push(item);
      state.realtimeStore.byShip.set(item.shipId, nextIndex);
      addRealtimeIndexItem(nextIndex, item);
      continue;
    }
    const previous = state.realtimeStore.items[index];
    if (String(previous.time || "").localeCompare(String(item.time || "")) > 0) continue;
    const previousCell = state.realtimeStore.itemCells.get(item.shipId);
    const nextCell = realtimeCellKey(item.lng, item.lat);
    state.realtimeStore.items[index] = item;
    if (previousCell !== nextCell) {
      removeRealtimeIndexItem(item.shipId, index);
      addRealtimeIndexItem(index, item);
    }
  }
  state.latest = state.realtimeStore.items;
  state.stats.memoryShips = state.realtimeStore.items.length;
}

function queryVisibleShipIndices() {
  const bbox = currentDataBBox();
  const store = state.realtimeStore;
  if (!bbox || !store.grid.size) {
    const indices = [];
    for (let index = 0; index < store.items.length; index += 1) {
      if (matchesRealtimeType(store.items[index])) indices.push(index);
    }
    return indices;
  }
  const cellSize = store.cellSize;
  const westCell = Math.floor(bbox.west / cellSize);
  const eastCell = Math.floor(bbox.east / cellSize);
  const southCell = Math.floor(bbox.south / cellSize);
  const northCell = Math.floor(bbox.north / cellSize);
  const visible = [];
  for (let x = westCell; x <= eastCell; x += 1) {
    for (let y = southCell; y <= northCell; y += 1) {
      const bucket = store.grid.get(`${x}:${y}`);
      if (!bucket) continue;
      for (const index of bucket) {
        const item = store.items[index];
        if (
          matchesRealtimeType(item) &&
          item.lng >= bbox.west &&
          item.lng <= bbox.east &&
          item.lat >= bbox.south &&
          item.lat <= bbox.north
        ) {
          visible.push(index);
        }
      }
    }
  }
  return visible;
}

function scheduleRealtimeRender(delay = 120) {
  if (state.layers.realtimeRenderTimer) clearTimeout(state.layers.realtimeRenderTimer);
  state.layers.realtimeRenderTimer = setTimeout(() => {
    state.layers.realtimeRenderTimer = null;
    if (state.mode === "realtime" && state.latest.length) renderRealtime();
  }, delay);
}

function syncRealtimeCanvasDuringMove() {
  if (state.mode !== "realtime" || !state.map || !state.layers.realtimeCanvas) return;
  if (!state.layers.realtimeRenderAnchor || !state.layers.realtimeRenderAnchorPixel) return;
  if (state.layers.realtimePanFrame) return;
  state.layers.realtimePanFrame = requestAnimationFrame(() => {
    state.layers.realtimePanFrame = null;
    if (state.mode !== "realtime" || !state.layers.realtimeCanvas || !state.layers.realtimeRenderAnchor) return;
    const currentPixel = state.map.getPixelFromCoordinate(state.layers.realtimeRenderAnchor);
    if (!currentPixel) return;
    const dx = Math.round(currentPixel[0] - state.layers.realtimeRenderAnchorPixel[0]);
    const dy = Math.round(currentPixel[1] - state.layers.realtimeRenderAnchorPixel[1]);
    state.layers.realtimeCanvas.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
  });
}

function normalizeRealtimeItems(data) {
  if (!data.compact) return data.items || [];
  return (data.items || []).map((row) => ({
    shipId: row[0],
    shipName: row[1],
    lng: row[2],
    lat: row[3],
    speed: row[4],
    heading: row[5],
    time: row[6],
    isAis: Number(row[7] || 0)
  }));
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function realtimeTypeFilter() {
  return $("realtime-type")?.value || "ais";
}

function matchesRealtimeType(item) {
  const type = realtimeTypeFilter();
  if (type === "all") return true;
  if (type === "ais") return Number(item.isAis) === 1;
  if (type === "nonais") return Number(item.isAis) === 0;
  return true;
}

function metricNumber(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toLocaleString() : "0";
}

function updateMetrics() {
  $("metric-db-points").textContent = metricNumber(state.stats.databaseTrackPoints);
  $("metric-db-ships").textContent = metricNumber(state.stats.databaseShips);
  $("metric-window-points").textContent = metricNumber(state.stats.windowTrackPoints);
  $("metric-window-ships").textContent = metricNumber(state.stats.windowShips);
  $("metric-memory-ships").textContent = metricNumber(state.stats.memoryShips);
  $("metric-viewport-ships").textContent = metricNumber(state.stats.viewportShips);
  $("progress").max = Math.max(0, state.trackPoints.length - 1);
  $("progress").value = state.playIndex;
  $("active-time").textContent = state.trackPoints[state.playIndex]?.time || "--";
}

function activeStatsWindow() {
  if (state.mode === "realtime") {
    const start = $("realtime-start")?.value;
    const end = $("realtime-end")?.value;
    if (start && end) return { start: toIso(start), end: toIso(end) };
    if (state.realtimeWindow?.start && state.realtimeWindow?.end) {
      return { start: new Date(String(state.realtimeWindow.start).replace(" ", "T")).toISOString(), end: new Date(String(state.realtimeWindow.end).replace(" ", "T")).toISOString() };
    }
  }
  const start = $("start")?.value;
  const end = $("end")?.value;
  if (!start || !end) return null;
  return { start: toIso(start), end: toIso(end) };
}

async function refreshViewportStats() {
  const bbox = currentDataBBox();
  const windowValue = activeStatsWindow();
  if (!bbox || !windowValue) return;
  const seq = ++state.stats.viewportSeq;
  const params = qs({ ...windowValue, ...bbox });
  const data = await getJson(`/api/stats/viewport?${params}`);
  if (seq !== state.stats.viewportSeq) return;
  state.stats.viewportShips = Number(data.viewportShips || 0);
  updateMetrics();
}

async function refreshRealtimeSummary() {
  const windowValue = activeStatsWindow();
  if (!windowValue) return;
  const seq = ++state.stats.summarySeq;
  const params = qs(windowValue);
  const data = await getJson(`/api/stats/realtime-summary?${params}`);
  if (seq !== state.stats.summarySeq) return;
  state.stats.databaseTrackPoints = Number(data.databaseTrackPoints || 0);
  state.stats.databaseShips = Number(data.databaseShips || 0);
  state.stats.windowTrackPoints = Number(data.windowTrackPoints || 0);
  state.stats.windowShips = Number(data.windowShips || 0);
  updateMetrics();
}

function scheduleViewportStats(delay = 260) {
  if (state.stats.viewportTimer) clearTimeout(state.stats.viewportTimer);
  state.stats.viewportTimer = setTimeout(() => {
    state.stats.viewportTimer = null;
    refreshViewportStats().catch((error) => setStatus("统计刷新失败: " + error.message));
  }, delay);
}

function scheduleRealtimeSummary(delay = 260) {
  if (state.stats.summaryTimer) clearTimeout(state.stats.summaryTimer);
  state.stats.summaryTimer = setTimeout(() => {
    state.stats.summaryTimer = null;
    refreshRealtimeSummary().catch((error) => setStatus("统计刷新失败: " + error.message));
  }, delay);
}

function switchMode(mode) {
  const previousMode = state.mode;
  state.mode = mode;
  document.querySelectorAll(".nav").forEach((button) => button.classList.toggle("active", button.dataset.mode === mode));
  document.querySelectorAll(".mode-panel").forEach((panel) => panel.classList.add("hidden"));
  $(`${mode}-panel`)?.classList.remove("hidden");
  $("time-section").classList.toggle("hidden", mode === "realtime");
  $("player").classList.toggle("hidden", !["single", "multi", "global"].includes(mode));
  $("panel-title").textContent = {
    realtime: "实时位置展示",
    analysis: "历史态势分析",
    single: "单船轨迹回放",
    multi: "多船轨迹回放",
    global: "全域轨迹回放"
  }[mode];
  clearLayers();
  if (mode === "realtime") renderRealtime();
  if (mode === "analysis") renderHeat();
  if (["single", "multi", "global"].includes(mode)) renderTracks();
  scheduleViewportStats(0);
}

function clearLayers() {
  if (!state.map) return;
  if (state.layers.lineTimer) {
    clearTimeout(state.layers.lineTimer);
    state.layers.lineTimer = null;
  }
  if (state.layers.heat) {
    state.map.removeLayer(state.layers.heat);
    state.layers.heat = null;
  }
  state.layers.trackSource?.clear();
  state.layers.markerSource?.clear();
  if (state.mode !== "multi") state.layers.rectangleSource?.clear();
  if (state.mode !== "realtime") {
    clearRealtimeShipConfirm();
    clearRealtimeCanvas();
  }
  state.layers.lines = [];
  state.layers.markers = [];
}

function ensureRealtimeCanvasLayer() {
  const wrap = document.querySelector(".map-wrap");
  if (!wrap) return null;
  if (!state.layers.realtimeCanvas) {
    const canvas = document.createElement("canvas");
    canvas.id = "realtime-canvas";
    canvas.className = "realtime-canvas";
    wrap.insertBefore(canvas, $("error"));
    state.layers.realtimeCanvas = canvas;
    state.layers.realtimeCtx = canvas.getContext("2d", { alpha: true });
  }
  const canvas = state.layers.realtimeCanvas;
  const rect = $("map").getBoundingClientRect();
  const ratio = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
  const width = Math.max(1, Math.round(rect.width));
  const height = Math.max(1, Math.round(rect.height));
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
  }
  canvas.classList.remove("hidden");
  const ctx = state.layers.realtimeCtx;
  if (!ctx) return null;
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { canvas, ctx, width, height };
}

function clearRealtimeCanvas() {
  if (state.layers.realtimeFrame) {
    cancelAnimationFrame(state.layers.realtimeFrame);
    state.layers.realtimeFrame = null;
  }
  state.layers.realtimeRenderSeq += 1;
  const canvas = state.layers.realtimeCanvas;
  const ctx = state.layers.realtimeCtx;
  if (canvas && ctx) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    canvas.style.transform = "";
    canvas.classList.toggle("hidden", state.mode !== "realtime");
  }
  if (state.layers.realtimePanFrame) {
    cancelAnimationFrame(state.layers.realtimePanFrame);
    state.layers.realtimePanFrame = null;
  }
  state.layers.realtimeRenderAnchor = null;
  state.layers.realtimeRenderAnchorPixel = null;
  state.layers.realtimeHits = [];
  state.layers.realtimeHitGrid = new Map();
  state.layers.realtimeVisibleCount = 0;
}

function canvasShipColor(item) {
  if (Number(item.isAis) === 0) return "#6b7280";
  return Number(item.speed) > 8 ? "#2563eb" : "#168a52";
}

function drawShipTriangle(ctx, x, y, heading, color) {
  const size = 14;
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(((Number(heading) || 0) * Math.PI) / 180);

  const drawHull = (scale) => {
    ctx.beginPath();
    ctx.moveTo(0, -size * scale);
    ctx.lineTo(size * 0.56 * scale, size * 0.92 * scale);
    ctx.lineTo(0, size * 0.58 * scale);
    ctx.lineTo(-size * 0.56 * scale, size * 0.92 * scale);
    ctx.closePath();
  };

  ctx.shadowColor = "rgba(15, 23, 42, 0.22)";
  ctx.shadowBlur = 5;
  ctx.shadowOffsetY = 2;
  drawHull(1.12);
  ctx.fillStyle = "rgba(255,255,255,0.96)";
  ctx.fill();

  ctx.shadowColor = "transparent";
  drawHull(1);
  ctx.fillStyle = color;
  ctx.fill();
  ctx.strokeStyle = "rgba(15,23,42,0.5)";
  ctx.lineWidth = 0.9;
  ctx.stroke();

  ctx.beginPath();
  ctx.moveTo(0, -size * 0.62);
  ctx.lineTo(0, size * 0.38);
  ctx.strokeStyle = "rgba(255,255,255,0.78)";
  ctx.lineWidth = 1.1;
  ctx.lineCap = "round";
  ctx.stroke();
  ctx.restore();
}

function shipMarkerStyle(heading) {
  return new ol.style.Style({
    image: new ol.style.RegularShape({
      points: 3,
      radius: 12,
      rotation: ((heading || 0) * Math.PI) / 180,
      rotateWithView: true,
      fill: new ol.style.Fill({ color: "#0f62c7" }),
      stroke: new ol.style.Stroke({ color: "rgba(255,255,255,0.96)", width: 2 })
    })
  });
}

function lineAndRectangleStyle(color, width, fillOpacity = 0) {
  return new ol.style.Style({
    stroke: new ol.style.Stroke({ color, width }),
    fill: new ol.style.Fill({ color: `rgba(37, 99, 235, ${fillOpacity})` })
  });
}

function pixelFromShip(item) {
  const pixel = state.map.getPixelFromCoordinate(ol.proj.fromLonLat([item.mapLng, item.mapLat]));
  return pixel ? { x: pixel[0], y: pixel[1] } : null;
}

function addRealtimeHit(hit) {
  state.layers.realtimeHits.push(hit);
  const key = `${Math.floor(hit.x / REALTIME_HIT_CELL_SIZE)}:${Math.floor(hit.y / REALTIME_HIT_CELL_SIZE)}`;
  if (!state.layers.realtimeHitGrid.has(key)) state.layers.realtimeHitGrid.set(key, []);
  state.layers.realtimeHitGrid.get(key).push(hit);
}

function renderRealtimeCanvas(indices) {
  const layer = ensureRealtimeCanvasLayer();
  if (!layer) return false;
  const { ctx, width, height } = layer;
  if (state.layers.realtimeFrame) cancelAnimationFrame(state.layers.realtimeFrame);
  layer.canvas.style.transform = "";
  state.layers.realtimeRenderAnchor = state.map.getView().getCenter();
  state.layers.realtimeRenderAnchorPixel = state.map.getPixelFromCoordinate(state.layers.realtimeRenderAnchor);
  const seq = (state.layers.realtimeRenderSeq += 1);
  ctx.clearRect(0, 0, width, height);
  state.layers.realtimeHits = [];
  state.layers.realtimeHitGrid = new Map();
  state.layers.realtimeVisibleCount = indices.length;
  let cursor = 0;
  const drawBatch = () => {
    if (seq !== state.layers.realtimeRenderSeq || state.mode !== "realtime") return;
    const startedAt = performance.now();
    let drawn = 0;
    while (cursor < indices.length) {
      const item = state.realtimeStore.items[indices[cursor]];
      cursor += 1;
      if (!item) continue;
      const pixel = pixelFromShip(item);
      if (!pixel) continue;
      if (pixel.x < -20 || pixel.x > width + 20 || pixel.y < -20 || pixel.y > height + 20) continue;
      drawShipTriangle(ctx, pixel.x, pixel.y, item.heading, canvasShipColor(item));
      addRealtimeHit({ x: pixel.x, y: pixel.y, index: indices[cursor - 1] });
      drawn += 1;
      if (drawn >= REALTIME_DRAW_BATCH || performance.now() - startedAt >= REALTIME_DRAW_BUDGET_MS) break;
    }
    if (cursor < indices.length) {
      state.layers.realtimeFrame = requestAnimationFrame(drawBatch);
    } else {
      state.layers.realtimeFrame = null;
    }
  };
  drawBatch();
  return true;
}

function renderRealtime() {
  if (!state.map || state.mode !== "realtime") return;
  const startedAt = performance.now();
  const indices = queryVisibleShipIndices();
  try {
    renderRealtimeCanvas(indices);
  } catch (error) {
    console.warn("Realtime canvas failed", error);
    clearRealtimeCanvas();
    showError("Realtime canvas failed: " + error.message);
  }
  const elapsed = Math.round(performance.now() - startedAt);
  setStatus(`内存缓存 ${state.latest.length.toLocaleString()} 艘，当前视野 ${indices.length.toLocaleString()} 艘，耗时 ${elapsed} ms`);
}

function normalizeShipInfoData(data) {
  return {
    id: data.id || data.shipId,
    name: data.name || data.shipName || data.shipId,
    speed: data.speed,
    heading: data.heading,
    time: data.time,
    isAis: data.isAis,
    lnglat: data.lnglat || [data.mapLng, data.mapLat]
  };
}

function hitTestShip(pixel) {
  if (!pixel) return null;
  const px = Number(Array.isArray(pixel) ? pixel[0] : pixel.x);
  const py = Number(Array.isArray(pixel) ? pixel[1] : pixel.y);
  const cellX = Math.floor(px / REALTIME_HIT_CELL_SIZE);
  const cellY = Math.floor(py / REALTIME_HIT_CELL_SIZE);
  let best = null;
  let bestDistance = REALTIME_HIT_RADIUS * REALTIME_HIT_RADIUS;
  for (let x = cellX - 1; x <= cellX + 1; x += 1) {
    for (let y = cellY - 1; y <= cellY + 1; y += 1) {
      const bucket = state.layers.realtimeHitGrid.get(`${x}:${y}`);
      if (!bucket) continue;
      for (const hit of bucket) {
        const dx = hit.x - px;
        const dy = hit.y - py;
        const distance = dx * dx + dy * dy;
        if (distance <= bestDistance) {
          bestDistance = distance;
          best = state.realtimeStore.items[hit.index];
        }
      }
    }
  }
  return best;
}

function handleRealtimePointerMove(event) {
  if (state.mode !== "realtime") return;
  if (state.layers.realtimeConfirmShipId) return;
  const ship = hitTestShip(event.pixel);
  if (ship) showShipInfo(ship);
  else if (state.layers.hoverShipId) hideShipInfo();
}

function handleRealtimeClick(event) {
  if (state.mode !== "realtime") return;
  const ship = hitTestShip(event.pixel);
  if (ship) showRealtimeShipConfirm(ship);
}

function handleRealtimeDomClick(event) {
  if (state.mode !== "realtime") return;
  if (event.target.closest?.("#ship-hover-card")) return;
  const mapEl = $("map");
  if (!mapEl) return;
  const rect = mapEl.getBoundingClientRect();
  const x = event.clientX - rect.left;
  const y = event.clientY - rect.top;
  if (x < 0 || y < 0 || x > rect.width || y > rect.height) return;
  const ship = hitTestShip({ x, y });
  if (!ship) return;
  event.preventDefault();
  event.stopPropagation();
  showRealtimeShipConfirm(ship);
}

function showShipInfo(data) {
  if (!state.map) return;
  data = normalizeShipInfoData(data);
  if (state.layers.hoverShipId === data.id) return;
  state.layers.hoverShipId = data.id;
  const typeText = Number(data.isAis) === 1 ? "AIS" : "Radar";
  const content = `
    <div class="ship-info">
      <strong>${escapeHtml(data.name || data.id)}</strong>
      <div>编号：${escapeHtml(data.id)}</div>
      <div>类型：${typeText}</div>
      <div>航速：${escapeHtml(data.speed ?? "--")}</div>
      <div>航向：${escapeHtml(data.heading ?? "--")}</div>
      <div>时间：${escapeHtml(data.time || "--")}</div>
    </div>
  `;
  const card = $("ship-hover-card");
  card.innerHTML = content;
  card.classList.remove("hidden");
  const position = Array.isArray(data.lnglat) ? data.lnglat : [data.mapLng, data.mapLat];
  const pixel = state.map.getPixelFromCoordinate(ol.proj.fromLonLat(position));
  if (pixel) {
    card.style.left = `${Math.round(pixel[0] + 14)}px`;
    card.style.top = `${Math.round(pixel[1] + 14)}px`;
  }
  setTimeout(() => {
    document.querySelectorAll(".ship-info").forEach((node) => {
      node.onclick = (event) => {
        event.preventDefault();
        event.stopPropagation();
        selectRealtimeShip(data.id, data.name || data.id);
      };
    });
  }, 0);
  setStatus(`${data.name || data.id} / ${data.id}`);
}

function hideShipInfo() {
  if (state.layers.realtimeConfirmShipId) return;
  state.layers.hoverShipId = "";
  $("ship-hover-card")?.classList.add("hidden");
}

function clearRealtimeShipConfirm() {
  state.layers.realtimeConfirmShipId = "";
  state.layers.hoverShipId = "";
  const card = $("ship-hover-card");
  card?.classList.remove("confirm");
  card?.classList.add("hidden");
}

function showRealtimeShipConfirm(data) {
  if (!state.map) return;
  data = normalizeShipInfoData(data);
  state.layers.hoverShipId = data.id;
  state.layers.realtimeConfirmShipId = data.id;
  const typeText = Number(data.isAis) === 1 ? "AIS" : "Radar";
  const card = $("ship-hover-card");
  card.innerHTML = `
    <div class="ship-info">
      <strong>${escapeHtml(data.name || data.id)}</strong>
      <div>编号：${escapeHtml(data.id)}</div>
      <div>类型：${typeText}</div>
      <div>航速：${escapeHtml(data.speed ?? "--")}</div>
      <div>航向：${escapeHtml(data.heading ?? "--")}</div>
      <div>时间：${escapeHtml(data.time || "--")}</div>
      <div class="ship-info-actions">
        <button type="button" id="confirm-single-track" class="primary">确认接入单船轨迹</button>
        <button type="button" id="cancel-single-track">取消</button>
      </div>
    </div>
  `;
  card.classList.add("confirm");
  card.classList.remove("hidden");
  const position = Array.isArray(data.lnglat) ? data.lnglat : [data.mapLng, data.mapLat];
  const pixel = state.map.getPixelFromCoordinate(ol.proj.fromLonLat(position));
  if (pixel) {
    card.style.left = `${Math.round(pixel[0] + 14)}px`;
    card.style.top = `${Math.round(pixel[1] + 14)}px`;
  }
  $("confirm-single-track").onclick = (event) => {
    event.preventDefault();
    event.stopPropagation();
    selectRealtimeShip(data.id, data.name || data.id);
  };
  $("cancel-single-track").onclick = (event) => {
    event.preventDefault();
    event.stopPropagation();
    clearRealtimeShipConfirm();
  };
  setStatus(`已选中 ${data.name || data.id}，确认后查询单船轨迹`);
}

async function selectRealtimeShip(shipId, shipName) {
  if (!shipId) return;
  clearRealtimeShipConfirm();
  $("ship-id").value = shipId;
  setStatus(`已选中 ${shipName || shipId}，正在查询单船轨迹`);
  switchMode("single");
  try {
    await loadSingleTrack();
  } catch (error) {
    showError(error.message);
    setStatus("单船轨迹查询失败");
  }
}

function renderHeat() {
  if (!state.map || state.mode !== "analysis") return;
  if (state.layers.heat) state.map.removeLayer(state.layers.heat);
  const max = Math.max(1, ...state.density.map((item) => Number(item.count)));
  const source = new ol.source.Vector({
    features: state.density.map((item) => {
      const feature = new ol.Feature({ geometry: new ol.geom.Point(toMapCoordinate(item)) });
      feature.set("weight", Math.max(0.05, Math.min(1, Number(item.count) / max)));
      return feature;
    })
  });
  const heat = new ol.layer.Heatmap({
    source,
    radius: 28,
    blur: 18,
    opacity: 0.85,
    gradient: ["rgba(64,132,255,0)", "rgb(64,132,255)", "rgb(88,211,141)", "rgb(255,213,79)", "rgb(232,76,61)"],
    weight: (feature) => feature.get("weight")
  });
  heat.setZIndex(20);
  state.map.addLayer(heat);
  state.layers.heat = heat;
}

function renderTracks() {
  if (!state.map) return;
  if (state.layers.lineTimer) {
    clearTimeout(state.layers.lineTimer);
    state.layers.lineTimer = null;
  }
  state.layers.trackSource?.clear();
  state.layers.lines = [];
  const palette = ["#2563eb", "#16a34a", "#dc2626", "#d97706", "#7c3aed", "#0891b2", "#be123c"];
  state.layers.lineQueue = Array.from(groupByShip(state.trackPoints).entries()).map(([ship, points], index) => ({ ship, points, index, palette }));
  drawLineBatch();
  renderPlaybackMarkers();
}

function drawLineBatch() {
  const batch = state.layers.lineQueue.splice(0, state.mode === "global" ? 120 : 30);
  batch.forEach(({ ship, points, index, palette }) => {
    const path = points.map(toMapCoordinate);
    if (path.length < 2) return;
    const line = new ol.Feature({
      geometry: new ol.geom.LineString(path),
      ship,
      points: points.length
    });
    line.setStyle(new ol.style.Style({
      stroke: new ol.style.Stroke({
        color: palette[index % palette.length],
        width: state.mode === "global" ? 2 : 4,
        lineJoin: "round"
      })
    }));
    line.set("opacity", state.mode === "global" ? 0.35 : 0.85);
    state.layers.trackSource?.addFeature(line);
    state.layers.lines.push(line);
  });
  if (state.layers.lineQueue.length) {
    state.layers.lineTimer = setTimeout(drawLineBatch, 16);
  }
}

function renderPlaybackMarkers() {
  if (!state.map || !["single", "multi", "global"].includes(state.mode)) return;
  state.layers.markerSource?.clear();
  state.layers.markers = [];
  const visible = state.trackPoints.slice(0, Math.max(1, state.playIndex + 1));
  const latestByShip = new Map();
  visible.forEach((point) => latestByShip.set(point.shipId, point));
  latestByShip.forEach((point) => {
    const marker = new ol.Feature({
      geometry: new ol.geom.Point(toMapCoordinate(point)),
      point
    });
    marker.setStyle(shipMarkerStyle(Number(point.heading || 0)));
    state.layers.markerSource?.addFeature(marker);
    state.layers.markers.push(marker);
  });
  updateMetrics();
}

async function loadLatest() {
  setStatus("正在加载实时船位");
  state.latest = [];
  const windowQuery = realtimeWindowQuery();
  const data = await getJson("/api/realtime/latest" + (windowQuery ? "?" + windowQuery : ""));
  syncRealtimeWindowInputs(data.window);
  state.realtimeWindow = data.window || null;
  buildRealtimeStore(normalizeRealtimeItems(data));
  state.stats.memoryShips = Number(data.memoryShips ?? state.latest.length ?? 0);
  const maxTime = state.latest.reduce((max, item) => (item.time > max ? item.time : max), "");
  if (maxTime) {
    const end = new Date(maxTime.replace(" ", "T"));
    const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
    $("start").value = toLocalDatetime(start);
    $("end").value = toLocalDatetime(end);
    $("segment-start").value = toLocalDatetime(start);
  }
  renderRealtime();
  updateMetrics();
  scheduleRealtimeSummary(0);
  scheduleViewportStats(0);
  const sourceText = data.source === "memory" ? "内存缓存" : "数据库查询";
  setStatus(`${sourceText}已加载 ${state.latest.length.toLocaleString()} 条最新船位`);
}

async function loadDensity() {
  switchMode("analysis");
  setStatus("正在查询态势密度");
  const params = qs({ start: toIso($("start").value), end: toIso($("end").value), zoom: getMapZoom(), ...currentDataBBox() });
  const data = await getJson(`/api/analysis/density?${params}`);
  state.density = data.items;
  renderHeat();
  updateMetrics();
  scheduleViewportStats(0);
  setStatus(`密度网格 ${state.density.length.toLocaleString()} 个`);
}

async function loadSingleTrack() {
  switchMode("single");
  setStatus("正在查询单船轨迹");
  const params = qs({ shipId: $("ship-id").value.trim(), start: toIso($("start").value), end: toIso($("end").value), zoom: getMapZoom() });
  const data = await getJson(`/api/tracks/single?${params}`);
  state.trackPoints = data.items;
  state.playIndex = 0;
  renderTracks();
  updateMetrics();
  setStatus(`单船轨迹 ${state.trackPoints.length.toLocaleString()} 个抽稀点`);
}

async function loadCandidates(bbox = currentDataBBox()) {
  setStatus("正在查询候选船舶");
  const params = qs({ start: toIso($("start").value), end: toIso($("end").value), ...bbox, limit: state.config.maxMultiShips });
  const data = await getJson(`/api/tracks/candidates?${params}`);
  state.candidates = data.items;
  state.selectedShips = state.candidates.slice(0, 5).map((item) => item.shipId);
  renderCandidateList();
  setStatus(`候选船舶 ${state.candidates.length} 艘，默认选中前 5 艘`);
}

function renderCandidateList() {
  const list = $("ship-list");
  list.innerHTML = "";
  state.candidates.forEach((ship) => {
    const row = document.createElement("label");
    row.className = "check-row";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = state.selectedShips.includes(ship.shipId);
    checkbox.onchange = () => {
      if (checkbox.checked) state.selectedShips = [...state.selectedShips, ship.shipId].slice(0, state.config.maxMultiShips);
      else state.selectedShips = state.selectedShips.filter((id) => id !== ship.shipId);
    };
    const text = document.createElement("span");
    text.textContent = ship.shipId;
    row.append(checkbox, text);
    list.append(row);
  });
}

function drawBox() {
  switchMode("multi");
  setStatus("拖拽地图框选船舶范围");
  if (state.layers.drawBoxInteraction) {
    state.map.removeInteraction(state.layers.drawBoxInteraction);
    state.layers.drawBoxInteraction = null;
  }
  const dragBox = new ol.interaction.DragBox({ condition: ol.events.condition.always });
  dragBox.on("boxend", async () => {
    const extent = dragBox.getGeometry().getExtent();
    state.layers.rectangleSource?.clear();
    const rectangle = new ol.Feature({ geometry: ol.geom.Polygon.fromExtent(extent) });
    state.layers.rectangleSource?.addFeature(rectangle);
    state.map.removeInteraction(dragBox);
    state.layers.drawBoxInteraction = null;
    const sw = ol.proj.toLonLat([extent[0], extent[1]]);
    const ne = ol.proj.toLonLat([extent[2], extent[3]]);
    await loadCandidates(toQueryBBoxFromLngLat(sw, ne));
  });
  state.layers.drawBoxInteraction = dragBox;
  state.map.addInteraction(dragBox);
}

async function loadMultiTrack() {
  switchMode("multi");
  setStatus("正在查询多船轨迹");
  const data = await postJson("/api/tracks/multi", {
    shipIds: state.selectedShips,
    start: toIso($("start").value),
    end: toIso($("end").value),
    zoom: getMapZoom(),
    bbox: currentDataBBox()
  });
  state.trackPoints = data.items;
  state.playIndex = 0;
  renderTracks();
  updateMetrics();
  setStatus(`多船轨迹 ${state.trackPoints.length.toLocaleString()} 个抽稀点`);
}

async function loadGlobalSegment() {
  switchMode("global");
  setStatus("正在加载全域小时片段");
  const start = new Date($("segment-start").value);
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  const params = qs({ start: start.toISOString(), end: end.toISOString(), zoom: getMapZoom(), ...currentDataBBox() });
  const data = await getJson(`/api/tracks/global-segment?${params}`);
  state.trackPoints = [...state.trackPoints, ...data.items];
  state.playIndex = 0;
  renderTracks();
  updateMetrics();
  setStatus(`已加载 ${toLocalDatetime(start)} - ${toLocalDatetime(end)}，${data.items.length.toLocaleString()} 点`);
}

function tickPlayer() {
  if (state.playing && state.trackPoints.length) {
    state.playIndex = Math.min(state.trackPoints.length - 1, state.playIndex + state.speed);
    $("progress").value = state.playIndex;
    renderPlaybackMarkers();
    if (state.playIndex >= state.trackPoints.length - 1) {
      state.playing = false;
      $("play").textContent = "▶";
    }
  }
  setTimeout(tickPlayer, 400);
}

function createAmapTileLayer() {
  return new ol.layer.Tile({
    source: new ol.source.XYZ({
      maxZoom: 18,
      tileUrlFunction: (tileCoord) => {
        if (!tileCoord) return "";
        const [z, x, y] = tileCoord;
        const sub = Math.abs(x + y) % 4 + 1;
        return AMAP_TILE_URL.replace("{sub}", String(sub)).replace("{x}", String(x)).replace("{y}", String(y)).replace("{z}", String(z));
      },
      crossOrigin: "anonymous"
    })
  });
}

function initMap() {
  const center = state.config.coordinateSystem === "wgs84"
    ? wgs84ToGcj02(state.config.defaultCenter[0], state.config.defaultCenter[1])
    : state.config.defaultCenter;

  state.layers.trackSource = new ol.source.Vector();
  state.layers.markerSource = new ol.source.Vector();
  state.layers.rectangleSource = new ol.source.Vector();
  state.layers.trackLayer = new ol.layer.Vector({ source: state.layers.trackSource, zIndex: 30 });
  state.layers.markerLayer = new ol.layer.Vector({ source: state.layers.markerSource, zIndex: 40 });
  state.layers.rectangleLayer = new ol.layer.Vector({
    source: state.layers.rectangleSource,
    zIndex: 35,
    style: lineAndRectangleStyle("#2563eb", 2, 0.08)
  });

  state.map = new ol.Map({
    target: "map",
    layers: [createAmapTileLayer(), state.layers.trackLayer, state.layers.rectangleLayer, state.layers.markerLayer],
    view: new ol.View({
      center: ol.proj.fromLonLat(center),
      zoom: state.config.defaultZoom
    }),
    controls: ol.control.defaults.defaults().extend([new ol.control.ScaleLine()])
  });

  state.map.on("movestart", syncRealtimeCanvasDuringMove);
  state.map.on("pointerdrag", syncRealtimeCanvasDuringMove);
  state.map.on("moveend", () => {
    scheduleRealtimeRender();
    scheduleViewportStats();
  });
  state.map.getView().on("change:resolution", () => {
    scheduleRealtimeRender();
    scheduleViewportStats();
  });
  state.map.on("pointermove", handleRealtimePointerMove);
  state.map.on("click", handleRealtimeClick);
  $("map").addEventListener("mouseleave", hideShipInfo);
}

async function init() {
  try {
    state.config = await getJson("/api/config/map");
    initMap();
    document.addEventListener("click", handleRealtimeDomClick, true);
    window.addEventListener("resize", () => {
      state.map.updateSize();
      scheduleRealtimeRender(50);
      scheduleViewportStats(80);
    }, { passive: true });
    bindEvents();
    await loadLatest().catch((error) => {
      showError(error.message);
      setStatus("Database is unavailable; map page loaded");
    });
    connectWebSocket();
    tickPlayer();
  } catch (error) {
    showError(error.message);
  }
}

function connectWebSocket() {
  const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/realtime`);
  ws.onmessage = (event) => {
    const payload = JSON.parse(event.data);
    if (payload.type === "delta") {
      if (payload.window && state.realtimeWindow && !sameWindow(payload.window, state.realtimeWindow)) return;
      upsertRealtimeItems(payload.items || []);
      scheduleRealtimeRender(16);
      scheduleViewportStats(500);
      updateMetrics();
      setStatus(`WebSocket pushed ${payload.items.length} ships`);
    }
    if (payload.type === "error") setStatus(payload.message);
  };
}

function bindEvents() {
  document.querySelectorAll(".nav").forEach((button) => button.addEventListener("click", () => switchMode(button.dataset.mode)));
  $("load-latest").onclick = () => loadLatest().catch((error) => showError(error.message));
  $("realtime-start").onchange = () => loadLatest().catch((error) => showError(error.message));
  $("realtime-end").onchange = () => loadLatest().catch((error) => showError(error.message));
  $("realtime-type").onchange = () => {
    if (state.mode === "realtime") {
      renderRealtime();
      updateMetrics();
    }
  };
  $("load-density").onclick = () => loadDensity().catch((error) => showError(error.message));
  $("start").onchange = () => {
    scheduleRealtimeSummary(0);
    scheduleViewportStats(0);
  };
  $("end").onchange = () => {
    scheduleRealtimeSummary(0);
    scheduleViewportStats(0);
  };
  $("load-single").onclick = () => loadSingleTrack().catch((error) => showError(error.message));
  $("draw-box").onclick = drawBox;
  $("load-candidates").onclick = () => loadCandidates().catch((error) => showError(error.message));
  $("load-multi").onclick = () => loadMultiTrack().catch((error) => showError(error.message));
  $("load-global").onclick = () => loadGlobalSegment().catch((error) => showError(error.message));
  $("clear-global").onclick = () => {
    state.trackPoints = [];
    state.playIndex = 0;
    renderTracks();
    updateMetrics();
  };
  $("play").onclick = () => {
    state.playing = !state.playing;
    $("play").textContent = state.playing ? "暂停" : "播放";
  };
  $("stop").onclick = () => {
    state.playing = false;
    state.playIndex = 0;
    $("play").textContent = "▶";
    renderPlaybackMarkers();
  };
  $("speed").onchange = () => {
    state.speed = Number($("speed").value);
  };
  $("progress").oninput = () => {
    state.playIndex = Number($("progress").value);
    renderPlaybackMarkers();
  };
}

init();





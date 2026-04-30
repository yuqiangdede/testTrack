const state = {
  mode: "realtime",
  config: null,
  AMap: null,
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
  layers: {
    mass: null,
    heat: null,
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
const RADAR_SHIP_ID_PATTERN = /^\d+[-_]\d+$/;

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

function toQueryBBox(bounds) {
  const sw = bounds.getSouthWest();
  const ne = bounds.getNorthEast();
  const qsw = state.config?.coordinateSystem === "wgs84" ? gcj02ToWgs84(sw.lng, sw.lat) : [sw.lng, sw.lat];
  const qne = state.config?.coordinateSystem === "wgs84" ? gcj02ToWgs84(ne.lng, ne.lat) : [ne.lng, ne.lat];
  return { west: qsw[0], south: qsw[1], east: qne[0], north: qne[1] };
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

function currentDataBBox() {
  if (!state.map) return null;
  return toQueryBBox(state.map.getBounds());
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
    const currentPixel = state.map.lngLatToContainer(state.layers.realtimeRenderAnchor);
    const dx = Math.round(currentPixel.x - state.layers.realtimeRenderAnchorPixel.x);
    const dy = Math.round(currentPixel.y - state.layers.realtimeRenderAnchorPixel.y);
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

function updateMetrics() {
  $("metric-latest").textContent = state.latest.length.toLocaleString();
  $("metric-density").textContent = state.density.length.toLocaleString();
  $("metric-track").textContent = state.trackPoints.length.toLocaleString();
  $("metric-ships").textContent = groupByShip(state.trackPoints).size.toLocaleString();
  $("progress").max = Math.max(0, state.trackPoints.length - 1);
  $("progress").value = state.playIndex;
  $("active-time").textContent = state.trackPoints[state.playIndex]?.time || "--";
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
  if (previousMode === "realtime" && mode !== "realtime" && state.layers.mass) {
    state.layers.mass.setMap(null);
    state.layers.mass = null;
  }
  if (mode === "realtime") renderRealtime();
  if (mode === "analysis") renderHeat();
  if (["single", "multi", "global"].includes(mode)) renderTracks();
}

function clearLayers() {
  if (!state.map) return;
  if (state.layers.lineTimer) {
    clearTimeout(state.layers.lineTimer);
    state.layers.lineTimer = null;
  }
  if (state.layers.mass && state.mode !== "realtime") state.layers.mass.setMap(null);
  if (state.layers.heat) state.layers.heat.setMap(null);
  state.layers.lines.forEach((line) => state.map.remove(line));
  state.layers.markers.forEach((marker) => state.map.remove(marker));
  if (state.mode !== "realtime") clearRealtimeCanvas();
  state.layers.mass = null;
  state.layers.heat = null;
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

function pixelFromShip(item) {
  const lngLat = new state.AMap.LngLat(item.mapLng, item.mapLat);
  const pixel = state.map.lngLatToContainer(lngLat);
  return { x: pixel.x, y: pixel.y };
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
  if (state.layers.mass) {
    state.layers.mass.setMap(null);
    state.layers.mass = null;
  }
  const { ctx, width, height } = layer;
  if (state.layers.realtimeFrame) cancelAnimationFrame(state.layers.realtimeFrame);
  layer.canvas.style.transform = "";
  state.layers.realtimeRenderAnchor = state.map.getCenter();
  state.layers.realtimeRenderAnchorPixel = state.map.lngLatToContainer(state.layers.realtimeRenderAnchor);
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

function renderRealtimeMassMarks(indices) {
  const visibleItems = indices.map((index) => state.realtimeStore.items[index]).filter(Boolean);
  const data = visibleItems.map((item) => ({
    lnglat: [item.mapLng, item.mapLat],
    name: item.shipName || item.shipId,
    id: item.shipId,
    speed: item.speed,
    heading: item.heading,
    time: item.time,
    isAis: item.isAis,
    style: Number(item.speed) > 8 ? 1 : 0
  }));
  const styles = [
    { url: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' viewBox='0 0 22 22'%3E%3Cpath d='M11 2 18 20 11 16 4 20z' fill='%23168a52' stroke='%23ffffff' stroke-width='1.4'/%3E%3C/svg%3E", anchor: new state.AMap.Pixel(11, 11), size: new state.AMap.Size(22, 22) },
    { url: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' viewBox='0 0 22 22'%3E%3Cpath d='M11 2 18 20 11 16 4 20z' fill='%232b6fe8' stroke='%23ffffff' stroke-width='1.4'/%3E%3C/svg%3E", anchor: new state.AMap.Pixel(11, 11), size: new state.AMap.Size(22, 22) }
  ];
  if (state.layers.mass && typeof state.layers.mass.setData === "function") {
    state.layers.mass.setData(data);
    return;
  }
  if (state.layers.mass) state.layers.mass.setMap(null);
  const layer = new state.AMap.MassMarks(data, { opacity: 0.9, zIndex: 120, style: styles });
  layer.on("mouseover", (event) => showShipInfo(event.data));
  layer.on("mouseout", () => hideShipInfo());
  layer.on("click", (event) => selectRealtimeShip(event.data.id, event.data.name));
  layer.setMap(state.map);
  state.layers.mass = layer;
}

function renderRealtime() {
  if (!state.AMap || !state.map || state.mode !== "realtime") return;
  const startedAt = performance.now();
  const indices = queryVisibleShipIndices();
  try {
    renderRealtimeCanvas(indices);
  } catch (error) {
    console.warn("Realtime canvas failed, fallback to MassMarks", error);
    clearRealtimeCanvas();
    renderRealtimeMassMarks(indices);
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
  const px = Number(pixel.x);
  const py = Number(pixel.y);
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
  const ship = hitTestShip(event.pixel);
  if (ship) showShipInfo(ship);
  else if (state.layers.hoverShipId) hideShipInfo();
}

function handleRealtimeClick(event) {
  if (state.mode !== "realtime") return;
  const ship = hitTestShip(event.pixel);
  if (ship) selectRealtimeShip(ship.shipId, ship.shipName || ship.shipId);
}

function handleRealtimeDomClick(event) {
  if (state.mode !== "realtime") return;
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
  selectRealtimeShip(ship.shipId, ship.shipName || ship.shipId);
}

function showShipInfo(data) {
  if (!state.AMap || !state.map) return;
  data = normalizeShipInfoData(data);
  if (state.layers.hoverShipId === data.id) return;
  state.layers.hoverShipId = data.id;
  if (!state.layers.infoWindow) {
    state.layers.infoWindow = new state.AMap.InfoWindow({
      offset: new state.AMap.Pixel(0, -18),
      closeWhenClickMap: true
    });
  }
  const typeText = Number(data.isAis) === 1 ? "AIS 船只" : "雷达船";
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
  state.layers.infoWindow.setContent(content);
  const position = Array.isArray(data.lnglat) ? new state.AMap.LngLat(data.lnglat[0], data.lnglat[1]) : data.lnglat;
  state.layers.infoWindow.open(state.map, position);
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
  state.layers.hoverShipId = "";
  state.layers.infoWindow?.close();
}

async function selectRealtimeShip(shipId, shipName) {
  if (!shipId) return;
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
  if (!state.AMap || !state.map || state.mode !== "analysis") return;
  if (state.layers.heat) state.layers.heat.setMap(null);
  const heat = new state.AMap.HeatMap(state.map, {
    radius: 28,
    opacity: [0.15, 0.85],
    gradient: { 0.25: "rgb(64,132,255)", 0.55: "rgb(88,211,141)", 0.75: "rgb(255,213,79)", 1: "rgb(232,76,61)" }
  });
  const data = state.density.map((item) => {
    const [lng, lat] = toMapPoint(item);
    return { lng, lat, count: Number(item.count) };
  });
  heat.setDataSet({ data, max: Math.max(1, ...state.density.map((item) => Number(item.count))) });
  state.layers.heat = heat;
}

function renderTracks() {
  if (!state.AMap || !state.map) return;
  if (state.layers.lineTimer) {
    clearTimeout(state.layers.lineTimer);
    state.layers.lineTimer = null;
  }
  state.layers.lines.forEach((line) => state.map.remove(line));
  state.layers.lines = [];
  const palette = ["#2563eb", "#16a34a", "#dc2626", "#d97706", "#7c3aed", "#0891b2", "#be123c"];
  state.layers.lineQueue = Array.from(groupByShip(state.trackPoints).entries()).map(([ship, points], index) => ({ ship, points, index, palette }));
  drawLineBatch();
  renderPlaybackMarkers();
}

function drawLineBatch() {
  const batch = state.layers.lineQueue.splice(0, state.mode === "global" ? 120 : 30);
  batch.forEach(({ ship, points, index, palette }) => {
    const path = points.map(toMapPoint);
    if (path.length < 2) return;
    const line = new state.AMap.Polyline({
      path,
      strokeColor: palette[index % palette.length],
      strokeWeight: state.mode === "global" ? 2 : 4,
      strokeOpacity: state.mode === "global" ? 0.35 : 0.85,
      lineJoin: "round",
      showDir: state.mode !== "global"
    });
    line.on("mouseover", () => setStatus(`${ship} / ${points.length} track points`));
    state.map.add(line);
    state.layers.lines.push(line);
  });
  if (state.layers.lineQueue.length) {
    state.layers.lineTimer = setTimeout(drawLineBatch, 16);
  }
}

function renderPlaybackMarkers() {
  if (!state.AMap || !state.map || !["single", "multi", "global"].includes(state.mode)) return;
  state.layers.markers.forEach((marker) => state.map.remove(marker));
  state.layers.markers = [];
  const visible = state.trackPoints.slice(0, Math.max(1, state.playIndex + 1));
  const latestByShip = new Map();
  visible.forEach((point) => latestByShip.set(point.shipId, point));
  latestByShip.forEach((point) => {
    const marker = new state.AMap.Marker({
        position: toMapPoint(point),
        angle: Number(point.heading || 0),
        offset: new state.AMap.Pixel(-11, -11),
        content: '<div class="ship-marker"></div>'
      });
    state.map.add(marker);
    state.layers.markers.push(marker);
  });
  updateMetrics();
}

async function loadLatest() {
  setStatus("正在加载实时船位");
  state.latest = [];
  const windowQuery = realtimeWindowQuery();
  const data = await getJson(`/api/realtime/latest${windowQuery ? `?${windowQuery}` : ""}`);
  syncRealtimeWindowInputs(data.window);
  state.realtimeWindow = data.window || null;
  buildRealtimeStore(normalizeRealtimeItems(data));
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
  const sourceText = data.source === "memory" ? "内存缓存" : "数据库查询";
  setStatus(`${sourceText}已加载 ${state.latest.length.toLocaleString()} 条最新船位`);
}

async function loadDensity() {
  switchMode("analysis");
  setStatus("正在查询态势密度");
  const params = qs({ start: toIso($("start").value), end: toIso($("end").value), zoom: Math.round(state.map.getZoom()), ...toQueryBBox(state.map.getBounds()) });
  const data = await getJson(`/api/analysis/density?${params}`);
  state.density = data.items;
  renderHeat();
  updateMetrics();
  setStatus(`密度网格 ${state.density.length.toLocaleString()} 个`);
}

async function loadSingleTrack() {
  switchMode("single");
  setStatus("正在查询单船轨迹");
  const params = qs({ shipId: $("ship-id").value.trim(), start: toIso($("start").value), end: toIso($("end").value), zoom: Math.round(state.map.getZoom()) });
  const data = await getJson(`/api/tracks/single?${params}`);
  state.trackPoints = data.items;
  state.playIndex = 0;
  renderTracks();
  updateMetrics();
  setStatus(`单船轨迹 ${state.trackPoints.length.toLocaleString()} 个抽稀点`);
}

async function loadCandidates(bbox = toQueryBBox(state.map.getBounds())) {
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
  const mouseTool = new state.AMap.MouseTool(state.map);
  mouseTool.rectangle({
    strokeColor: "#2563eb",
    strokeOpacity: 0.9,
    strokeWeight: 2,
    fillColor: "#2563eb",
    fillOpacity: 0.08
  });
  mouseTool.on("draw", async (event) => {
    if (state.layers.rectangle) state.map.remove(state.layers.rectangle);
    state.layers.rectangle = event.obj;
    mouseTool.close(false);
    await loadCandidates(toQueryBBox(event.obj.getBounds()));
  });
}

async function loadMultiTrack() {
  switchMode("multi");
  setStatus("正在查询多船轨迹");
  const data = await postJson("/api/tracks/multi", {
    shipIds: state.selectedShips,
    start: toIso($("start").value),
    end: toIso($("end").value),
    zoom: Math.round(state.map.getZoom()),
    bbox: toQueryBBox(state.map.getBounds())
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
  const params = qs({ start: start.toISOString(), end: end.toISOString(), zoom: Math.round(state.map.getZoom()), ...toQueryBBox(state.map.getBounds()) });
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

function loadAmapScript(key, securityCode) {
  return new Promise((resolve, reject) => {
    if (securityCode) window._AMapSecurityConfig = { securityJsCode: securityCode };
    window.onAmapLoaded = () => resolve(window.AMap);
    const script = document.createElement("script");
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.Scale,AMap.ToolBar,AMap.HeatMap,AMap.MouseTool&callback=onAmapLoaded`;
    script.onerror = () => reject(new Error("AMap JS API failed to load"));
    document.head.appendChild(script);
  });
}

async function init() {
  try {
    state.config = await getJson("/api/config/map");
    if (!state.config.amapKey) {
      showError("Please configure VITE_AMAP_KEY in .env or environment variables, then restart the server");
      return;
    }
    state.AMap = await loadAmapScript(state.config.amapKey, state.config.amapSecurityJsCode);
    const center = state.config.coordinateSystem === "wgs84" ? wgs84ToGcj02(state.config.defaultCenter[0], state.config.defaultCenter[1]) : state.config.defaultCenter;
    state.map = new state.AMap.Map("map", {
      zoom: state.config.defaultZoom,
      center,
      mapStyle: "amap://styles/normal",
      viewMode: "2D"
    });
    state.map.addControl(new state.AMap.Scale());
    state.map.addControl(new state.AMap.ToolBar({ position: "RB" }));
    state.map.on("mapmove", syncRealtimeCanvasDuringMove);
    state.map.on("moveend", () => {
      scheduleRealtimeRender();
    });
    state.map.on("zoomend", () => {
      scheduleRealtimeRender();
    });
    state.map.on("mousemove", handleRealtimePointerMove);
    state.map.on("click", handleRealtimeClick);
    state.map.on("mouseout", hideShipInfo);
    document.addEventListener("click", handleRealtimeDomClick, true);
    window.addEventListener("resize", () => scheduleRealtimeRender(50), { passive: true });
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
    $("play").textContent = state.playing ? "Pause" : "Play";
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

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
  multi: {
    bbox: null,
    drawerVisible: false,
    candidateLoading: false,
    candidateQueryElapsedMs: null,
    candidateLoadSeq: 0,
    candidateBatchCount: 0,
    candidateHasMore: false,
    candidatePages: [],
    candidateCurrentPage: 1,
    selectedShips: [],
    selectedRawTrackPoints: 0,
    selectedSampledTrackPoints: 0,
    selectedStatsSeq: 0,
    stats: {
      databaseTrackPoints: 0,
      databaseShips: 0,
      windowTrackPoints: 0,
      windowShips: 0,
      bboxTrackPoints: 0,
      bboxShips: 0,
      summaryWindowKey: "",
      summaryTimer: null,
      summarySeq: 0,
      summaryInFlightKey: "",
      summaryInFlightPromise: null
    }
  },
  playing: false,
  playIndex: 0,
  speed: 4,
  sampling: {
    mode: "auto",
    bucketSeconds: 60
  },
  stats: {
    databaseTrackPoints: 0,
    databaseShips: 0,
    windowTrackPoints: 0,
    windowShips: 0,
    windowHeatCells: 0,
    viewportHeatCells: 0,
    viewportShips: 0,
    memoryShips: 0,
    singleRawTrackPoints: 0,
    singleSampledTrackPoints: 0,
    singleStatsSeq: 0,
    databaseStatsLoaded: false,
    summaryWindowKey: "",
    summaryTimer: null,
    summarySeq: 0
  },
  global: {
    stats: {
      databaseTrackPoints: 0,
      databaseShips: 0,
      windowTrackPoints: 0,
      sampledTrackPoints: 0,
      statsSeq: 0,
      summaryWindowKey: "",
      summaryTimer: null,
      summarySeq: 0
    }
  },
  layers: {
    heat: null,
    trackSource: null,
    trackLayer: null,
    playbackTrackSource: null,
    playbackTrackLayer: null,
    markerSource: null,
    markerLayer: null,
    rectangleSource: null,
    rectangleLayer: null,
    drawBoxInteraction: null,
    lines: [],
    lineQueue: [],
    lineTimer: null,
    trackRenderSeq: 0,
    trackRenderCallbacks: [],
    realtimeRenderTimer: null,
    realtimeCanvas: null,
    realtimeCtx: null,
    realtimeFrame: null,
    realtimeRenderSeq: 0,
    realtimePanFrame: null,
    realtimeRenderAnchor: null,
    realtimeRenderAnchorPixel: null,
    realtimeRenderResolution: null,
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
  },
  apiTrace: {
    seq: 0,
    history: [],
    hidden: false
  }
};

const REALTIME_INDEX_CELL_SIZE = 0.05;
const REALTIME_HIT_CELL_SIZE = 32;
const REALTIME_HIT_RADIUS = 12;
const REALTIME_DRAW_BUDGET_MS = 8;
const REALTIME_DRAW_BATCH = 2500;
const AMAP_TILE_URL = "https://webrd0{sub}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}";
const STATS_CACHE_TTL_MS = 5 * 60 * 1000;
const SUMMARY_CACHE_TTL_MS = 30 * 1000;
const API_TRACE_MAX_HISTORY = 3;
const API_TRACE_HIDDEN_KEY = "shiptrack.apiTrace.hidden";
const statsRequestCache = new Map();
const PLAYBACK_SPEED_OPTIONS = [1, 2, 4, 8, 16, 64, 128];
const TRACK_LINE_OPACITY = 0.24;
const PLAYBACK_LINE_OPACITY = 0.96;
const AIS_SHIP_FILL = "rgba(34, 197, 94, 0.75)";
const AIS_SHIP_STROKE = "#111827";
const AIS_SHIP_STROKE_WIDTH = 0.8;
const AIS_SHIP_ICON_WIDTH = 15;
const AIS_SHIP_ICON_HEIGHT = 7;
const AIS_SHIP_ICON_CACHE = new Map();

const $ = (id) => document.getElementById(id);

function setStatus(text) {
  $("status").textContent = text;
}

function showError(text) {
  const panel = $("error");
  panel.textContent = text;
  panel.classList.remove("hidden");
}

function loadApiTraceHidden() {
  try {
    return localStorage.getItem(API_TRACE_HIDDEN_KEY) === "1";
  } catch {
    return false;
  }
}

function saveApiTraceHidden(hidden) {
  try {
    localStorage.setItem(API_TRACE_HIDDEN_KEY, hidden ? "1" : "0");
  } catch {}
}

function setApiTraceHidden(hidden) {
  state.apiTrace.hidden = Boolean(hidden);
  saveApiTraceHidden(state.apiTrace.hidden);
  renderApiTracePanel();
}

function shouldTraceApi(url) {
  return !String(url || "").startsWith("/api/stats/") && String(url || "") !== "/api/config/map";
}

function apiTraceLabel(url, method = "GET") {
  let pathname = String(url || "");
  try {
    pathname = new URL(url, location.href).pathname || pathname;
  } catch {}
  pathname = pathname.replace(/^\/+/, "");
  if (pathname.startsWith("api/")) {
    pathname = pathname.slice(4);
  }
  const normalizedMethod = String(method || "GET").toUpperCase();
  return normalizedMethod === "GET" ? pathname : `${normalizedMethod} ${pathname}`;
}

function renderApiTracePanel(activeTrace = null) {
  const panel = $("api-trace");
  const toggle = $("api-trace-toggle");
  if (!panel) return;
  if (state.apiTrace.hidden) {
    panel.classList.add("hidden");
    if (toggle) {
      toggle.classList.remove("hidden");
    }
    panel.textContent = "";
    return;
  }
  const recentHistory = state.apiTrace.history
    .filter((trace) => trace && trace.id !== activeTrace?.id)
    .sort((left, right) => Number(right.startedAt || 0) - Number(left.startedAt || 0));
  const traces = [];
  if (activeTrace) {
    traces.push(activeTrace);
  }
  traces.push(...recentHistory);
  panel.classList.toggle("hidden", traces.length === 0);
  if (toggle) {
    toggle.classList.add("hidden");
  }
  if (!traces.length) {
    panel.textContent = "";
    return;
  }
  panel.innerHTML = traces.map((trace, index) => {
    const entries = trace.entries || [];
    const body = entries.length
      ? entries.map((entry) => `
          <div class="api-trace-item">
            <code>${escapeHtml(entry.label)}</code>
            <span>${escapeHtml(entry.elapsedMs)} ms</span>
          </div>
        `).join("")
      : `<div class="api-trace-empty">等待接口返回</div>`;
    return `
      <section class="api-trace-card ${index === 0 ? "is-active" : ""}">
        <div class="api-trace-header">
          <strong>${escapeHtml(trace.title)}</strong>
          <button class="api-trace-close" type="button" data-api-trace-close aria-label="关闭接口耗时浮层">×</button>
        </div>
        <div class="api-trace-header">
          <span>${escapeHtml(trace.finished ? "已完成" : "进行中")} · ${escapeHtml(entries.length)} 项</span>
        </div>
        <div class="api-trace-list">${body}</div>
      </section>
    `;
  }).join("");
  panel.querySelectorAll("[data-api-trace-close]").forEach((button) => {
    button.onclick = () => setApiTraceHidden(true);
  });
}

function beginApiTrace(title) {
  const trace = {
    id: ++state.apiTrace.seq,
    title,
    startedAt: performance.now(),
    entries: [],
    finished: false
  };
  renderApiTracePanel(trace);
  return trace;
}

function recordApiTrace(trace, url, method, elapsedMs) {
  if (!trace || trace.finished || !shouldTraceApi(url)) {
    return;
  }
  trace.entries.push({
    label: apiTraceLabel(url, method),
    elapsedMs: Math.max(0, Math.round(elapsedMs))
  });
  renderApiTracePanel(trace);
}

function finishApiTrace(trace) {
  if (!trace || trace.finished) {
    return;
  }
  trace.finished = true;
  state.apiTrace.history = [trace, ...state.apiTrace.history.filter((item) => item.id !== trace.id)].slice(0, API_TRACE_MAX_HISTORY);
  renderApiTracePanel(trace);
}

async function requestJson(url, options = {}) {
  const { trace, ...fetchOptions } = options;
  const startedAt = performance.now();
  let response;
  try {
    response = await fetch(url, fetchOptions);
  } catch (error) {
    const elapsedMs = Math.max(0, performance.now() - startedAt);
    recordApiTrace(trace, url, fetchOptions.method || "GET", elapsedMs);
    throw error;
  }
  const elapsedMs = Math.max(0, performance.now() - startedAt);
  recordApiTrace(trace, url, fetchOptions.method || "GET", elapsedMs);
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

async function getJson(url, options = {}) {
  return requestJson(url, { ...options, method: "GET" });
}

async function getCachedJson(url, ttlMs = STATS_CACHE_TTL_MS) {
  const now = Date.now();
  const cached = statsRequestCache.get(url);
  if (cached?.value && cached.expiresAt > now) {
    return cached.value;
  }
  if (cached?.promise) {
    return cached.promise;
  }
  const entry = { expiresAt: now + ttlMs, value: null, promise: null };
  entry.promise = getJson(url)
    .then((value) => {
      entry.value = value;
      entry.expiresAt = Date.now() + ttlMs;
      entry.promise = null;
      statsRequestCache.set(url, entry);
      return value;
    })
    .catch((error) => {
      if (statsRequestCache.get(url) === entry) {
        statsRequestCache.delete(url);
      }
      throw error;
    });
  statsRequestCache.set(url, entry);
  return entry.promise;
}

function applyDatabaseStats(databaseTrackPoints, databaseShips) {
  const trackPoints = Number(databaseTrackPoints || 0);
  const ships = Number(databaseShips || 0);
  state.stats.databaseTrackPoints = trackPoints;
  state.stats.databaseShips = ships;
  state.stats.databaseStatsLoaded = true;
  state.multi.stats.databaseTrackPoints = trackPoints;
  state.multi.stats.databaseShips = ships;
  state.global.stats.databaseTrackPoints = trackPoints;
  state.global.stats.databaseShips = ships;
}

async function postJson(url, body, options = {}) {
  return requestJson(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    trace: options.trace
  });
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

function toLocalDatetimeString(value) {
  if (!value) return "";
  const date = new Date(String(value).replace(" ", "T"));
  return Number.isFinite(date.getTime()) ? toLocalDatetime(date) : "";
}

function realtimeWindowParams() {
  const point = $("realtime-point")?.value;
  const minutesValue = $("realtime-minutes")?.value;
  const minutes = Number(minutesValue || 10);
  if (point && Number.isFinite(minutes) && minutes > 0) {
    return { timePoint: toIso(point), minutes };
  }
  if (state.realtimeWindow?.start && state.realtimeWindow?.end) {
    const start = new Date(String(state.realtimeWindow.start).replace(" ", "T"));
    const end = new Date(String(state.realtimeWindow.end).replace(" ", "T"));
    const fallbackMinutes = Math.max(1, Math.round((end.getTime() - start.getTime()) / 60000));
    return { timePoint: toLocalDatetime(end), minutes: fallbackMinutes };
  }
  return null;
}

function analysisWindowParams() {
  const point = $("analysis-point")?.value;
  const minutesValue = $("analysis-minutes")?.value;
  const minutes = Number(minutesValue || 10);
  if (point && Number.isFinite(minutes) && minutes > 0) {
    return { timePoint: toIso(point), minutes };
  }
  if (state.realtimeWindow?.start && state.realtimeWindow?.end) {
    const start = new Date(String(state.realtimeWindow.start).replace(" ", "T"));
    const end = new Date(String(state.realtimeWindow.end).replace(" ", "T"));
    const fallbackMinutes = Math.max(1, Math.round((end.getTime() - start.getTime()) / 60000));
    return { timePoint: toLocalDatetime(end), minutes: fallbackMinutes };
  }
  return null;
}

function ensureGlobalWindowDefaults() {
  const point = $("global-point");
  if (point && !point.value) {
    point.value = toLocalDatetime(new Date());
  }
  const hours = $("global-hours");
  if (hours && !hours.value) {
    hours.value = String(Math.max(1, Number(state.config?.globalSegmentHours || 1)));
  }
}

function globalWindowParams() {
  ensureGlobalWindowDefaults();
  const point = $("global-point")?.value;
  if (!point) return null;
  const pointDate = new Date(point);
  if (!Number.isFinite(pointDate.getTime())) return null;
  const hours = Math.max(1, Number($("global-hours")?.value || state.config?.globalSegmentHours || 1));
  return { timePoint: toIso(pointDate), hours };
}

function normalizeSamplingMode(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (normalized === "raw" || normalized === "manual" || normalized === "auto") {
    return normalized;
  }
  return "auto";
}

function normalizeBucketSeconds(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return 60;
  }
  return Math.max(1, Math.floor(number));
}

function syncSamplingControls() {
  const modeSelect = $("track-sampling-mode");
  const bucketGroup = $("track-sampling-bucket-group");
  const bucketInput = $("track-sampling-bucket");
  const hint = $("track-sampling-hint");
  const mode = normalizeSamplingMode(modeSelect?.value || state.sampling.mode);
  const bucketSeconds = normalizeBucketSeconds(bucketInput?.value || state.sampling.bucketSeconds);
  state.sampling.mode = mode;
  state.sampling.bucketSeconds = bucketSeconds;
  if (modeSelect && modeSelect.value !== mode) {
    modeSelect.value = mode;
  }
  if (bucketGroup) {
    bucketGroup.classList.toggle("hidden", mode !== "manual");
  }
  if (bucketInput) {
    bucketInput.value = String(bucketSeconds);
    bucketInput.disabled = mode !== "manual";
  }
  if (hint) {
    hint.textContent = mode === "manual" ? "人工指定时直接按桶秒数抽稀，输入值会完全生效。" : "";
  }
}

function activeSamplingParams() {
  syncSamplingControls();
  return {
    samplingMode: state.sampling.mode,
    bucketSeconds: state.sampling.bucketSeconds
  };
}

function playbackPointLabel() {
  return state.sampling.mode === "raw" ? "原始点" : "抽稀点";
}

async function reloadActivePlaybackTrack() {
  if (state.mode === "single") {
    return loadSingleTrack();
  }
  if (state.mode === "multi") {
    return loadMultiTrack();
  }
  if (state.mode === "global") {
    return loadGlobalSegment();
  }
  return null;
}

function syncSingleWindowInputs(windowValue) {
  if (!windowValue?.end) return;
  const point = $("single-point");
  if (point && !point.value) {
    point.value = toLocalDatetimeString(windowValue.end);
  }
}

function ensureSingleWindowDefaults() {
  const point = $("single-point");
  if (point && !point.value) {
    point.value = state.realtimeWindow?.end ? toLocalDatetimeString(state.realtimeWindow.end) : toLocalDatetime(new Date());
  }
  const beforeHours = $("single-before-hours");
  if (beforeHours && !beforeHours.value) beforeHours.value = "12";
  const afterHours = $("single-after-hours");
  if (afterHours && !afterHours.value) afterHours.value = "0";
}

function singleTrackWindowParams() {
  ensureSingleWindowDefaults();
  const point = $("single-point")?.value;
  if (!point) return null;
  const pointDate = new Date(point);
  if (!Number.isFinite(pointDate.getTime())) return null;
  const beforeHours = Math.max(0, Number($("single-before-hours")?.value || 12));
  const afterHours = Math.max(0, Number($("single-after-hours")?.value || 0));
  const start = new Date(pointDate.getTime() - beforeHours * 60 * 60 * 1000);
  const end = new Date(pointDate.getTime() + afterHours * 60 * 60 * 1000);
  if (start >= end) return null;
  return { start: toIso(start), end: toIso(end) };
}

function syncRealtimeWindowInputs(windowValue) {
  if (!windowValue?.start || !windowValue?.end) return;
  const start = new Date(String(windowValue.start).replace(" ", "T"));
  const end = new Date(String(windowValue.end).replace(" ", "T"));
  $("realtime-point").value = toLocalDatetime(end);
  $("realtime-minutes").value = String(Math.max(1, Math.round((end.getTime() - start.getTime()) / 60000)));
}

function syncAnalysisWindowInputs(windowValue) {
  if (!windowValue?.start || !windowValue?.end) return;
  const start = new Date(String(windowValue.start).replace(" ", "T"));
  const end = new Date(String(windowValue.end).replace(" ", "T"));
  $("analysis-point").value = toLocalDatetime(end);
  $("analysis-minutes").value = String(Math.max(1, Math.round((end.getTime() - start.getTime()) / 60000)));
}

function realtimeWindowQuery() {
  const params = realtimeWindowParams();
  return params ? qs(params) : "";
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
    isAis: Number(item.isAis || 0)
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

function cancelRealtimePanFrame() {
  if (state.layers.realtimePanFrame) {
    cancelAnimationFrame(state.layers.realtimePanFrame);
    state.layers.realtimePanFrame = null;
  }
}

function renderRealtimeNow() {
  if (state.layers.realtimeRenderTimer) {
    clearTimeout(state.layers.realtimeRenderTimer);
    state.layers.realtimeRenderTimer = null;
  }
  cancelRealtimePanFrame();
  if (state.mode === "realtime" && state.latest.length) renderRealtime();
}

function syncRealtimeCanvasDuringMove() {
  if (state.mode !== "realtime" || !state.map || !state.layers.realtimeCanvas) return;
  if (!state.layers.realtimeRenderAnchor || !state.layers.realtimeRenderAnchorPixel) return;
  if (!Number.isFinite(state.layers.realtimeRenderResolution)) return;
  if (state.layers.realtimePanFrame) return;
  state.layers.realtimePanFrame = requestAnimationFrame(() => {
    state.layers.realtimePanFrame = null;
    if (state.mode !== "realtime" || !state.layers.realtimeCanvas || !state.layers.realtimeRenderAnchor) return;
    const currentPixel = state.map.getPixelFromCoordinate(state.layers.realtimeRenderAnchor);
    if (!currentPixel) return;
    const currentResolution = state.map.getView().getResolution();
    if (!Number.isFinite(currentResolution) || currentResolution <= 0) return;
    const scale = state.layers.realtimeRenderResolution / currentResolution;
    const dx = Math.round(currentPixel[0] - state.layers.realtimeRenderAnchorPixel[0] * scale);
    const dy = Math.round(currentPixel[1] - state.layers.realtimeRenderAnchorPixel[1] * scale);
    state.layers.realtimeCanvas.style.transform = `translate3d(${dx}px, ${dy}px, 0) scale(${scale})`;
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

function withOpacity(color, opacity) {
  if (!color) return color;
  const alpha = Math.max(0, Math.min(1, Number(opacity)));
  if (color.startsWith("#")) {
    const hex = color.slice(1);
    const normalized = hex.length === 3
      ? hex.split("").map((part) => part + part).join("")
      : hex;
    if (normalized.length === 6) {
      const r = Number.parseInt(normalized.slice(0, 2), 16);
      const g = Number.parseInt(normalized.slice(2, 4), 16);
      const b = Number.parseInt(normalized.slice(4, 6), 16);
      if ([r, g, b].every(Number.isFinite)) {
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
      }
    }
  }
  return color;
}

function updateMetrics() {
  $("metric-realtime-db-points").textContent = metricNumber(state.stats.databaseTrackPoints);
  $("metric-realtime-db-ships").textContent = metricNumber(state.stats.databaseShips);
  $("metric-realtime-window-points").textContent = metricNumber(state.stats.windowTrackPoints);
  $("metric-realtime-window-ships").textContent = metricNumber(state.stats.windowShips);
  $("metric-realtime-memory-ships").textContent = metricNumber(state.stats.memoryShips);
  $("metric-realtime-viewport-ships").textContent = metricNumber(state.stats.viewportShips);
  $("metric-analysis-db-points").textContent = metricNumber(state.stats.databaseTrackPoints);
  $("metric-analysis-db-ships").textContent = metricNumber(state.stats.databaseShips);
  $("metric-analysis-window-points").textContent = metricNumber(state.stats.windowTrackPoints);
  $("metric-analysis-window-ships").textContent = metricNumber(state.stats.windowShips);
  $("metric-analysis-window-heat-cells").textContent = metricNumber(state.stats.windowHeatCells);
  $("metric-analysis-viewport-heat-cells").textContent = metricNumber(state.stats.viewportHeatCells);
  $("metric-single-db-points").textContent = metricNumber(state.stats.databaseTrackPoints);
  $("metric-single-db-ships").textContent = metricNumber(state.stats.databaseShips);
  $("metric-single-track-points").textContent = metricNumber(state.stats.singleRawTrackPoints);
  $("metric-single-sampled-points").textContent = metricNumber(state.stats.singleSampledTrackPoints);
  $("metric-multi-db-points").textContent = metricNumber(state.multi.stats.databaseTrackPoints);
  $("metric-multi-db-ships").textContent = metricNumber(state.multi.stats.databaseShips);
  $("metric-multi-window-points").textContent = metricNumber(state.multi.stats.windowTrackPoints);
  $("metric-multi-window-ships").textContent = metricNumber(state.multi.stats.windowShips);
  $("metric-multi-bbox-points").textContent = metricNumber(state.multi.stats.bboxTrackPoints);
  $("metric-multi-bbox-ships").textContent = metricNumber(state.multi.stats.bboxShips);
  $("metric-multi-selected-points").textContent = metricNumber(state.multi.selectedRawTrackPoints);
  $("metric-multi-sampled-points").textContent = metricNumber(state.multi.selectedSampledTrackPoints);
  $("metric-global-db-points").textContent = metricNumber(state.global.stats.databaseTrackPoints);
  $("metric-global-db-ships").textContent = metricNumber(state.global.stats.databaseShips);
  $("metric-global-window-points").textContent = metricNumber(state.global.stats.windowTrackPoints);
  $("metric-global-sampled-points").textContent = metricNumber(state.global.stats.sampledTrackPoints);
  $("progress").max = Math.max(0, state.trackPoints.length - 1);
  $("progress").value = state.playIndex;
  $("active-time").textContent = state.trackPoints[state.playIndex]?.time || "--";
}

function activeStatsWindow() {
  if (state.mode === "realtime") {
    return realtimeWindowParams();
  }
  if (state.mode === "analysis") {
    return analysisWindowParams();
  }
  if (state.mode === "multi") {
    const start = $("start")?.value;
    const end = $("end")?.value;
    if (!start || !end) return null;
    return { start: toIso(start), end: toIso(end) };
  }
  return null;
}

async function refreshRealtimeSummary() {
  const windowValue = activeStatsWindow();
  if (!windowValue) return;
  const analysisMode = state.mode === "analysis";
  const bbox = analysisMode ? currentDataBBox() : null;
  const summaryKey = JSON.stringify({ windowValue, bbox, zoom: getMapZoom(), mode: state.mode });
  if (summaryKey === state.stats.summaryWindowKey) return;
  const seq = ++state.stats.summarySeq;
  const params = qs({ ...windowValue, zoom: getMapZoom(), ...(bbox || {}) });
  const data = await getCachedJson(`/api/stats/realtime-summary?${params}`, SUMMARY_CACHE_TTL_MS);
  if (seq !== state.stats.summarySeq) return;
  state.stats.summaryWindowKey = summaryKey;
  state.stats.windowTrackPoints = Number(data.windowTrackPoints || 0);
  state.stats.windowShips = Number(data.windowShips || 0);
  if (analysisMode) {
    state.stats.windowHeatCells = Number(data.windowHeatCells || 0);
    state.stats.viewportHeatCells = Number(data.viewportHeatCells || 0);
  }
  updateMetrics();
}

function scheduleRealtimeSummary(delay = 260) {
  if (state.stats.summaryTimer) clearTimeout(state.stats.summaryTimer);
  state.stats.summaryTimer = setTimeout(() => {
    state.stats.summaryTimer = null;
    refreshRealtimeSummary().catch((error) => setStatus("统计刷新失败: " + error.message));
  }, delay);
}

async function refreshDatabaseStats() {
  const data = await getCachedJson("/api/stats/database", STATS_CACHE_TTL_MS);
  applyDatabaseStats(data.databaseTrackPoints ?? data.trackPoints ?? 0, data.databaseShips ?? data.ships ?? 0);
  updateMetrics();
}

async function refreshSingleTrackPointStats(windowValue, shipId, seq) {
  if (!windowValue || !shipId) return;
  const params = qs({ shipId, ...windowValue });
  const data = await getJson(`/api/stats/single-track-points?${params}`);
  if (seq !== state.stats.singleStatsSeq) return;
  state.stats.singleRawTrackPoints = Number(data.trackPoints || 0);
  updateMetrics();
}

async function refreshSelectedMultiTrackPointStats(windowValue, shipIds, seq) {
  if (!windowValue || !shipIds.length) return;
  const data = await postJson("/api/stats/multi-track-points", {
    shipIds,
    start: windowValue.start,
    end: windowValue.end
  });
  if (seq !== state.multi.selectedStatsSeq) return;
  state.multi.selectedRawTrackPoints = Number(data.trackPoints || 0);
  updateMetrics();
}

async function refreshGlobalSummary() {
  const windowValue = globalWindowParams();
  if (!windowValue) return;
  const summaryKey = JSON.stringify({ windowValue });
  if (summaryKey === state.global.stats.summaryWindowKey) return;
  const seq = ++state.global.stats.summarySeq;
  const data = await getCachedJson(`/api/stats/global-summary?${qs(windowValue)}`, SUMMARY_CACHE_TTL_MS);
  if (seq !== state.global.stats.summarySeq) return;
  state.global.stats.summaryWindowKey = summaryKey;
  state.global.stats.windowTrackPoints = Number(data.windowTrackPoints || 0);
  updateMetrics();
}

function scheduleGlobalSummary(delay = 260) {
  if (state.global.stats.summaryTimer) clearTimeout(state.global.stats.summaryTimer);
  state.global.stats.summaryTimer = setTimeout(() => {
    state.global.stats.summaryTimer = null;
    refreshGlobalSummary().catch((error) => setStatus("全域统计刷新失败: " + error.message));
  }, delay);
}

async function refreshMultiSummary() {
  const start = $("start")?.value;
  const end = $("end")?.value;
  if (!start || !end) {
    state.multi.stats.summaryWindowKey = "";
    state.multi.stats.summaryInFlightKey = "";
    state.multi.stats.summaryInFlightPromise = null;
    state.multi.stats.databaseTrackPoints = 0;
    state.multi.stats.databaseShips = 0;
    state.multi.stats.windowTrackPoints = 0;
    state.multi.stats.windowShips = 0;
    state.multi.stats.bboxTrackPoints = 0;
    state.multi.stats.bboxShips = 0;
    updateMetrics();
    return;
  }
  const bbox = state.multi.bbox;
  const summaryKey = JSON.stringify({ start, end, bbox });
  if (summaryKey === state.multi.stats.summaryWindowKey) return;
  if (summaryKey === state.multi.stats.summaryInFlightKey && state.multi.stats.summaryInFlightPromise) {
    return state.multi.stats.summaryInFlightPromise;
  }
  const seq = ++state.multi.stats.summarySeq;
  const params = qs({
    start: toIso(start),
    end: toIso(end),
    ...(bbox || {})
  });
  const promise = getCachedJson(`/api/stats/multi-summary?${params}`, SUMMARY_CACHE_TTL_MS)
    .then((data) => {
      if (seq !== state.multi.stats.summarySeq) return;
      state.multi.stats.summaryWindowKey = summaryKey;
      state.multi.stats.windowTrackPoints = Number(data.windowTrackPoints || 0);
      state.multi.stats.windowShips = Number(data.windowShips || 0);
      state.multi.stats.bboxTrackPoints = Number(data.bboxTrackPoints || 0);
      state.multi.stats.bboxShips = Number(data.bboxShips || 0);
      updateMetrics();
    })
    .finally(() => {
      if (state.multi.stats.summaryInFlightPromise === promise) {
        state.multi.stats.summaryInFlightKey = "";
        state.multi.stats.summaryInFlightPromise = null;
      }
    });
  state.multi.stats.summaryInFlightKey = summaryKey;
  state.multi.stats.summaryInFlightPromise = promise;
  return promise;
}

function scheduleMultiSummary(delay = 260) {
  if (state.multi.stats.summaryTimer) clearTimeout(state.multi.stats.summaryTimer);
  state.multi.stats.summaryTimer = setTimeout(() => {
    state.multi.stats.summaryTimer = null;
    refreshMultiSummary().catch((error) => setStatus("多船统计刷新失败: " + error.message));
  }, delay);
}

function resetTrackPlaybackState() {
  state.playing = false;
  state.playIndex = 0;
  state.trackPoints = [];
  state.stats.singleStatsSeq += 1;
  state.stats.singleRawTrackPoints = 0;
  state.stats.singleSampledTrackPoints = 0;
  state.multi.selectedStatsSeq += 1;
  state.multi.selectedRawTrackPoints = 0;
  state.multi.selectedSampledTrackPoints = 0;
  state.global.stats.statsSeq += 1;
  state.global.stats.sampledTrackPoints = 0;
  syncPlayButtons();
  $("progress").value = 0;
  $("active-time").textContent = "--";
}

function syncPlayButtons() {
  const playing = state.playing;
  const mainPlay = $("play");
  if (mainPlay) {
    mainPlay.textContent = playing ? "⏸" : "▶";
    mainPlay.disabled = !state.trackPoints.length;
  }
  const speedSelect = $("speed");
  if (speedSelect) {
    const normalizedSpeed = PLAYBACK_SPEED_OPTIONS.includes(Number(state.speed)) ? Number(state.speed) : 4;
    if (normalizedSpeed !== state.speed) {
      state.speed = normalizedSpeed;
    }
    speedSelect.value = String(normalizedSpeed);
  }
}

function pausePlayback() {
  state.playing = false;
  syncPlayButtons();
}

function startPlayback() {
  if (!state.trackPoints.length) return;
  if (state.playIndex >= state.trackPoints.length - 1) {
    state.playIndex = 0;
    $("progress").value = 0;
  }
  state.playing = true;
  syncPlayButtons();
  renderPlaybackMarkers();
}

function togglePlayback() {
  if (state.playing) {
    pausePlayback();
    return;
  }
  startPlayback();
}

function exitPlayback() {
  if (!["single", "multi", "global"].includes(state.mode)) return;
  const previousMode = state.mode;
  resetTrackPlaybackState();
  switchMode("realtime");
  updateMetrics();
  setStatus({
    single: "已退出单船轨迹，回到实时位置展示",
    multi: "已退出多船轨迹，回到实时位置展示",
    global: "已退出全域回放，回到实时位置展示"
  }[previousMode] || "已退出回放，回到实时位置展示");
}

function switchMode(mode) {
  const previousMode = state.mode;
  state.mode = mode;
  if (previousMode !== mode) {
    resetTrackPlaybackState();
  }
  document.querySelectorAll(".nav").forEach((button) => button.classList.toggle("active", button.dataset.mode === mode));
  document.querySelectorAll(".mode-panel").forEach((panel) => panel.classList.add("hidden"));
  $("realtime-stats-section")?.classList.toggle("hidden", mode !== "realtime");
  $("analysis-stats-section")?.classList.toggle("hidden", mode !== "analysis");
  $("single-stats-section")?.classList.toggle("hidden", mode !== "single");
  $("multi-stats-section")?.classList.toggle("hidden", mode !== "multi");
  $("global-stats-section")?.classList.toggle("hidden", mode !== "global");
  $(`${mode}-panel`)?.classList.remove("hidden");
  $("time-section").classList.toggle("hidden", mode !== "multi");
  $("player").classList.toggle("hidden", !["single", "multi", "global"].includes(mode));
  if (["single", "multi", "global"].includes(mode)) {
    syncSamplingControls();
  }
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
  renderCandidateDrawer();
  if (previousMode === "global" && mode !== "global" && state.global.stats.summaryTimer) {
    clearTimeout(state.global.stats.summaryTimer);
    state.global.stats.summaryTimer = null;
  }
  if (mode === "multi") {
    scheduleMultiSummary(previousMode === mode ? 0 : 120);
  } else if (mode === "global") {
    ensureGlobalWindowDefaults();
    scheduleGlobalSummary(previousMode === mode ? 0 : 120);
  } else if (mode !== "single") {
    scheduleRealtimeSummary(previousMode === mode ? 0 : 120);
  }
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
  state.layers.playbackTrackSource?.clear();
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
    canvas.style.transformOrigin = "0 0";
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
  cancelRealtimePanFrame();
  state.layers.realtimeRenderAnchor = null;
  state.layers.realtimeRenderAnchorPixel = null;
  state.layers.realtimeRenderResolution = null;
  state.layers.realtimeHits = [];
  state.layers.realtimeHitGrid = new Map();
  state.layers.realtimeVisibleCount = 0;
}

function canvasShipColor(item) {
  if (Number(item.isAis) === 0) return "#22c55e";
  return AIS_SHIP_FILL;
}

function drawRadarCircle(ctx, x, y, color) {
  const radius = 5.5;
  ctx.save();
  ctx.beginPath();
  ctx.shadowColor = "rgba(15, 23, 42, 0.22)";
  ctx.shadowBlur = 4;
  ctx.shadowOffsetY = 2;
  ctx.arc(x, y, radius + 1.2, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(255,255,255,0.96)";
  ctx.fill();

  ctx.shadowColor = "transparent";
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.fillStyle = color;
  ctx.fill();
  ctx.strokeStyle = "rgba(15,23,42,0.35)";
  ctx.lineWidth = 0.8;
  ctx.stroke();
  ctx.restore();
}

function drawAisShipShape(ctx, scale = 1) {
  const halfWidth = (AIS_SHIP_ICON_WIDTH * scale) / 2;
  const halfHeight = (AIS_SHIP_ICON_HEIGHT * scale) / 2;
  ctx.beginPath();
  ctx.moveTo(-halfWidth, -halfHeight);
  ctx.lineTo(halfWidth, 0);
  ctx.lineTo(-halfWidth, halfHeight);
  ctx.lineTo(-halfWidth * 0.82, 0);
  ctx.closePath();
}

function drawShipTriangle(ctx, x, y, heading, color) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(((Number(heading) || 0) * Math.PI) / 180);

  ctx.shadowColor = "rgba(15, 23, 42, 0.22)";
  ctx.shadowBlur = 4;
  ctx.shadowOffsetY = 2;
  drawAisShipShape(ctx, 1.12);
  ctx.fillStyle = "rgba(255,255,255,0.96)";
  ctx.fill();

  ctx.shadowColor = "transparent";
  drawAisShipShape(ctx, 1);
  ctx.fillStyle = color;
  ctx.fill();
  ctx.strokeStyle = AIS_SHIP_STROKE;
  ctx.lineWidth = AIS_SHIP_STROKE_WIDTH;
  ctx.stroke();
  ctx.restore();
}

function aisShipIconCanvas(color = AIS_SHIP_FILL) {
  const key = color;
  if (AIS_SHIP_ICON_CACHE.has(key)) return AIS_SHIP_ICON_CACHE.get(key);
  const ratio = window.devicePixelRatio || 1;
  const padding = 4;
  const width = AIS_SHIP_ICON_WIDTH + padding * 2;
  const height = AIS_SHIP_ICON_HEIGHT + padding * 2;
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  const ctx = canvas.getContext("2d");
  ctx.scale(ratio, ratio);
  ctx.translate(width / 2, height / 2);
  drawAisShipShape(ctx, 1);
  ctx.fillStyle = color;
  ctx.fill();
  ctx.strokeStyle = AIS_SHIP_STROKE;
  ctx.lineWidth = AIS_SHIP_STROKE_WIDTH;
  ctx.stroke();
  AIS_SHIP_ICON_CACHE.set(key, canvas);
  return canvas;
}

function shipMarkerStyle(point) {
  return new ol.style.Style({
    image: new ol.style.Icon({
      img: aisShipIconCanvas(AIS_SHIP_FILL),
      imgSize: [AIS_SHIP_ICON_WIDTH + 8, AIS_SHIP_ICON_HEIGHT + 8],
      rotation: ((Number(point?.heading) || 0) * Math.PI) / 180,
      rotateWithView: true,
      anchor: [0.5, 0.5]
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
  cancelRealtimePanFrame();
  layer.canvas.style.transform = "";
  state.layers.realtimeRenderAnchor = state.map.getView().getCenter();
  state.layers.realtimeRenderAnchorPixel = state.map.getPixelFromCoordinate(state.layers.realtimeRenderAnchor);
  state.layers.realtimeRenderResolution = state.map.getView().getResolution();
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
      if (Number(item.isAis) === 0) drawRadarCircle(ctx, pixel.x, pixel.y, canvasShipColor(item));
      else drawShipTriangle(ctx, pixel.x, pixel.y, item.heading, canvasShipColor(item));
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
  state.stats.viewportShips = indices.length;
  try {
    renderRealtimeCanvas(indices);
  } catch (error) {
    console.warn("Realtime canvas failed", error);
    clearRealtimeCanvas();
    showError("Realtime canvas failed: " + error.message);
  }
  const elapsed = Math.round(performance.now() - startedAt);
  updateMetrics();
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
        selectRealtimeShip(data.id, data.name || data.id, data.time);
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
  const confirmButton = card.querySelector("#confirm-single-track");
  const cancelButton = card.querySelector("#cancel-single-track");
  if (!confirmButton || !cancelButton) {
    console.error("Single-track confirm card buttons were not rendered");
    return;
  }
  confirmButton.onclick = (event) => {
    event.preventDefault();
    event.stopPropagation();
    selectRealtimeShip(data.id, data.name || data.id, data.time);
  };
  cancelButton.onclick = (event) => {
    event.preventDefault();
    event.stopPropagation();
    clearRealtimeShipConfirm();
  };
  setStatus(`已选中 ${data.name || data.id}，确认后查询单船轨迹`);
}

async function selectRealtimeShip(shipId, shipName, shipTime) {
  if (!shipId) return;
  clearRealtimeShipConfirm();
  $("ship-id").value = shipId;
  if (shipTime) {
    $("single-point").value = toLocalDatetimeString(shipTime);
  }
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

function afterTrackRenderComplete(callback) {
  const seq = state.layers.trackRenderSeq;
  state.layers.trackRenderCallbacks.push({ seq, callback });
  if (!state.layers.lineQueue.length && !state.layers.lineTimer) {
    flushTrackRenderCallbacks(seq);
  }
}

function flushTrackRenderCallbacks(seq = state.layers.trackRenderSeq) {
  const callbacks = state.layers.trackRenderCallbacks.filter((item) => item.seq === seq);
  state.layers.trackRenderCallbacks = state.layers.trackRenderCallbacks.filter((item) => item.seq !== seq);
  callbacks.forEach((item) => {
    setTimeout(() => {
      if (item.seq === state.layers.trackRenderSeq) {
        item.callback();
      }
    }, 0);
  });
}

function renderTracks() {
  if (!state.map) return;
  if (state.layers.lineTimer) {
    clearTimeout(state.layers.lineTimer);
    state.layers.lineTimer = null;
  }
  state.layers.trackRenderSeq += 1;
  state.layers.trackRenderCallbacks = [];
  state.layers.trackSource?.clear();
  state.layers.playbackTrackSource?.clear();
  state.layers.lines = [];
  const palette = ["#2563eb", "#16a34a", "#dc2626", "#d97706", "#7c3aed", "#0891b2", "#be123c"];
  state.layers.lineQueue = Array.from(groupByShip(state.trackPoints).entries()).map(([ship, points], index) => ({ ship, points, index, palette }));
  drawLineBatch();
  renderPlaybackMarkers();
}

function drawLineBatch() {
  state.layers.lineTimer = null;
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
        color: withOpacity(palette[index % palette.length], TRACK_LINE_OPACITY),
        width: state.mode === "global" ? 2 : 4,
        lineJoin: "round",
        lineCap: "round"
      })
    }));
    state.layers.trackSource?.addFeature(line);
    state.layers.lines.push(line);
  });
  if (state.layers.lineQueue.length) {
    state.layers.lineTimer = setTimeout(drawLineBatch, 16);
  } else {
    flushTrackRenderCallbacks();
  }
}

function updatePlaybackTrail() {
  if (!state.map || !["single", "multi", "global"].includes(state.mode)) return;
  state.layers.playbackTrackSource?.clear();
  const visible = state.trackPoints.slice(0, Math.max(1, state.playIndex + 1));
  if (visible.length < 2) return;
  const palette = ["#2563eb", "#16a34a", "#dc2626", "#d97706", "#7c3aed", "#0891b2", "#be123c"];
  const batchWidth = state.mode === "global" ? 2.5 : 4.5;
  const grouped = Array.from(groupByShip(visible).entries());
  grouped.forEach(([ship, points], index) => {
    if (points.length < 2) return;
    const line = new ol.Feature({
      geometry: new ol.geom.LineString(points.map(toMapCoordinate)),
      ship,
      points: points.length
    });
    line.setStyle(new ol.style.Style({
      stroke: new ol.style.Stroke({
        color: withOpacity(palette[index % palette.length], PLAYBACK_LINE_OPACITY),
        width: batchWidth,
        lineJoin: "round",
        lineCap: "round"
      })
    }));
    state.layers.playbackTrackSource?.addFeature(line);
  });
}

function renderPlaybackMarkers() {
  if (!state.map || !["single", "multi", "global"].includes(state.mode)) return;
  state.layers.markerSource?.clear();
  state.layers.markers = [];
  const visible = state.trackPoints.slice(0, Math.max(1, state.playIndex + 1));
  const grouped = Array.from(groupByShip(visible).entries());
  grouped.forEach(([, points]) => {
    const point = points[points.length - 1];
    const marker = new ol.Feature({
      geometry: new ol.geom.Point(toMapCoordinate(point)),
      point
    });
    marker.setStyle(shipMarkerStyle(point));
    state.layers.markerSource?.addFeature(marker);
    state.layers.markers.push(marker);
  });
  updatePlaybackTrail();
  updateMetrics();
}

async function loadLatest() {
  setStatus("正在加载实时船位");
  state.latest = [];
  const windowQuery = realtimeWindowQuery();
  const trace = beginApiTrace("实时位置");
  try {
    const data = await getJson("/api/realtime/latest" + (windowQuery ? "?" + windowQuery : ""), { trace });
    syncRealtimeWindowInputs(data.window);
    syncAnalysisWindowInputs(data.window);
    syncSingleWindowInputs(data.window);
    state.realtimeWindow = data.window || null;
    buildRealtimeStore(normalizeRealtimeItems(data));
    state.stats.memoryShips = Number(data.memoryShips ?? state.latest.length ?? 0);
    const maxTime = state.latest.reduce((max, item) => (item.time > max ? item.time : max), "");
    if (maxTime) {
      const end = new Date(maxTime.replace(" ", "T"));
      const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
      $("start").value = toLocalDatetime(start);
      $("end").value = toLocalDatetime(end);
    }
    renderRealtime();
    updateMetrics();
    scheduleRealtimeSummary(0);
    const sourceText = data.source === "memory" ? "内存缓存" : "数据库查询";
    setStatus(`${sourceText}已加载 ${state.latest.length.toLocaleString()} 条最新船位，当前视野 ${state.stats.viewportShips.toLocaleString()} 艘`);
  } finally {
    finishApiTrace(trace);
  }
}

async function loadDensity() {
  switchMode("analysis");
  setStatus("正在查询态势密度");
  const windowValue = analysisWindowParams();
  if (!windowValue) {
    showError("分析时间窗无效");
    setStatus("分析时间窗无效");
    return;
  }
  const trace = beginApiTrace("态势分析");
  try {
    const params = qs({ ...windowValue, zoom: getMapZoom(), ...(currentDataBBox() || {}) });
    const data = await getJson(`/api/analysis/density?${params}`, { trace });
    state.density = data.items;
    renderHeat();
    updateMetrics();
    scheduleRealtimeSummary(0);
    setStatus(`密度网格 ${state.density.length.toLocaleString()} 个`);
  } finally {
    finishApiTrace(trace);
  }
}

async function loadSingleTrack() {
  switchMode("single");
  setStatus("正在查询单船轨迹");
  const windowValue = singleTrackWindowParams();
  if (!windowValue) {
    showError("单船时间范围无效");
    setStatus("单船时间范围无效");
    return;
  }
  const trace = beginApiTrace("单船轨迹");
  pausePlayback();
  const shipId = $("ship-id").value.trim();
  const statsSeq = ++state.stats.singleStatsSeq;
  state.trackPoints = [];
  state.playIndex = 0;
  state.stats.singleRawTrackPoints = 0;
  state.stats.singleSampledTrackPoints = 0;
  renderTracks();
  updateMetrics();
  syncPlayButtons();
  try {
    const sampling = activeSamplingParams();
    const params = qs({
      shipId,
      ...windowValue,
      zoom: getMapZoom(),
      samplingMode: sampling.samplingMode,
      bucketSeconds: sampling.bucketSeconds
    });
    const data = await getJson(`/api/tracks/single?${params}`, { trace });
    state.trackPoints = data.items || [];
    state.playIndex = 0;
    if (!state.stats.databaseStatsLoaded) {
      refreshDatabaseStats().catch(() => {});
    }
    renderTracks();
    afterTrackRenderComplete(() => {
      if (statsSeq !== state.stats.singleStatsSeq) return;
      state.stats.singleSampledTrackPoints = state.trackPoints.length;
      if (sampling.samplingMode === "raw") {
        state.stats.singleRawTrackPoints = state.trackPoints.length;
        updateMetrics();
      } else {
        updateMetrics();
        refreshSingleTrackPointStats(windowValue, shipId, statsSeq).catch((error) => setStatus("单船统计刷新失败: " + error.message));
      }
    });
    syncPlayButtons();
    setStatus(`单船轨迹 ${state.trackPoints.length.toLocaleString()} 个${playbackPointLabel()}`);
  } finally {
    finishApiTrace(trace);
  }
}

function multiTimeWindowParams() {
  const start = $("start")?.value;
  const end = $("end")?.value;
  if (!start || !end) return null;
  return { start: toIso(start), end: toIso(end) };
}

function selectedCandidateTypes() {
  const types = [];
  if ($("candidate-type-ais")?.checked) types.push("ais");
  if ($("candidate-type-radar")?.checked) types.push("radar");
  return types;
}

function multiQueryBBox() {
  return state.multi.bbox || currentDataBBox();
}

function resetMultiCandidates({ preserveDrawer = false } = {}) {
  state.multi.candidatePages = [];
  state.multi.candidateCurrentPage = 1;
  if (!preserveDrawer) {
    state.multi.drawerVisible = false;
  }
  state.multi.selectedShips = [];
  state.multi.selectedStatsSeq += 1;
  state.multi.selectedRawTrackPoints = 0;
  state.multi.selectedSampledTrackPoints = 0;
  state.multi.candidateQueryElapsedMs = null;
}

function clearMultiBBoxAndCandidates() {
  if (state.layers.drawBoxInteraction) {
    state.map?.removeInteraction(state.layers.drawBoxInteraction);
    state.layers.drawBoxInteraction = null;
  }
  state.layers.rectangleSource?.clear();
  state.multi.bbox = null;
  state.multi.candidateLoadSeq += 1;
  state.multi.stats.summarySeq += 1;
  state.multi.stats.summaryInFlightKey = "";
  state.multi.stats.summaryInFlightPromise = null;
  if (state.multi.stats.summaryTimer) {
    clearTimeout(state.multi.stats.summaryTimer);
    state.multi.stats.summaryTimer = null;
  }
  state.multi.stats.summaryWindowKey = "";
  state.multi.stats.bboxTrackPoints = 0;
  state.multi.stats.bboxShips = 0;
  state.multi.candidateLoading = false;
  state.multi.candidateBatchCount = 0;
  state.multi.candidateHasMore = false;
  resetMultiCandidates({ preserveDrawer: true });
  renderCandidateDrawer();
  updateMetrics();
  setStatus("已清空矩形框和候选船舶");
}

function candidatePageItems(page = state.multi.candidateCurrentPage) {
  return state.multi.candidatePages.find((item) => item.page === page)?.items || [];
}

function loadedCandidateItems() {
  return state.multi.candidatePages.flatMap((page) => page.items);
}

function multiSelectionLimit() {
  return Math.max(1, Number(state.config?.maxMultiShips || 1));
}

function multiSelectionLimitMessage() {
  return `已选中船舶达到上限，超出部分未纳入播放（上限 ${multiSelectionLimit()}）`;
}

function multiSelectionShipIds(shipIds) {
  const limit = multiSelectionLimit();
  const selected = new Set(state.multi.selectedShips);
  for (const shipId of shipIds) {
    if (!shipId) continue;
    selected.add(shipId);
    if (selected.size >= limit) break;
  }
  const ordered = loadedCandidateItems()
    .map((item) => item.shipId)
    .filter((shipId) => selected.has(shipId))
    .slice(0, limit);
  state.multi.selectedShips = ordered;
  return ordered.length >= limit && selected.size > ordered.length;
}

function multiSelectionFromPage(page) {
  return candidatePageItems(page).map((item) => item.shipId);
}

function sortCandidateItems(items) {
  return [...(items || [])].sort((left, right) => {
    const typeRank = Number(right.isAis || 0) - Number(left.isAis || 0);
    if (typeRank !== 0) return typeRank;
    const pointsRank = Number(right.points || 0) - Number(left.points || 0);
    if (pointsRank !== 0) return pointsRank;
    return String(left.shipId || "").localeCompare(String(right.shipId || ""));
  });
}

function renderCandidateDrawer() {
  const drawer = $("candidate-drawer");
  const expandButton = $("candidate-expand");
  if (!drawer) return;
  const hasCandidates = state.multi.candidatePages.length > 0;
  const visible = state.mode === "multi" && state.multi.drawerVisible;
  drawer.classList.toggle("hidden", !visible);
  if (expandButton) {
    expandButton.classList.toggle("hidden", !(state.mode === "multi" && hasCandidates && !visible));
  }
  if (!visible) return;

  const currentPage = Math.max(1, state.multi.candidateCurrentPage);
  const pages = state.multi.candidatePages;
  const pageCount = pages.length;
  const currentItems = candidatePageItems(currentPage);
  $("candidate-loaded-pages").textContent = String(pageCount);
  $("candidate-total-pages").textContent = String(pageCount);
  $("candidate-current-page").textContent = String(currentPage);
  $("candidate-total-loaded").textContent = String(loadedCandidateItems().length);
  $("candidate-selected-count").textContent = String(state.multi.selectedShips.length);
  $("candidate-query-elapsed").textContent = state.multi.candidateQueryElapsedMs == null ? "--" : `${state.multi.candidateQueryElapsedMs} m`;
  $("candidate-prev-page").disabled = currentPage <= 1;
  $("candidate-next-page").disabled = currentPage >= pageCount;
  $("candidate-more-pages").disabled = state.multi.candidateLoading || !state.multi.candidateHasMore;
  $("candidate-select-page").disabled = !currentItems.length;
  $("candidate-select-loaded").disabled = !loadedCandidateItems().length;

  const list = $("candidate-list");
  if (!list) return;
  list.innerHTML = "";
  if (!currentItems.length) {
    const empty = document.createElement("div");
    empty.className = "candidate-empty";
    empty.textContent = pageCount ? "当前页没有候选船舶" : "请先框选范围，候选船舶会自动加载在这里";
    list.append(empty);
    return;
  }
  currentItems.forEach((ship) => {
    const row = document.createElement("label");
    row.className = "candidate-row";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = state.multi.selectedShips.includes(ship.shipId);
    checkbox.onchange = () => {
      if (checkbox.checked) {
        const trimmed = multiSelectionShipIds([ship.shipId]);
        if (trimmed) setStatus(multiSelectionLimitMessage());
      } else {
        state.multi.selectedShips = state.multi.selectedShips.filter((id) => id !== ship.shipId);
      }
      state.multi.selectedStatsSeq += 1;
      state.multi.selectedRawTrackPoints = 0;
      state.multi.selectedSampledTrackPoints = 0;
      renderCandidateDrawer();
    };
    const text = document.createElement("div");
    text.className = "candidate-text";
    const title = document.createElement("strong");
    title.textContent = ship.shipName ? `${ship.shipName} / ${ship.shipId}` : String(ship.shipId || "");
    const meta = document.createElement("span");
    meta.textContent = `${ship.shipType === "ais" ? "AIS" : "雷达"} · ${Number(ship.points || 0).toLocaleString()} 条`;
    row.title = `${ship.shipName ? `${ship.shipName} / ` : ""}${ship.shipId || ""} · ${ship.shipType === "ais" ? "AIS" : "雷达"} · ${Number(ship.points || 0).toLocaleString()} 条 · ${ship.firstTime || "--"} ~ ${ship.lastTime || "--"}`;
    text.append(title, meta);
    row.append(checkbox, text);
    list.append(row);
  });
}

const CANDIDATE_PAGE_SIZE = 100;
const CANDIDATE_BATCH_PAGE_COUNT = 10;
const CANDIDATE_BATCH_SIZE = CANDIDATE_PAGE_SIZE * CANDIDATE_BATCH_PAGE_COUNT;

function chunkCandidateItems(items, startPage) {
  const pages = [];
  for (let index = 0; index < items.length; index += CANDIDATE_PAGE_SIZE) {
    pages.push({
      page: startPage + pages.length,
      items: items.slice(index, index + CANDIDATE_PAGE_SIZE)
    });
  }
  return pages;
}

async function loadCandidateBatch(batchIndex, shipTypes, bbox, trace) {
  const windowValue = multiTimeWindowParams();
  if (!windowValue) {
    throw new Error("多船时间范围无效");
  }
  const params = qs({
    ...windowValue,
    ...bbox,
    page: batchIndex,
    pageSize: CANDIDATE_BATCH_SIZE,
    shipTypes: shipTypes.join(",")
  });
  const data = await getJson(`/api/tracks/candidates?${params}`, { trace });
  return { batchIndex, items: sortCandidateItems(data.items || []) };
}

async function loadCandidates({ append = false } = {}) {
  switchMode("multi");
  if (state.multi.candidateLoading) {
    return;
  }
  const requestSeq = ++state.multi.candidateLoadSeq;
  const startedAt = performance.now();
  const bbox = multiQueryBBox();
  const windowValue = multiTimeWindowParams();
  const shipTypes = selectedCandidateTypes();
  if (!windowValue) {
    showError("多船时间范围无效");
    setStatus("多船时间范围无效");
    return;
  }
  if (!bbox) {
    showError("请先框选船舶范围");
    setStatus("请先框选船舶范围");
    return;
  }
  state.multi.bbox = bbox;
  if (!shipTypes.length) {
    showError("请至少选择一种船舶类型");
    setStatus("请至少选择一种船舶类型");
    return;
  }
  const trace = beginApiTrace("候选船舶");
  state.multi.candidateLoading = true;
  renderCandidateDrawer();
  const batchIndex = append ? state.multi.candidateBatchCount + 1 : 1;
  const startPage = append ? state.multi.candidatePages.length + 1 : 1;
  setStatus(append ? `正在继续加载候选船舶第 ${batchIndex} 批` : "正在查询候选船舶");
  try {
    const nextPages = append
      ? state.multi.candidatePages.map((item) => ({ page: item.page, items: item.items.slice() }))
      : [];
    const result = await loadCandidateBatch(batchIndex, shipTypes, bbox, trace);
    const batchPages = chunkCandidateItems(result.items || [], startPage);
    for (const resultPage of batchPages) {
      const existingIndex = nextPages.findIndex((item) => item.page === resultPage.page);
      if (existingIndex >= 0) {
        nextPages[existingIndex] = resultPage;
      } else {
        nextPages.push(resultPage);
      }
    }
    if (requestSeq !== state.multi.candidateLoadSeq) {
      return;
    }
    nextPages.sort((left, right) => left.page - right.page);
    state.multi.candidatePages = nextPages;
    state.multi.candidateBatchCount = batchIndex;
    state.multi.candidateHasMore = (result.items || []).length === CANDIDATE_BATCH_SIZE;
    state.multi.selectedShips = append ? state.multi.selectedShips.filter((id) => loadedCandidateItems().some((item) => item.shipId === id)) : [];
    state.multi.drawerVisible = true;
    state.multi.candidateCurrentPage = append ? Math.min(state.multi.candidateCurrentPage, state.multi.candidatePages.length) : 1;
    state.multi.candidateQueryElapsedMs = Math.max(0, Math.round(performance.now() - startedAt));
    renderCandidateDrawer();
    scheduleMultiSummary(0);
    setStatus(`候选船舶已加载 ${state.multi.candidatePages.length} 页，当前共 ${loadedCandidateItems().length.toLocaleString()} 艘，查询耗时 ${state.multi.candidateQueryElapsedMs} ms`);
  } catch (error) {
    state.multi.candidateQueryElapsedMs = Math.max(0, Math.round(performance.now() - startedAt));
    renderCandidateDrawer();
    throw error;
  } finally {
    state.multi.candidateLoading = false;
    renderCandidateDrawer();
    finishApiTrace(trace);
  }
}

function selectCandidatePage(page) {
  const ids = multiSelectionFromPage(page);
  if (!ids.length) return;
  const trimmed = multiSelectionShipIds(ids);
  state.multi.selectedStatsSeq += 1;
  state.multi.selectedRawTrackPoints = 0;
  state.multi.selectedSampledTrackPoints = 0;
  renderCandidateDrawer();
  if (trimmed) setStatus(multiSelectionLimitMessage());
}

function selectLoadedCandidatePages() {
  const ids = loadedCandidateItems().map((item) => item.shipId);
  if (!ids.length) return;
  const trimmed = multiSelectionShipIds(ids);
  state.multi.selectedStatsSeq += 1;
  state.multi.selectedRawTrackPoints = 0;
  state.multi.selectedSampledTrackPoints = 0;
  renderCandidateDrawer();
  if (trimmed) setStatus(multiSelectionLimitMessage());
}

function gotoCandidatePage(delta) {
  const nextPage = Math.min(Math.max(1, state.multi.candidateCurrentPage + delta), Math.max(1, state.multi.candidatePages.length));
  if (nextPage === state.multi.candidateCurrentPage) return;
  state.multi.candidateCurrentPage = nextPage;
  renderCandidateDrawer();
}

function drawBox() {
  switchMode("multi");
  setStatus("拖拽地图框选船舶范围");
  if (state.layers.drawBoxInteraction) {
    state.map.removeInteraction(state.layers.drawBoxInteraction);
    state.layers.drawBoxInteraction = null;
  }
  const dragBox = new ol.interaction.DragBox({ condition: ol.events.condition.always });
  dragBox.on("boxend", () => {
    const extent = dragBox.getGeometry().getExtent();
    state.layers.rectangleSource?.clear();
    const rectangle = new ol.Feature({ geometry: ol.geom.Polygon.fromExtent(extent) });
    state.layers.rectangleSource?.addFeature(rectangle);
    state.map.removeInteraction(dragBox);
    state.layers.drawBoxInteraction = null;
    const sw = ol.proj.toLonLat([extent[0], extent[1]]);
    const ne = ol.proj.toLonLat([extent[2], extent[3]]);
    state.multi.bbox = toQueryBBoxFromLngLat(sw, ne);
    resetMultiCandidates();
    renderCandidateDrawer();
    setStatus("框选完成，正在加载候选船舶");
    loadCandidates().catch((error) => showError(error.message));
  });
  state.layers.drawBoxInteraction = dragBox;
  state.map.addInteraction(dragBox);
}

async function loadMultiTrack() {
  switchMode("multi");
  const shipIds = state.multi.selectedShips.slice(0, state.config.maxMultiShips);
  if (!shipIds.length) {
    showError("请先选择候选船舶");
    setStatus("请先选择候选船舶");
    return;
  }
  setStatus("正在查询多船轨迹");
  const windowValue = multiTimeWindowParams();
  if (!windowValue) {
    showError("多船时间范围无效");
    setStatus("多船时间范围无效");
    return;
  }
  const trace = beginApiTrace("多船轨迹");
  pausePlayback();
  const statsSeq = ++state.multi.selectedStatsSeq;
  state.trackPoints = [];
  state.playIndex = 0;
  state.multi.selectedRawTrackPoints = 0;
  state.multi.selectedSampledTrackPoints = 0;
  renderTracks();
  updateMetrics();
  syncPlayButtons();
  try {
    const sampling = activeSamplingParams();
    const data = await postJson("/api/tracks/multi", {
      shipIds,
      start: windowValue.start,
      end: windowValue.end,
      zoom: getMapZoom(),
      samplingMode: sampling.samplingMode,
      bucketSeconds: sampling.bucketSeconds
    }, { trace });
    state.trackPoints = data.items || [];
    state.playIndex = 0;
    renderTracks();
    afterTrackRenderComplete(() => {
      if (statsSeq !== state.multi.selectedStatsSeq) return;
      state.multi.selectedSampledTrackPoints = state.trackPoints.length;
      if (sampling.samplingMode === "raw") {
        state.multi.selectedRawTrackPoints = state.trackPoints.length;
        updateMetrics();
      } else {
        updateMetrics();
        refreshSelectedMultiTrackPointStats(windowValue, shipIds, statsSeq).catch((error) => setStatus("多船统计刷新失败: " + error.message));
      }
    });
    syncPlayButtons();
    setStatus(`多船轨迹 ${state.trackPoints.length.toLocaleString()} 个${playbackPointLabel()}`);
  } finally {
    finishApiTrace(trace);
  }
}

async function loadGlobalSegment() {
  switchMode("global");
  setStatus("正在加载全域回放");
  const windowValue = globalWindowParams();
  if (!windowValue) {
    showError("全域时间范围无效");
    setStatus("全域时间范围无效");
    return;
  }
  const trace = beginApiTrace("全域回放");
  pausePlayback();
  const statsSeq = ++state.global.stats.statsSeq;
  state.trackPoints = [];
  state.playIndex = 0;
  state.global.stats.sampledTrackPoints = 0;
  renderTracks();
  updateMetrics();
  syncPlayButtons();
  try {
    const sampling = activeSamplingParams();
    const params = qs({
      ...windowValue,
      zoom: getMapZoom(),
      samplingMode: sampling.samplingMode,
      bucketSeconds: sampling.bucketSeconds
    });
    const data = await getJson(`/api/tracks/global-segment?${params}`, { trace });
    state.trackPoints = data.items || [];
    state.playIndex = 0;
    renderTracks();
    afterTrackRenderComplete(() => {
      if (statsSeq !== state.global.stats.statsSeq) return;
      state.global.stats.sampledTrackPoints = state.trackPoints.length;
      updateMetrics();
      scheduleGlobalSummary(0);
    });
    syncPlayButtons();
    const end = new Date(windowValue.timePoint);
    const start = new Date(end.getTime() - Number(windowValue.hours || 1) * 60 * 60 * 1000);
    setStatus(`已加载 ${toLocalDatetime(start)} - ${toLocalDatetime(end)}，${(data.items || []).length.toLocaleString()} 个${playbackPointLabel()}`);
  } finally {
    finishApiTrace(trace);
  }
}

function tickPlayer() {
  if (state.playing && state.trackPoints.length) {
    try {
      state.playIndex = Math.min(state.trackPoints.length - 1, state.playIndex + 1);
      $("progress").value = state.playIndex;
      renderPlaybackMarkers();
      if (state.playIndex >= state.trackPoints.length - 1) {
        state.playing = false;
        syncPlayButtons();
      }
    } catch (error) {
      console.error("Playback tick failed", error);
      state.playing = false;
      syncPlayButtons();
    }
  }
  const speed = Math.max(1, Number(state.speed) || 1);
  setTimeout(tickPlayer, Math.max(16, 400 / speed));
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
  state.layers.playbackTrackSource = new ol.source.Vector();
  state.layers.markerSource = new ol.source.Vector();
  state.layers.rectangleSource = new ol.source.Vector();
  state.layers.trackLayer = new ol.layer.Vector({ source: state.layers.trackSource, zIndex: 30 });
  state.layers.playbackTrackLayer = new ol.layer.Vector({ source: state.layers.playbackTrackSource, zIndex: 31 });
  state.layers.markerLayer = new ol.layer.Vector({ source: state.layers.markerSource, zIndex: 40 });
  state.layers.rectangleLayer = new ol.layer.Vector({
    source: state.layers.rectangleSource,
    zIndex: 35,
    style: lineAndRectangleStyle("#2563eb", 2, 0.08)
  });

  state.map = new ol.Map({
    target: "map",
    layers: [createAmapTileLayer(), state.layers.trackLayer, state.layers.playbackTrackLayer, state.layers.rectangleLayer, state.layers.markerLayer],
    view: new ol.View({
      center: ol.proj.fromLonLat(center),
      zoom: state.config.defaultZoom
    }),
    controls: ol.control.defaults.defaults().extend([new ol.control.ScaleLine()])
  });

  state.map.on("movestart", syncRealtimeCanvasDuringMove);
  state.map.on("pointerdrag", syncRealtimeCanvasDuringMove);
  state.map.on("moveend", () => {
    renderRealtimeNow();
    if (state.mode === "analysis") scheduleRealtimeSummary();
  });
  state.map.getView().on("change:resolution", () => {
    syncRealtimeCanvasDuringMove();
    scheduleRealtimeRender();
    if (state.mode === "analysis") scheduleRealtimeSummary();
  });
  state.map.on("pointermove", handleRealtimePointerMove);
  state.map.on("click", handleRealtimeClick);
  $("map").addEventListener("mouseleave", hideShipInfo);
}

async function init() {
  try {
    state.apiTrace.hidden = loadApiTraceHidden();
    $("api-trace-toggle").onclick = () => setApiTraceHidden(false);
    state.config = await getJson("/api/config/map");
    initMap();
    document.addEventListener("click", handleRealtimeDomClick, true);
    window.addEventListener("resize", () => {
      state.map.updateSize();
      scheduleRealtimeRender(50);
    }, { passive: true });
    bindEvents();
    refreshDatabaseStats().catch(() => {});
    await loadLatest().catch((error) => {
      showError(error.message);
      setStatus("Database is unavailable; map page loaded");
    });
    connectWebSocket();
    syncSamplingControls();
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
  $("realtime-point").onchange = () => loadLatest().catch((error) => showError(error.message));
  $("realtime-minutes").onchange = () => loadLatest().catch((error) => showError(error.message));
  $("realtime-type").onchange = () => {
    if (state.mode === "realtime") {
      renderRealtime();
      updateMetrics();
    }
  };
  $("load-density").onclick = () => loadDensity().catch((error) => showError(error.message));
  $("analysis-point").onchange = () => {
    scheduleRealtimeSummary(0);
  };
  $("analysis-minutes").onchange = () => {
    scheduleRealtimeSummary(0);
  };
  $("start").onchange = () => {
    if (state.mode === "multi") {
      scheduleMultiSummary(0);
    } else {
      scheduleRealtimeSummary(0);
    }
  };
  $("end").onchange = () => {
    if (state.mode === "multi") {
      scheduleMultiSummary(0);
    } else {
      scheduleRealtimeSummary(0);
    }
  };
  $("load-single").onclick = () => loadSingleTrack().catch((error) => showError(error.message));
  $("single-point").onchange = () => {
    if (state.mode === "single") {
      loadSingleTrack().catch((error) => showError(error.message));
    }
  };
  $("single-before-hours").onchange = () => {
    if (state.mode === "single") {
      loadSingleTrack().catch((error) => showError(error.message));
    }
  };
  $("single-after-hours").onchange = () => {
    if (state.mode === "single") {
      loadSingleTrack().catch((error) => showError(error.message));
    }
  };
  $("track-sampling-mode").onchange = () => {
    syncSamplingControls();
    if (["single", "multi", "global"].includes(state.mode)) {
      reloadActivePlaybackTrack().catch((error) => showError(error.message));
    }
  };
  $("track-sampling-bucket").onchange = () => {
    syncSamplingControls();
    if (state.mode === "single" || state.mode === "multi" || state.mode === "global") {
      if (state.sampling.mode === "manual") {
        reloadActivePlaybackTrack().catch((error) => showError(error.message));
      }
    }
  };
  $("draw-box").onclick = drawBox;
  $("clear-box").onclick = clearMultiBBoxAndCandidates;
  $("candidate-type-ais").onchange = () => {
    if (state.mode === "multi") {
      loadCandidates().catch((error) => showError(error.message));
    }
  };
  $("candidate-type-radar").onchange = () => {
    if (state.mode === "multi") {
      loadCandidates().catch((error) => showError(error.message));
    }
  };
  $("candidate-prev-page").onclick = () => {
    if (state.mode === "multi") gotoCandidatePage(-1);
  };
  $("candidate-next-page").onclick = () => {
    if (state.mode === "multi") gotoCandidatePage(1);
  };
  $("candidate-more-pages").onclick = () => loadCandidates({ append: true }).catch((error) => showError(error.message));
  $("candidate-select-page").onclick = () => {
    if (state.mode === "multi") selectCandidatePage(state.multi.candidateCurrentPage);
  };
  $("candidate-select-loaded").onclick = () => {
    if (state.mode === "multi") selectLoadedCandidatePages();
  };
  $("candidate-load-track").onclick = () => loadMultiTrack().catch((error) => showError(error.message));
  $("candidate-close").onclick = () => {
    state.multi.drawerVisible = false;
    renderCandidateDrawer();
  };
  $("candidate-expand").onclick = () => {
    state.multi.drawerVisible = true;
    renderCandidateDrawer();
  };
  $("load-global").onclick = () => loadGlobalSegment().catch((error) => showError(error.message));
  $("global-point").onchange = () => {
    if (state.mode === "global") {
      scheduleGlobalSummary(0);
    }
  };
  $("global-hours").onchange = () => {
    if (state.mode === "global") {
      scheduleGlobalSummary(0);
    }
  };
  $("exit-playback").onclick = () => exitPlayback();
  $("play").onclick = togglePlayback;
  $("speed").onchange = () => {
    state.speed = Number($("speed").value);
    syncPlayButtons();
  };
  $("progress").oninput = () => {
    state.playIndex = Number($("progress").value);
    renderPlaybackMarkers();
  };
  syncPlayButtons();
}

init();


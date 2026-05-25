# 后端接口文档

本文档描述船舶态势与轨迹回放服务的后端 HTTP API。接口由 Spring Boot 提供，默认服务地址为：

```text
http://127.0.0.1:3001
```

## 1. 通用约定

### 1.1 数据格式

- 请求参数：`GET` 接口使用 query string；`POST` 接口使用 JSON 请求体。
- 返回格式：所有接口默认返回 JSON。
- 字符编码：UTF-8。
- 时间字段：支持 ISO-8601 格式，例如 `2026-04-30T08:00:00Z`；也支持 ClickHouse 本地时间字符串，例如 `2026-04-30 16:00:00`。
- 时间窗口：`start` 必须早于 `end`。
- 坐标系统：默认配置为 `wgs84`。
- 经纬度边界框：
  - `west`：西边界经度。
  - `south`：南边界纬度。
  - `east`：东边界经度。
  - `north`：北边界纬度。
  - 要求 `west < east` 且 `south < north`。

### 1.2 抽稀参数

轨迹类接口支持抽稀参数，用于控制返回点数量，避免前端渲染压力过大。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `samplingMode` | string | 否 | 抽稀模式。可选值：`auto`、`manual`、`raw`。默认 `auto`。 |
| `bucketSeconds` | integer | 否 | 手动抽稀粒度，单位秒。服务端归一到 `60`、`300`、`1800`。 |
| `zoom` | integer | 否 | 当前地图缩放级别。合法范围 3-18。缺省使用配置中的默认缩放级别。 |

`samplingMode` 说明：

| 值 | 说明 |
| --- | --- |
| `auto` | 后端根据缩放级别选择 ClickHouse 固定抽稀粒度。 |
| `manual` | 使用 `bucketSeconds` 选择 ClickHouse 固定抽稀粒度。 |
| `raw` | 单船回放返回原始轨迹点，不做抽稀。多船和全域回放会继续使用抽稀轨迹，避免放大查询和渲染压力。 |

当前实现使用 ClickHouse 物化视图维护的固定抽稀表：

- 原始轨迹表为 `tb_ais_track_raw`。
- 固定抽稀轨迹表为 `tb_ais_track_thin`，支持 `bucket_size=60/300/1800`。
- `samplingMode=auto` 和 `samplingMode=manual` 查询固定抽稀表，不回退到原始表 bucket 聚合。
- `samplingMode=raw` 仅在单船回放查询原始表；多船和全域回放仍查询抽稀表。
- 轨迹查询接口不在业务 SQL 中增加结果 `LIMIT`；候选船、实时最新船位、密度网格等非轨迹明细接口仍保留各自已有上限。

`zoom` 到抽稀粒度的默认映射：

| zoom 范围 | bucket_size | 说明 |
| --- | --- | --- |
| `<= 7` | `1800` | 30 分钟抽稀，适合低缩放全局视野。 |
| `8-10` | `300` | 5 分钟抽稀。 |
| `>= 11` | `60` | 1 分钟抽稀；仍不是原始全量。 |

### 1.3 通用错误响应

当参数非法、ClickHouse 查询失败或服务异常时，返回：

```json
{
  "error": "time window range is invalid"
}
```

常见 HTTP 状态码：

| 状态码 | 场景 |
| --- | --- |
| `200` | 请求成功。 |
| `500` | 参数非法、查询异常或服务内部错误。 |
| `503` | ClickHouse 连接失败。 |

## 2. 接口索引

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `GET` | `/api/config/map` | 获取前端地图和查询限制配置。 |
| `GET` | `/api/realtime/latest` | 获取实时窗口内每艘船的最新船位。 |
| `GET` | `/api/stats/realtime-summary` | 获取实时态势统计摘要。 |
| `GET` | `/api/stats/database` | 获取全库轨迹点和船舶数量统计。 |
| `GET` | `/api/stats/global-summary` | 获取全域回放窗口统计摘要。 |
| `GET` | `/api/stats/multi-summary` | 获取多船框选窗口统计摘要。 |
| `GET` | `/api/stats/single-track-points` | 获取单船原始轨迹点数量。 |
| `POST` | `/api/stats/multi-track-points` | 获取多船原始轨迹点数量。 |
| `GET` | `/api/analysis/density` | 获取指定视野和时间窗口内的密度网格。 |
| `GET` | `/api/tracks/single` | 查询单船轨迹。 |
| `GET` | `/api/tracks/candidates` | 查询框选范围内的候选船舶。 |
| `POST` | `/api/tracks/multi` | 查询多船轨迹。 |
| `GET` | `/api/tracks/global-segment` | 查询全域轨迹回放片段。 |
| `GET` | `/ws/realtime` | 实时 WebSocket 推送连接。 |

## 3. 接口详情

### 3.1 获取地图配置

```http
GET /api/config/map
```

功能描述：获取前端初始化所需的地图配置、最大多船选择数量和全域回放默认窗口。

请求参数：无。

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `coordinateSystem` | string | 坐标系统，例如 `wgs84`。 |
| `defaultCenter` | number[] | 地图默认中心点，格式 `[lng, lat]`。 |
| `defaultZoom` | integer | 地图默认缩放级别。 |
| `maxMultiShips` | integer | 多船轨迹一次最多选择的船舶数量。 |
| `globalSegmentHours` | integer | 全域回放默认回看小时数。 |
| `amapKey` | string | 高德 Web JS API Key，未配置时为空字符串。 |
| `amapSecurityJsCode` | string | 高德安全密钥，未配置时为空字符串。 |

响应示例：

```json
{
  "coordinateSystem": "wgs84",
  "defaultCenter": [122.4, 39.2],
  "defaultZoom": 8,
  "maxMultiShips": 1000,
  "globalSegmentHours": 1,
  "amapKey": "",
  "amapSecurityJsCode": ""
}
```

### 3.2 获取实时最新船位

```http
GET /api/realtime/latest
```

功能描述：获取指定实时窗口内每艘船的最新位置。后端优先返回内存缓存，缓存未命中时会查询 ClickHouse 并刷新缓存。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `start` | string | 否 | 窗口开始时间。与 `end` 一起传入时生效。 |
| `end` | string | 否 | 窗口结束时间。与 `start` 一起传入时生效。 |
| `timePoint` | string | 否 | 实时窗口锚点时间。传入后，后端按 `minutes` 向前推算 `start`。 |
| `minutes` | integer | 否 | 实时窗口分钟数，默认取配置 `query.realtimeWindowMinutes`。 |

时间窗口优先级：

1. 如果传入 `timePoint` 或 `minutes`，使用 `timePoint - minutes` 到 `timePoint`。
2. 否则如果传入 `start` 或 `end`，使用 `start` 到 `end`。
3. 否则使用数据库最新时间向前回看默认分钟数。

请求示例：

```http
GET /api/realtime/latest?timePoint=2026-04-30T08:00:00Z&minutes=10
```

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `source` | string | 数据来源。`memory` 表示内存缓存，`clickhouse` 表示数据库查询。 |
| `compact` | boolean | 是否为紧凑数组结构，当前固定为 `true`。 |
| `fields` | string[] | `items` 每行数组对应字段顺序。 |
| `ready` | boolean | 当前窗口缓存是否就绪。 |
| `warming` | boolean | 可选。是否正在刷新缓存。 |
| `window` | object | 实际查询时间窗口。 |
| `watermark` | string | 当前返回数据中的最大时间。 |
| `memoryShips` | integer | 内存缓存中的船舶数量。 |
| `memoryRows` | integer | 本次返回的行数。 |
| `items` | array[] | 船位行数组，字段顺序由 `fields` 指定。 |
| `nextCursor` | string | 当前固定为空字符串。 |
| `hasMore` | boolean | 当前固定为 `false`。 |

`fields` 固定顺序：

```json
["shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis", "shipType"]
```

响应示例：

```json
{
  "source": "memory",
  "compact": true,
  "fields": ["shipId", "shipName", "lng", "lat", "speed", "heading", "time", "isAis", "shipType"],
  "ready": true,
  "window": {
    "start": "2026-04-30 15:50:00",
    "end": "2026-04-30 16:00:00"
  },
  "watermark": "2026-04-30 15:59:58",
  "memoryShips": 2,
  "memoryRows": 2,
  "items": [
    ["SHIP001", "测试船A", 122.41, 39.21, 10.5, 85.0, "2026-04-30 15:59:58", 1, 1],
    ["SHIP002", "测试船B", 122.52, 39.33, 8.2, 120.0, "2026-04-30 15:59:55", 1, 5]
  ],
  "nextCursor": "",
  "hasMore": false
}
```

### 3.3 获取实时态势统计摘要

```http
GET /api/stats/realtime-summary
```

功能描述：获取全库统计、实时窗口统计、热力网格数量和当前视野热力网格数量，用于前端指标面板。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `start` | string | 否 | 窗口开始时间。 |
| `end` | string | 否 | 窗口结束时间。 |
| `timePoint` | string | 否 | 实时窗口锚点时间。 |
| `minutes` | integer | 否 | 实时窗口分钟数。 |
| `west` | number | 否 | 当前视野西边界。 |
| `south` | number | 否 | 当前视野南边界。 |
| `east` | number | 否 | 当前视野东边界。 |
| `north` | number | 否 | 当前视野北边界。 |
| `zoom` | integer | 否 | 当前地图缩放级别。 |

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `databaseTrackPoints` | integer | 全库轨迹点数量。 |
| `databaseShips` | integer | 全库船舶数量。 |
| `windowTrackPoints` | integer | 时间窗口内轨迹点数量。 |
| `windowShips` | integer | 时间窗口内船舶数量。 |
| `windowHeatCells` | integer | 时间窗口内全范围热力网格数量。 |
| `viewportHeatCells` | integer | 当前视野内热力网格数量。未传 bbox 时为 0。 |

响应示例：

```json
{
  "databaseTrackPoints": 125000000,
  "databaseShips": 350000,
  "windowTrackPoints": 820000,
  "windowShips": 24500,
  "windowHeatCells": 18000,
  "viewportHeatCells": 1250
}
```

### 3.4 获取全库统计

```http
GET /api/stats/database
```

功能描述：获取全库轨迹点数量和船舶数量。

请求参数：无。

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `trackPoints` | integer | 全库轨迹点数量。 |
| `ships` | integer | 全库船舶数量。 |

响应示例：

```json
{
  "trackPoints": 125000000,
  "ships": 350000
}
```

### 3.5 获取全域回放统计摘要

```http
GET /api/stats/global-summary
```

功能描述：获取全域回放窗口内的轨迹点数量，并返回全库总量指标。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `timePoint` | string | 否 | 全域回放结束时间。不传时使用数据库最新时间。 |
| `hours` | integer | 否 | 向前回看的小时数，默认取配置 `query.globalSegmentHours`。 |

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `databaseTrackPoints` | integer | 全库轨迹点数量。 |
| `databaseShips` | integer | 全库船舶数量。 |
| `windowTrackPoints` | integer | 全域回放窗口内轨迹点数量。 |

响应示例：

```json
{
  "databaseTrackPoints": 125000000,
  "databaseShips": 350000,
  "windowTrackPoints": 9600000
}
```

### 3.6 获取多船框选统计摘要

```http
GET /api/stats/multi-summary
```

功能描述：获取多船模式下时间窗口总量和框选范围内统计量。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `start` | string | 是 | 窗口开始时间。 |
| `end` | string | 是 | 窗口结束时间。 |
| `west` | number | 否 | 框选范围西边界。 |
| `south` | number | 否 | 框选范围南边界。 |
| `east` | number | 否 | 框选范围东边界。 |
| `north` | number | 否 | 框选范围北边界。 |

请求示例：

```http
GET /api/stats/multi-summary?start=2026-04-30T07:00:00Z&end=2026-04-30T08:00:00Z&west=122.1&south=38.9&east=122.8&north=39.6
```

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `windowTrackPoints` | integer | 时间窗口内全范围轨迹点数量。 |
| `windowShips` | integer | 时间窗口内全范围船舶数量。 |
| `bboxTrackPoints` | integer | 框选范围内轨迹点数量。未传 bbox 时为 0。 |
| `bboxShips` | integer | 框选范围内船舶数量。未传 bbox 时为 0。 |

响应示例：

```json
{
  "windowTrackPoints": 9600000,
  "windowShips": 86000,
  "bboxTrackPoints": 180000,
  "bboxShips": 3200
}
```

### 3.7 获取单船原始轨迹点数量

```http
GET /api/stats/single-track-points
```

功能描述：统计单艘船在指定时间窗口内的原始轨迹点数量。通常用于前端在抽稀轨迹之外展示原始点总量。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `shipId` | string | 是 | 船舶编号。 |
| `start` | string | 是 | 窗口开始时间。 |
| `end` | string | 是 | 窗口结束时间。 |

响应示例：

```json
{
  "trackPoints": 12345
}
```

### 3.8 获取多船原始轨迹点数量

```http
POST /api/stats/multi-track-points
Content-Type: application/json
```

功能描述：统计多艘船在指定时间窗口内的原始轨迹点总数。后端会按 `maxMultiShips` 限制截断 `shipIds`。

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `shipIds` | string[] | 是 | 船舶编号列表。 |
| `start` | string | 是 | 窗口开始时间。 |
| `end` | string | 是 | 窗口结束时间。 |

请求示例：

```json
{
  "shipIds": ["SHIP001", "SHIP002"],
  "start": "2026-04-30T07:00:00Z",
  "end": "2026-04-30T08:00:00Z"
}
```

响应示例：

```json
{
  "trackPoints": 24680
}
```

### 3.9 获取态势密度网格

```http
GET /api/analysis/density
```

功能描述：按地图缩放级别计算网格大小，聚合指定时间窗口和 bbox 范围内的轨迹点密度，用于前端热力图展示。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `start` | string | 否 | 窗口开始时间。 |
| `end` | string | 否 | 窗口结束时间。 |
| `timePoint` | string | 否 | 实时窗口锚点时间。 |
| `minutes` | integer | 否 | 实时窗口分钟数。 |
| `west` | number | 是 | 查询范围西边界。 |
| `south` | number | 是 | 查询范围南边界。 |
| `east` | number | 是 | 查询范围东边界。 |
| `north` | number | 是 | 查询范围北边界。 |
| `zoom` | integer | 否 | 当前地图缩放级别。 |

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `items` | object[] | 密度网格列表。 |
| `items[].lng` | number | 网格中心经度。 |
| `items[].lat` | number | 网格中心纬度。 |
| `items[].count` | integer | 网格内轨迹点数量。 |
| `items[].ships` | integer | 网格内去重船舶数量。 |

响应示例：

```json
{
  "items": [
    {
      "lng": 122.405,
      "lat": 39.205,
      "count": 860,
      "ships": 42
    }
  ]
}
```

### 3.10 查询单船轨迹

```http
GET /api/tracks/single
```

功能描述：查询单艘船在指定时间窗口内的轨迹点，支持自动抽稀、手动抽稀和原始点返回。`auto/manual` 查询固定抽稀表；`raw` 返回原始表数据。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `shipId` | string | 是 | 船舶编号。 |
| `start` | string | 是 | 窗口开始时间。 |
| `end` | string | 是 | 窗口结束时间。 |
| `zoom` | integer | 否 | 当前地图缩放级别。 |
| `samplingMode` | string | 否 | 抽稀模式：`auto`、`manual`、`raw`。 |
| `bucketSeconds` | integer | 否 | 手动抽稀粒度，服务端归一到 `60`、`300`、`1800`。 |

请求示例：

```http
GET /api/tracks/single?shipId=SHIP001&start=2026-04-30T07:00:00Z&end=2026-04-30T08:00:00Z&zoom=10&samplingMode=auto
```

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `items` | object[] | 轨迹点列表，按时间升序排列。 |
| `items[].shipId` | string | 船舶编号。 |
| `items[].shipName` | string | 船名。 |
| `items[].lng` | number | 经度。 |
| `items[].lat` | number | 纬度。 |
| `items[].speed` | number | 航速。 |
| `items[].heading` | number | 航向。 |
| `items[].time` | string | 轨迹点时间。 |

响应示例：

```json
{
  "items": [
    {
      "shipId": "SHIP001",
      "shipName": "测试船A",
      "lng": 122.41,
      "lat": 39.21,
      "speed": 10.5,
      "heading": 85.0,
      "time": "2026-04-30 15:00:00"
    },
    {
      "shipId": "SHIP001",
      "shipName": "测试船A",
      "lng": 122.43,
      "lat": 39.24,
      "speed": 10.8,
      "heading": 88.0,
      "time": "2026-04-30 15:01:00"
    }
  ]
}
```

### 3.11 查询候选船舶

```http
GET /api/tracks/candidates
```

功能描述：在多船模式下，根据时间窗口和框选范围查询候选船舶列表。后端只使用桶索引表聚合空间候选和点数，并按点数和船舶编号排序。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `start` | string | 是 | 窗口开始时间。 |
| `end` | string | 是 | 窗口结束时间。 |
| `west` | number | 是 | 框选范围西边界。 |
| `south` | number | 是 | 框选范围南边界。 |
| `east` | number | 是 | 框选范围东边界。 |
| `north` | number | 是 | 框选范围北边界。 |
| `page` | integer | 否 | 页码，默认 1。 |
| `pageSize` | integer | 否 | 每页数量，默认 100，最大受配置 `query.maxCandidateBatchSize` 限制。 |

请求示例：

```http
GET /api/tracks/candidates?start=2026-04-30T07:00:00Z&end=2026-04-30T08:00:00Z&west=122.1&south=38.9&east=122.8&north=39.6&page=1&pageSize=100
```

响应字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `items` | object[] | 候选船舶列表。 |
| `items[].shipId` | string | 船舶编号。 |
| `items[].shipName` | string | 当前三表结构不含船名，返回船舶编号。 |
| `items[].firstTime` | string | 该船在查询窗口内的最早命中时间。 |
| `items[].lastTime` | string | 该船在查询窗口内的最晚命中时间。 |
| `items[].points` | integer | 该船在查询窗口和范围内的点数。 |

响应示例：

```json
{
  "items": [
    {
      "shipId": "SHIP001",
      "shipName": "测试船A",
      "firstTime": "2026-04-30 15:02:00",
      "lastTime": "2026-04-30 15:58:00",
      "points": 180
    }
  ]
}
```

### 3.12 查询多船轨迹

```http
POST /api/tracks/multi
Content-Type: application/json
```

功能描述：查询多艘船在指定时间窗口内的轨迹点，支持自动抽稀和手动抽稀。后端会按 `maxMultiShips` 限制截断 `shipIds`。多船回放始终查询固定抽稀表；即使传入 `raw` 也按自动抽稀处理。

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `shipIds` | string[] | 是 | 船舶编号列表。 |
| `start` | string | 是 | 窗口开始时间。 |
| `end` | string | 是 | 窗口结束时间。 |
| `zoom` | integer | 否 | 当前地图缩放级别。 |
| `samplingMode` | string | 否 | 抽稀模式：`auto`、`manual`、`raw`。 |
| `bucketSeconds` | integer | 否 | 手动抽稀粒度，服务端归一到 `60`、`300`、`1800`。 |

说明：

- 请求体类中存在 `bbox` 字段，但当前控制器调用仓库时未使用该字段，多船轨迹接口实际不按 `bbox` 过滤。
- 前端多船回放加载的数据仍作为播放事件源，但地图上只展示当前播放时刻向前 30 分钟的轨迹窗口，不再展示整段全量轨迹线。

请求示例：

```json
{
  "shipIds": ["SHIP001", "SHIP002"],
  "start": "2026-04-30T07:00:00Z",
  "end": "2026-04-30T08:00:00Z",
  "zoom": 10,
  "samplingMode": "manual",
  "bucketSeconds": 60
}
```

响应字段同“查询单船轨迹”。

响应示例：

```json
{
  "items": [
    {
      "shipId": "SHIP001",
      "shipName": "测试船A",
      "lng": 122.41,
      "lat": 39.21,
      "speed": 10.5,
      "heading": 85.0,
      "time": "2026-04-30 15:00:00"
    },
    {
      "shipId": "SHIP002",
      "shipName": "测试船B",
      "lng": 122.52,
      "lat": 39.33,
      "speed": 8.2,
      "heading": 120.0,
      "time": "2026-04-30 15:00:10"
    }
  ]
}
```

### 3.13 查询全域回放片段

```http
GET /api/tracks/global-segment
```

功能描述：查询全域范围内某个时间片段的轨迹数据，用于全域回放模式。时间窗口由 `timePoint` 和 `hours` 推算。全域回放始终查询固定抽稀位置帧；即使传入 `raw` 也不扫描原始表。

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `timePoint` | string | 否 | 回放结束时间。不传时使用数据库最新时间。 |
| `hours` | integer | 否 | 向前回看的小时数，默认取配置 `query.globalSegmentHours`。 |
| `zoom` | integer | 否 | 当前地图缩放级别。 |
| `samplingMode` | string | 否 | 抽稀模式：`auto`、`manual`、`raw`。 |
| `bucketSeconds` | integer | 否 | 手动抽稀粒度，服务端归一到 `60`、`300`、`1800`。 |

请求示例：

```http
GET /api/tracks/global-segment?timePoint=2026-04-30T08:00:00Z&hours=1&zoom=8&samplingMode=auto
```

响应字段同“查询单船轨迹”，但 `items` 会包含多艘船的数据。前端全域回放使用这些点驱动播放时间轴，但地图上只显示每艘船在当前播放时刻的最新位置，不绘制轨迹线。

响应示例：

```json
{
  "items": [
    {
      "shipId": "SHIP001",
      "shipName": "测试船A",
      "lng": 122.41,
      "lat": 39.21,
      "speed": 10.5,
      "heading": 85.0,
      "time": "2026-04-30 15:00:00"
    }
  ]
}
```

## 4. WebSocket 接口

```http
GET /ws/realtime
```

功能描述：建立实时船位 WebSocket 连接，用于前端接收缓存状态和实时增量推送。

连接建立后，服务端会发送缓存就绪类消息。消息字段由 `RealtimeWebSocketHandler` 和 `RealtimeService.readyPayload()` 生成。

典型消息示例：

```json
{
  "type": "ready",
  "source": "memory",
  "cacheReady": true,
  "window": {
    "start": "2026-04-30 15:50:00",
    "end": "2026-04-30 16:00:00"
  },
  "since": "2026-04-30 15:59:58"
}
```

## 5. 性能与使用建议

- 船舶加载速度是本项目第一优先级。前端应尽量复用已有统计接口，避免为展示指标重复发起大范围轨迹查询。
- 大时间跨度轨迹查询优先使用 `samplingMode=auto`，由服务端按 zoom 选择固定 `bucket_size`。
- 只有在需要完整原始点导出或精确核查时使用 `samplingMode=raw`。
- 多船轨迹查询前，建议先调用 `/api/tracks/candidates` 获取候选船，再按需选择 `shipIds` 调用 `/api/tracks/multi`。
- 热力图接口 `/api/analysis/density` 必须传入当前视野 bbox，避免查询全域造成不必要压力；态势分析播放由前端按时间片逐帧请求，后端只返回当前时间片的静态热力点。
- 候选船舶接口的 `pageSize` 会受后端配置上限限制，默认配置最大为 1000。

## 6. 抽稀表

抽稀表不是 HTTP API，但会影响轨迹接口的数据来源：

1. 原始表使用 `tb_ais_track_raw`。
2. 固定抽稀表使用 `tb_ais_track_thin`，依赖 ClickHouse 物化视图维护 `bucket_size=60/300/1800`。
3. 候选船框选使用 `tb_ship_bucket_index` 的时空索引聚合状态。
4. 项目内旧 `TrackSimplificationService` 默认关闭，页面主链路不依赖旧 SED-RDP 抽稀表。

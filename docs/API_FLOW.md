# 后端接口流程说明

本文档说明后端接口的内部处理链路，面向后端研发、联调人员和后续维护者。接口入参、返回字段和示例详见 `docs/API.md`；本文只描述后台流程、关键类方法和性能注意点。

## 1. 总体链路

```mermaid
flowchart TD
  A["前端或调用方"] --> B["ApiController / WebSocketHandler"]
  B --> C{"接口类型"}
  C -->|配置/校验/组织响应| D["ApiController"]
  C -->|实时窗口和缓存| E["RealtimeService"]
  C -->|SQL 生成和查询逻辑| F["TrackRepository"]
  E --> F
  F --> G["ClickHouseHttpClient"]
  G --> H["ClickHouse HTTP 接口"]
  H --> G
  G --> I["解析 JSONEachRow"]
  I --> F
  F --> B
  E --> B
  D --> B
  B --> J["JSON 响应或 WebSocket 消息"]
  B -.异常.-> K["ApiExceptionHandler"]
  K --> L["统一 error JSON"]
```

后台核心分工：

| 模块 | 职责 |
| --- | --- |
| `ApiController` | 接收 HTTP 请求，解析 query/body，校验时间窗口、bbox、zoom，调用服务或仓库并组织响应。 |
| `RealtimeService` | 推算实时窗口和全域窗口，维护实时最新船位缓存，读取数据库统计缓存。 |
| `TrackRepository` | 根据业务场景生成 ClickHouse SQL，处理抽稀、统计聚合、候选船查询。 |
| `TrackSimplificationService` | 旧 SED-RDP 抽稀任务，当前默认关闭；页面主链路使用 ClickHouse 固定抽稀表。 |
| `ClickHouseHttpClient` | 将 SQL 和参数通过 ClickHouse HTTP 接口发送，追加 `FORMAT JSONEachRow`，解析返回行；批量写入时使用 `INSERT ... FORMAT JSONEachRow`。 |
| `ApiExceptionHandler` | 捕获异常并返回统一 `{ "error": "..." }` JSON。 |

通用数据库查询流程：

```mermaid
flowchart TD
  A["TrackRepository 生成 SQL"] --> B["准备 ClickHouse 参数 Map"]
  B --> C["ClickHouseHttpClient.query/queryOne"]
  C --> D["POST SQL + FORMAT JSONEachRow"]
  D --> E["ClickHouse 执行查询"]
  E --> F{"HTTP 状态码"}
  F -->|2xx| G["逐行解析 JSONEachRow"]
  F -->|非 2xx| H["抛出 ClickHouseException"]
  G --> I["返回 List<Map> 或单行 Map"]
  H --> J["ApiExceptionHandler 转换错误响应"]
```

固定抽稀数据来源：

```mermaid
flowchart TD
  A["tb_ais_track_raw 原始轨迹写入"] --> B["ClickHouse 物化视图"]
  B --> C["tb_ais_track_thin 固定 bucket_size 轨迹"]
  B --> D["tb_ship_bucket_index 时空索引"]
```

抽稀查询通用流程：

```mermaid
flowchart TD
  A["single/multi/global 轨迹查询"] --> B["normalize samplingMode"]
  B --> C{"samplingMode == raw?"}
  C -->|是| D["查询 tb_ais_track_raw 原始轨迹"]
  C -->|否| E["选择固定 bucket_size"]
  E --> F["查询 tb_ais_track_thin"]
  F --> G["返回固定抽稀轨迹点"]
```

## 2. `GET /api/config/map`

后台职责：返回前端初始化需要的地图配置、查询上限和高德相关环境变量，不访问 ClickHouse。

```mermaid
flowchart TD
  A["GET /api/config/map"] --> B["ApiController.mapConfig"]
  B --> C["读取 ShipTrackConfig.map 和 query"]
  C --> D["ShipConfigService.envOrDefault 读取高德环境变量"]
  D --> E["组装 LinkedHashMap"]
  E --> F["返回 JSON"]
```

关键处理步骤：

1. `trace("/api/config/map", ...)` 开始记录请求耗时。
2. 从 `config.map` 读取 `coordinateSystem`、`defaultCenter`、`defaultZoom`。
3. 从 `config.query` 读取 `maxMultiShips`、`globalSegmentHours`。
4. 通过 `ShipConfigService.envOrDefault` 读取 `VITE_AMAP_KEY` 和 `VITE_AMAP_SECURITY_JS_CODE`。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `mapConfig()` |
| `ShipConfigService` | `envOrDefault(...)` |

性能注意点或特殊逻辑：

- 该接口只读内存配置和环境变量，不产生数据库查询。
- 适合前端启动时调用一次，后续无需频繁请求。

## 3. `GET /api/realtime/latest`

后台职责：根据请求参数得到实时窗口，优先返回内存缓存中的最新船位；缓存未命中时刷新缓存，必要时回退到 ClickHouse 查询。

```mermaid
flowchart TD
  A["GET /api/realtime/latest"] --> B["ApiController.latest"]
  B --> C["realtimeWindow(start,end,timePoint,minutes)"]
  C --> D{"传入 timePoint 或 minutes?"}
  D -->|是| E["RealtimeService.realtimeWindowFromParams"]
  D -->|否| F{"传入 start 或 end?"}
  F -->|是| G["RealtimeService.validateTimeWindow"]
  F -->|否| E
  E --> H["RealtimeService.latestResponse"]
  G --> H
  H --> I{"缓存 ready 且窗口一致?"}
  I -->|是| J["compactLatest(source=memory)"]
  I -->|否| K["warmLatestCache"]
  K --> L["TrackRepository.latest"]
  L --> M["ClickHouseHttpClient.query"]
  M --> N["刷新内存缓存"]
  N --> O{"刷新后缓存可用?"}
  O -->|是| J
  O -->|否| P["fallback: TrackRepository.latest"]
  P --> Q["compactLatest(source=clickhouse)"]
  J --> R["返回 compact JSON"]
  Q --> R
```

关键处理步骤：

1. `ApiController.realtimeWindow` 决定时间窗口来源。
2. `RealtimeService.realtimeWindowFromParams` 在未传 `timePoint` 时会调用 `TrackRepository.watermark()` 获取数据库最新时间。
3. `RealtimeService.latestResponse` 先检查内存缓存是否已就绪且窗口一致。
4. 缓存未命中时调用 `warmLatestCache`，内部使用 `TrackRepository.latest` 查询每艘船的最新点。
5. 返回紧凑结构：`fields` 描述字段顺序，`items` 是二维数组。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `latest(...)`, `realtimeWindow(...)` |
| `RealtimeService` | `realtimeWindowFromParams(...)`, `validateTimeWindow(...)`, `latestResponse(...)`, `warmLatestCache(...)` |
| `TrackRepository` | `watermark()`, `latest(...)` |
| `ClickHouseHttpClient` | `query(...)`, `queryOne(...)` |

性能注意点或特殊逻辑：

- 这是实时态势核心接口，优先走内存缓存，减少 ClickHouse 压力。
- 缓存刷新会按 `query.realtimeCacheMaxShips` 限制加载规模。
- 未传时间窗口时需要先查 `watermark()`，这会产生一次轻量 ClickHouse 查询。

## 4. `GET /api/stats/realtime-summary`

后台职责：返回实时面板统计，包括全库统计、时间窗口统计、热力网格数量和视野内热力网格数量。

```mermaid
flowchart TD
  A["GET /api/stats/realtime-summary"] --> B["ApiController.realtimeSummary"]
  B --> C["realtimeWindow(...) 得到时间窗口"]
  C --> D["RealtimeService.databaseStats 读取缓存统计"]
  D --> E{"时间窗口有效?"}
  E -->|否| F["windowTrackPoints/windowShips = 0"]
  E -->|是| G["TrackRepository.windowStats"]
  G --> H["ClickHouse 查询窗口 count/uniq"]
  F --> I["validateZoom"]
  H --> I
  I --> J{"是否传入完整 bbox?"}
  J -->|否| K["viewportHeatCells = 0"]
  J -->|是| L["TrackRepository.densityCellCount(bbox)"]
  I --> M["TrackRepository.densityCellCount(null)"]
  M --> N["组装统计 JSON"]
  K --> N
  L --> N
```

关键处理步骤：

1. 复用 `/api/realtime/latest` 的实时窗口解析规则。
2. `RealtimeService.databaseStats()` 返回启动预热后的全库统计缓存。
3. `TrackRepository.windowStats` 查询时间窗口内轨迹点和船舶数。
4. `TrackRepository.densityCellCount` 根据 `zoom` 推算网格大小并统计网格数量。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `realtimeSummary(...)`, `bboxOrNull(...)` |
| `RealtimeService` | `databaseStats()`, `validateZoom(...)` |
| `TrackRepository` | `windowStats(...)`, `densityCellCount(...)` |

性能注意点或特殊逻辑：

- 最多可能触发 3 次 ClickHouse 查询：窗口统计、全窗口热力格数、视野热力格数。
- 未传 bbox 时不会查询视野热力格数，直接返回 `viewportHeatCells=0`。
- 全库统计来自内存缓存，不在该接口内重新扫描全库。

## 5. `GET /api/stats/database`

后台职责：返回全库轨迹点数量和船舶数量。当前 HTTP 接口读取 `RealtimeService` 中的统计缓存。

```mermaid
flowchart TD
  A["GET /api/stats/database"] --> B["ApiController.databaseStats"]
  B --> C["RealtimeService.databaseStats"]
  C --> D["读取内存中的 databaseStats"]
  D --> E["返回 trackPoints/ships"]
  F["应用启动"] -.预热.-> G["RealtimeService.warmDatabaseStats"]
  G -.-> H["TrackRepository.databaseStats"]
  H -.-> I["ClickHouse 查询全库统计"]
  I -.-> D
```

关键处理步骤：

1. 应用启动时 `RealtimeService.warmOnStartup` 会调用 `warmDatabaseStats`。
2. `warmDatabaseStats` 通过 `TrackRepository.databaseStats` 查询全库 `count()` 和 `uniqCombined64(shipId)`。
3. HTTP 请求到达时直接返回内存中的 `databaseStats`。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `databaseStats()` |
| `RealtimeService` | `databaseStats()`, `warmOnStartup()`, `warmDatabaseStats()` |
| `TrackRepository` | `databaseStats()` |

性能注意点或特殊逻辑：

- 接口本身不直接查 ClickHouse，速度取决于内存读取。
- 如果启动预热失败，缓存默认值为 `trackPoints=0`、`ships=0`，日志会记录失败原因。

## 6. `GET /api/stats/global-summary`

后台职责：根据全域回放窗口返回窗口内轨迹点数量，同时返回全库统计缓存。

```mermaid
flowchart TD
  A["GET /api/stats/global-summary"] --> B["ApiController.globalSummary"]
  B --> C["RealtimeService.globalWindowFromParams"]
  C --> D{"timePoint 是否为空?"}
  D -->|是| E["TrackRepository.watermark 获取数据库最新时间"]
  D -->|否| F["解析 timePoint"]
  E --> G["按 hours 推算 start/end"]
  F --> G
  G --> H["RealtimeService.databaseStats"]
  H --> I["TrackRepository.windowStats"]
  I --> J["ClickHouse 查询窗口统计"]
  J --> K["返回 databaseTrackPoints/databaseShips/windowTrackPoints"]
```

关键处理步骤：

1. `globalWindowFromParams` 使用 `timePoint` 作为结束时间。
2. 未传 `timePoint` 时调用 `TrackRepository.watermark()` 获取数据库最大事件时间。
3. `hours` 为空时使用配置 `query.globalSegmentHours`。
4. 只返回窗口轨迹点数量，不返回窗口船舶数。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `globalSummary(...)` |
| `RealtimeService` | `globalWindowFromParams(...)`, `databaseStats()` |
| `TrackRepository` | `watermark()`, `windowStats(...)` |

性能注意点或特殊逻辑：

- 未传 `timePoint` 会多一次 `watermark()` 查询。
- `windowStats` 会统计窗口内轨迹点和船舶数，但响应只使用 `trackPoints`。

## 7. `GET /api/stats/multi-summary`

后台职责：返回多船模式下时间窗口全范围统计，以及可选 bbox 范围内统计。

```mermaid
flowchart TD
  A["GET /api/stats/multi-summary"] --> B["ApiController.multiSummary"]
  B --> C["RealtimeService.validateTimeWindow"]
  C --> D["bboxOrNull 解析可选 bbox"]
  D --> E["TrackRepository.multiStats"]
  E --> F["TrackRepository.windowStats(start,end)"]
  F --> G["ClickHouse 查询全窗口统计"]
  E --> H{"bbox 是否存在?"}
  H -->|否| I["bboxTrackPoints/bboxShips = 0"]
  H -->|是| J["TrackRepository.windowStats(start,end,bbox)"]
  J --> K["ClickHouse 查询框选范围统计"]
  G --> L["组装 multi summary JSON"]
  I --> L
  K --> L
```

关键处理步骤：

1. 强制校验 `start` 和 `end`，时间窗口无效时抛出异常。
2. bbox 参数不完整时视为未传 bbox。
3. `multiStats` 总是查询全窗口统计；只有存在 bbox 时才额外查询框选范围统计。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `multiSummary(...)`, `bboxOrNull(...)` |
| `RealtimeService` | `validateTimeWindow(...)` |
| `TrackRepository` | `multiStats(...)`, `windowStats(...)` |

性能注意点或特殊逻辑：

- 传入 bbox 时会有 2 次窗口统计查询。
- 未传 bbox 时框选指标直接为 0，避免无意义查询。

## 8. `GET /api/stats/single-track-points`

后台职责：统计单船在时间窗口内的原始轨迹点数量。

```mermaid
flowchart TD
  A["GET /api/stats/single-track-points"] --> B["ApiController.singleTrackPointStats"]
  B --> C{"shipId 是否为空?"}
  C -->|是| D["抛出 IllegalArgumentException"]
  C -->|否| E["RealtimeService.validateTimeWindow"]
  E --> F["TrackRepository.singleTrackPointCount"]
  F --> G["生成 count SQL，PREWHERE shipId"]
  G --> H["ClickHouseHttpClient.query"]
  H --> I["返回 trackPoints"]
  D --> J["ApiExceptionHandler 返回 error JSON"]
```

关键处理步骤：

1. `shipId` 为空直接抛出参数异常。
2. 时间窗口通过 `validateTimeWindow` 校验。
3. `singleTrackPointCount` 生成 `count()` SQL，并使用 `PREWHERE shipId` 加速单船过滤。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `singleTrackPointStats(...)` |
| `RealtimeService` | `validateTimeWindow(...)` |
| `TrackRepository` | `singleTrackPointCount(...)` |

性能注意点或特殊逻辑：

- 该接口只返回数量，不返回轨迹点。
- SQL 使用单船过滤，适合配合单船抽稀轨迹展示原始点总量。

## 9. `POST /api/stats/multi-track-points`

后台职责：统计多艘船在时间窗口内的原始轨迹点总数。

```mermaid
flowchart TD
  A["POST /api/stats/multi-track-points"] --> B["ApiController.multiTrackPointStats"]
  B --> C["读取 body.shipIds/start/end"]
  C --> D["shipIds 按 maxMultiShips 截断"]
  D --> E{"shipIds 是否为空?"}
  E -->|是| F["抛出 IllegalArgumentException"]
  E -->|否| G["RealtimeService.validateTimeWindow"]
  G --> H["TrackRepository.multiTrackPointCount"]
  H --> I["生成 count SQL，shipId IN 参数数组"]
  I --> J["ClickHouseHttpClient.query"]
  J --> K["返回 trackPoints"]
  F --> L["ApiExceptionHandler 返回 error JSON"]
```

关键处理步骤：

1. 请求体中的 `shipIds` 最多保留 `config.query.maxMultiShips` 个。
2. `shipIds` 为空时直接抛出参数异常。
3. `multiTrackPointCount` 使用 `shipId IN {shipIds: Array(String)}` 查询总点数。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `multiTrackPointStats(...)` |
| `RealtimeService` | `validateTimeWindow(...)` |
| `TrackRepository` | `multiTrackPointCount(...)` |

性能注意点或特殊逻辑：

- 只做计数，不返回轨迹明细。
- 多船数量受 `maxMultiShips` 限制，避免超大 IN 列表拖慢查询。

## 10. `GET /api/analysis/density`

后台职责：按时间窗口、bbox 和地图缩放级别聚合单帧密度网格；态势分析由前端按时间片逐帧请求。

```mermaid
flowchart TD
  A["GET /api/analysis/density"] --> B["ApiController.density"]
  B --> C["realtimeWindow(...) 得到时间窗口"]
  C --> D["bbox(west,south,east,north) 校验范围"]
  D --> E["RealtimeService.validateZoom"]
  E --> F["TrackRepository.density 静态聚合"]
  F --> G["densityGridSizeDegrees 按 zoom 计算网格"]
  G --> H["生成 GROUP BY lng/lat 网格 SQL"]
  H --> I["ClickHouseHttpClient.query"]
  I --> J["返回 items: lng/lat/count/ships"]
```

关键处理步骤：

1. 时间窗口规则与实时接口一致。
2. bbox 是必填参数，缺失或范围非法会抛出异常。
3. `densityGridSizeDegrees` 根据 `zoom` 返回网格粒度。
4. SQL 只按网格中心点聚合 `count()` 和 `uniqCombined64(shipId)`。
5. 前端如果要播放多帧热力，按自己的时间步进逐帧请求该接口。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `density(...)`, `realtimeWindow(...)`, `bbox(...)` |
| `RealtimeService` | `validateZoom(...)` |
| `TrackRepository` | `density(...)`, `densityGridSizeDegrees(...)` |

性能注意点或特殊逻辑：

- bbox 必填，避免全域热力查询影响船只加载速度。
- 态势分析播放由前端串行请求控制，后端只负责返回当前时间片的静态热力点。
- 返回数量受 `query.maxDensityCells` 限制。

## 11. `GET /api/tracks/single`

后台职责：查询单船轨迹，支持原始点和抽稀点返回。`raw` 查询原始表；`auto/manual` 查询固定抽稀表。

```mermaid
flowchart TD
  A["GET /api/tracks/single"] --> B["ApiController.single"]
  B --> C{"shipId 是否为空?"}
  C -->|是| D["抛出 IllegalArgumentException"]
  C -->|否| E["RealtimeService.validateTimeWindow"]
  E --> F["RealtimeService.validateZoom"]
  F --> G["normalizeSamplingMode + parseBucketSeconds"]
  G --> H["TrackRepository.singleTrackRows"]
  H --> I["resolveSamplingPlan"]
  I --> J{"samplingMode == raw?"}
  J -->|是| K["生成原始点 SQL，PREWHERE shipId"]
  J -->|否| L["选择固定 bucket_size"]
  L --> M["查询 tb_ais_track_thin"]
  K --> R["ClickHouseHttpClient.query"]
  M --> R
  R --> O["返回轨迹点"]
  D --> S["ApiExceptionHandler 返回 error JSON"]
```

关键处理步骤：

1. `shipId` 为空直接抛出异常。
2. 时间窗口必须合法，`zoom` 必须在 3 到 18。
3. `samplingMode` 非法时归一化为 `auto`。
4. `raw` 模式返回原始轨迹点；`auto/manual` 模式查询 `bucket_size=60/300/1800` 的固定抽稀点。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `single(...)`, `normalizeSamplingMode(...)`, `parseBucketSeconds(...)` |
| `RealtimeService` | `validateTimeWindow(...)`, `validateZoom(...)` |
| `TrackRepository` | `singleTrackRows(...)`, `bucketSizeForZoom(...)`, `resolveSamplingPlan(...)` |

性能注意点或特殊逻辑：

- 默认使用 `auto` 抽稀，目标是控制单船返回点数。
- `raw` 模式可能返回大量点，应只用于需要原始明细的场景。
- 单船 SQL 使用 `PREWHERE shipId`。
- 默认抽稀路径避免从原始表再做 bucket 聚合，控制船只加载查询成本。

## 12. `GET /api/tracks/candidates`

后台职责：查询多船框选范围内的候选船舶列表，用于先筛船再查询多船轨迹。

```mermaid
flowchart TD
  A["GET /api/tracks/candidates"] --> B["ApiController.candidates"]
  B --> C["RealtimeService.validateTimeWindow"]
  C --> D["解析 page/pageSize"]
  D --> F["bbox(west,south,east,north) 校验"]
  F --> G["TrackRepository.candidates"]
  G --> H["pageSize 按 maxCandidateBatchSize 限制"]
  H --> J["bucketIndex 聚合空间候选和点数"]
  J --> N["ClickHouseHttpClient.query"]
  N --> O["返回候选船 items"]
```

关键处理步骤：

1. 强制校验时间窗口和 bbox。
2. `page` 默认 1，`pageSize` 默认 100，并受配置上限限制。
3. 空间候选和点数查询只使用 `config.tables.bucketIndex` 配置的桶索引表。
4. 候选阶段不补船型，避免为了候选列表额外读取轨迹表；已选船轨迹查询再返回船型字段。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `candidates(...)`, `bbox(...)` |
| `RealtimeService` | `validateTimeWindow(...)` |
| `TrackRepository` | `candidates(...)` |

性能注意点或特殊逻辑：

- 该接口使用桶索引表而不是原始轨迹表，目的是加快候选船加载。
- 返回按 AIS 优先、点数倒序、船舶编号升序排序。
- `pageSize` 受 `query.maxCandidateBatchSize` 限制，默认最大 1000。

## 13. `POST /api/tracks/multi`

后台职责：查询多艘船轨迹，支持原始点和抽稀点返回。`raw` 查询原始表；`auto/manual` 查询固定抽稀表。

```mermaid
flowchart TD
  A["POST /api/tracks/multi"] --> B["ApiController.multi"]
  B --> C["读取 body.shipIds/start/end/zoom/sampling"]
  C --> D["shipIds 按 maxMultiShips 截断"]
  D --> E{"shipIds 是否为空?"}
  E -->|是| F["抛出 IllegalArgumentException"]
  E -->|否| G["RealtimeService.validateTimeWindow"]
  G --> H["zoom 为空则使用 defaultZoom"]
  H --> I["normalizeSamplingMode"]
  I --> J["TrackRepository.trackRows"]
  J --> K["resolveSamplingPlan"]
  K --> L{"samplingMode == raw?"}
  L -->|是| M["生成原始多船 SQL"]
  L -->|否| N["选择固定 bucket_size"]
  N --> O["查询 tb_ais_track_thin"]
  M --> T["ClickHouseHttpClient.query"]
  O --> T
  T --> Q["返回轨迹点"]
  F --> U["ApiExceptionHandler 返回 error JSON"]
```

关键处理步骤：

1. `shipIds` 最多保留 `config.query.maxMultiShips` 个。
2. `zoom` 为空时使用 `config.map.defaultZoom`；当前该接口未调用 `validateZoom`。
3. `TrackRepository.trackRows` 根据抽稀计划生成原始 SQL 或固定抽稀表 SQL。
4. 请求体类中存在 `bbox` 字段，但当前控制器传给仓库的是 `null`，因此该接口实际不按 bbox 过滤。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `multi(...)`, `normalizeSamplingMode(...)` |
| `RealtimeService` | `validateTimeWindow(...)` |
| `TrackRepository` | `trackRows(...)`, `resolveSamplingPlan(...)`, `bucketSizeForZoom(...)` |

性能注意点或特殊逻辑：

- 多船查询是重接口，默认应使用 `auto` 抽稀。
- 船舶数量受 `maxMultiShips` 限制，避免超大查询影响加载速度。
- 建议先用 `/api/tracks/candidates` 筛选船舶，再调用该接口。
- 前端使用返回点作为播放事件源，但地图只绘制当前播放时刻向前 30 分钟的轨迹窗口，不再预画整段轨迹。

## 14. `GET /api/tracks/global-segment`

后台职责：查询全域回放时间片段内的抽稀位置帧。全域回放固定留在抽稀表链路，传入 `raw` 也不扫描原始表。

```mermaid
flowchart TD
  A["GET /api/tracks/global-segment"] --> B["ApiController.globalSegment"]
  B --> C["RealtimeService.globalWindowFromParams"]
  C --> D{"timePoint 是否为空?"}
  D -->|是| E["TrackRepository.watermark 获取数据库最新时间"]
  D -->|否| F["解析 timePoint"]
  E --> G["按 hours 推算 start/end"]
  F --> G
  G --> H["RealtimeService.validateZoom"]
  H --> I["normalizeSamplingMode + parseBucketSeconds"]
  I --> J["TrackRepository.globalSegment"]
  J --> K["resolveSamplingPlan"]
  K --> L{"samplingMode == raw?"}
  L -->|是| M["生成全域原始点 SQL"]
  L -->|否| N["选择固定 bucket_size"]
  N --> O["查询 tb_ais_track_thin"]
  M --> S["ClickHouseHttpClient.query"]
  O --> S
  S --> Q["返回全域轨迹点"]
```

关键处理步骤：

1. `timePoint` 为空时通过 `watermark()` 取数据库最大时间。
2. `hours` 为空时使用配置 `query.globalSegmentHours`。
3. `globalSegment` 在 `auto/manual` 下按 zoom 选择固定抽稀粒度。
4. 返回多艘船的轨迹点，按时间和船舶编号排序。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiController` | `globalSegment(...)`, `normalizeSamplingMode(...)`, `parseBucketSeconds(...)` |
| `RealtimeService` | `globalWindowFromParams(...)`, `validateZoom(...)` |
| `TrackRepository` | `watermark()`, `globalSegment(...)`, `resolveSamplingPlan(...)` |

性能注意点或特殊逻辑：

- 全域查询覆盖范围最大，应优先使用 `auto` 抽稀。
- `raw` 模式可能返回巨大数据量，容易影响船只加载和前端渲染。
- 前端全域回放只显示每艘船当前播放时刻的位置 marker，不绘制轨迹线。

## 15. `GET /ws/realtime`

后台职责：建立实时 WebSocket 连接，并在连接建立后发送一次当前缓存状态。

```mermaid
flowchart TD
  A["GET /ws/realtime WebSocket Upgrade"] --> B["WebSocketConfig.registerWebSocketHandlers"]
  B --> C["RealtimeWebSocketHandler"]
  C --> D["afterConnectionEstablished"]
  D --> E["RealtimeService.readyPayload"]
  E --> F["ObjectMapper.writeValueAsString"]
  F --> G["session.sendMessage TextMessage"]
```

关键处理步骤：

1. `WebSocketConfig` 将 `/ws/realtime` 注册到 `RealtimeWebSocketHandler`。
2. 客户端连接建立后触发 `afterConnectionEstablished`。
3. 处理器调用 `RealtimeService.readyPayload()` 获取当前缓存状态。
4. 通过 `ObjectMapper` 序列化为 JSON，并发送给客户端。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `WebSocketConfig` | `registerWebSocketHandlers(...)` |
| `RealtimeWebSocketHandler` | `afterConnectionEstablished(...)` |
| `RealtimeService` | `readyPayload()` |

性能注意点或特殊逻辑：

- 当前实现只在连接建立后发送一次 ready 消息。
- 该流程不直接查询 ClickHouse。

## 16. 异常处理流程

后台职责：将控制器、服务层、仓库层和 ClickHouse 客户端抛出的异常统一转换为 JSON 错误响应。

```mermaid
flowchart TD
  A["任意 HTTP 接口执行异常"] --> B["抛出 RuntimeException 或 ClickHouseException"]
  B --> C["ApiExceptionHandler.handle"]
  C --> D{"是否 ClickHouse 连接失败?"}
  D -->|是| E["HTTP 503"]
  D -->|否| F["HTTP 500"]
  E --> G["返回 { error: message }"]
  F --> G
```

关键处理步骤：

1. 参数校验失败通常抛出 `IllegalArgumentException`。
2. ClickHouse 查询失败抛出 `ClickHouseException`。
3. 如果错误信息包含 `ClickHouse connection failed`，返回 HTTP 503。
4. 其他异常返回 HTTP 500。

主要参与类/方法：

| 类 | 方法 |
| --- | --- |
| `ApiExceptionHandler` | `handle(Exception error)` |
| `ClickHouseHttpClient` | `post(...)` |

性能注意点或特殊逻辑：

- 异常处理本身不做重试。
- ClickHouse 连接失败会在日志中记录 endpoint、timeout 和 max execution time。

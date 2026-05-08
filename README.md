# 船舶态势与轨迹回放

基于 OpenLayers、Java Spring Boot 和 ClickHouse 的船舶态势展示、热力分析和轨迹回放服务。前端静态文件位于 `public/`，后端提供 HTTP API、静态资源服务和 `/ws/realtime` 实时 WebSocket 推送。

## 环境要求

- Java 17 或更高版本。
- Maven 3.6.3 或更高版本。
- ClickHouse HTTP 服务可访问。

当前项目默认可整体迁移。配置、前端资源、SQL 辅助文件都位于项目目录内；日志、构建产物和本机私有配置不纳入版本库。

## 配置

复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`：

```text
PORT=3001
CLICKHOUSE_JDBC_URL=jdbc:clickhouse://127.0.0.1:8123/default
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=
```

ClickHouse 表名、字段名、查询上限、地图默认中心点等配置位于 `config/ship-track.config.json`。

前端使用项目内 `public/vendor/openlayers/` 中的 OpenLayers 文件，不需要高德 Web JS API Key。默认底图使用高德 XYZ 瓦片 `webrd01-04.is.autonavi.com/appmaptile`。

## 抽稀表

原始轨迹仍写入 `tb_ais_event_simple_info`。轨迹查询默认优先使用多级抽稀表：

- `tb_ship_track_simplified`：存储 L0/L1/L2/L3 抽稀轨迹。
- `tb_ship_simplify_offset`：记录每条船处理进度。

建表 SQL 位于项目内：

```powershell
# 在 ClickHouse 中执行 db/simplified-tracks.sql
```

已有历史原始轨迹需要显式回填：

```powershell
.\scripts\backfill-simplified-tracks.ps1
```

服务启动后会每 5 分钟按船增量读取原始表，执行 SED-RDP 抽稀并写入抽稀表。当前不处理 AIS 乱序、补串和迟到数据；offset 只按每船最后处理到的 `event_time` 单调前进。

## 运行

开发运行：

```powershell
mvn spring-boot:run
```

打包：

```powershell
mvn package
```

运行打包产物：

```powershell
java -jar target/ship-situation-replay-0.1.0.jar
```

浏览器访问：

```text
http://127.0.0.1:3001
```

## API

前端调用以下接口：

- `GET /api/config/map`
- `GET /api/realtime/latest`
- `GET /api/analysis/density`
- `GET /api/tracks/single`
- `GET /api/tracks/candidates`
- `POST /api/tracks/multi`
- `GET /api/tracks/global-segment`
- `GET /ws/realtime` WebSocket 升级连接

实时最新船位接口继续返回 compact 结构：`fields` 描述字段顺序，`items` 为二维数组，顺序为 `shipId, shipName, lng, lat, speed, heading, time, isAis`。

轨迹接口说明：

- `/api/tracks/single`、`/api/tracks/multi`、`/api/tracks/global-segment` 在 `auto/manual` 模式下先按 zoom 选择抽稀层级并查询 `tb_ship_track_simplified`。
- 抽稀表无数据或抽稀表查询失败时，会 fallback 到原有 bucket 分组查询。
- `samplingMode=raw` 仍查询原始表。
- 多船回放只绘制当前播放时刻向前 30 分钟轨迹；全域回放只显示船位置，不绘制轨迹线；实时位置点击船会自动绘制该船 1 小时轨迹。

## 可选索引

`db/optional-indexes.sql` 仅提供 ClickHouse 跳数索引 SQL，不会自动执行。建议先确认应用功能正常，再使用 `db/explain-queries.sql` 对比查询计划，并在低峰维护窗口人工执行索引和物化操作。

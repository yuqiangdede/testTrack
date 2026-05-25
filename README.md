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
CLICKHOUSE_JDBC_URL=jdbc:clickhouse://10.100.1.23:8461/track
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=123456
```

ClickHouse 表名、字段名、查询上限、地图默认中心点等配置位于 `config/ship-track.config.json`。

前端使用项目内 `public/vendor/openlayers/` 中的 OpenLayers 文件，不需要高德 Web JS API Key。默认底图使用内网离线高德 XYZ 瓦片 `10.100.1.3/GeoData_mbs/map/GaodeMap/img/{z}/{x}/{y}.png`。

## 抽稀表

轨迹查询依赖 ClickHouse 已维护的原始表、固定抽稀表和空间索引表：

- `tb_ais_track_raw`：原始轨迹。
- `tb_ais_track_thin`：按 `60`、`300`、`1800` 秒固定粒度抽稀后的轨迹。
- `tb_ship_bucket_index`：候选船框选使用的船舶时空索引。

`tb_ais_track_thin` 和 `tb_ship_bucket_index` 由 ClickHouse 物化视图维护。项目内旧 SED-RDP 抽稀任务默认关闭，页面轨迹加载不再依赖 `tb_ship_track_simplified` 或 offset 回填任务。

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

实时最新船位接口继续返回 compact 结构：`fields` 描述字段顺序，`items` 为二维数组，顺序为 `shipId, shipName, lng, lat, speed, heading, time, isAis, shipType`。

未指定实时窗口时，服务固定使用 `2026-05-15 23:30:00` 到 `2026-05-16 00:00:00`，并在启动时优先将该窗口最新船位加载到内存。默认缓存预热失败时接口返回空缓存结果，不把首屏请求回落到慢查询。

轨迹接口说明：

- `/api/tracks/single`、`/api/tracks/multi` 在 `auto/manual` 模式下查询 `tb_ais_track_thin` 的固定 `bucket_size`。
- `/api/tracks/multi` 与 `/api/tracks/global-segment` 返回 compact 轨迹结构；全域接口默认固定使用 `bucket_size=1800` 的船位帧，不返回整段全域轨迹线。
- 人工抽稀粒度固定为 `60`、`300`、`1800` 秒，自动模式按 zoom 选择其中一个粒度。
- 只有单船回放的 `samplingMode=raw` 查询 `tb_ais_track_raw`；多船和全域回放始终留在抽稀表链路。
- 多船回放先绘制整段半透明轨迹，再将已播放部分叠成不透明轨迹；全域回放只显示船位置，不绘制轨迹线；实时位置点击船会进入多选，详情仍可发起单船回放。
- 热力图从 `tb_ais_track_thin` 的 `bucket_size=300` 数据聚合，统计类指标在前端延后加载，避免争抢实时船位首屏链路。

## 可选索引

`db/optional-indexes.sql` 仅提供 ClickHouse 跳数索引 SQL，不会自动执行。建议先确认应用功能正常，再使用 `db/explain-queries.sql` 对比查询计划，并在低峰维护窗口人工执行索引和物化操作。

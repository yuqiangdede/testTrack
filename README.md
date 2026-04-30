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

## 回填命令

默认 dry-run，不写入数据：

```powershell
java -jar target/ship-situation-replay-0.1.0.jar backfill
```

执行写入：

```powershell
java -jar target/ship-situation-replay-0.1.0.jar backfill --execute
```

可选参数：

```text
--resume
--target-start=2026-01-07
--target-days=100
--batch-days=5
--reserve-gib=20
--safety-factor=1.2
--host-drive=D
```

回填会先检查源窗口、目标区间、ClickHouse 表大小和磁盘余量。目标区间已有数据时默认中止；确认需要补齐缺失日期时再使用 `--resume`。

## 可选索引

`db/optional-indexes.sql` 仅提供 ClickHouse 跳数索引 SQL，不会自动执行。建议先确认应用功能正常，再使用 `db/explain-queries.sql` 对比查询计划，并在低峰维护窗口人工执行索引和物化操作。
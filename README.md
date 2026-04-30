# 船舶态势与轨迹回放

基于高德地图 AMap JS API 与 ClickHouse 的船舶态势展示、热力分析和轨迹回放模块。

## 运行

当前项目为零 npm 依赖实现，不需要执行 `npm install`。

1. 复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

2. 编辑 `.env`，填写高德 Web JS API Key、ClickHouse 连接和账号信息：

```text
VITE_AMAP_KEY=你的高德 Web JS API Key
VITE_AMAP_SECURITY_JS_CODE=如启用安全密钥则填写
CLICKHOUSE_JDBC_URL=jdbc:clickhouse://127.0.0.1:8123/default
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=你的 ClickHouse 密码
```

3. 启动服务：

```powershell
npm start
```

4. 浏览器访问：

```text
http://127.0.0.1:3001
```

## 配置

ClickHouse 连接、表名、字段名、坐标字段、时间字段、船舶编号字段、航速字段、航向字段都在 `config/ship-track.config.json` 中配置。

默认配置使用：

- 轨迹表：`tb_ais_event_simple_info`
- 索引表：`tb_ship_bucket_index`
- 坐标字段：`longitude_wgs`、`latitude_wgs`
- 时间字段：`event_time`
- 船舶字段：`ship_serial_no`

敏感信息优先写入项目根目录 `.env`，不要提交真实密码或 API Key。项目需要迁移时，源码、配置模板和资源文件应放在项目目录内；运行日志、缓存和本机私有配置不纳入版本库。

## 功能

- 实时位置：`/api/realtime/latest` 返回全量最新船位，`/ws/realtime` 增量推送。
- 态势分析：`/api/analysis/density` 按时间窗、bbox 和 zoom 聚合密度点。
- 单船轨迹：`/api/tracks/single` 按船舶和时间窗抽稀查询。
- 多船轨迹：`/api/tracks/candidates` 框选查船，`/api/tracks/multi` 最多 100 艘抽稀回放。
- 全域回放：`/api/tracks/global-segment` 按 1 小时片段查询当前视野 bbox 内抽稀轨迹。

## 可选索引

`db/optional-indexes.sql` 仅提供可选 ClickHouse 跳数索引 SQL，不会自动执行。

建议流程：

1. 先运行应用确认功能。
2. 用 `db/explain-queries.sql` 记录当前查询计划。
3. 在 ClickHouse 低峰维护窗口人工执行 `ADD INDEX`。
4. 需要历史数据命中索引时，再人工执行 `MATERIALIZE INDEX`。

注意：历史索引物化可能耗时较长，执行前应确认磁盘空间和维护窗口。

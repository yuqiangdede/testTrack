-- 可选优化脚本：不要在应用启动、安装或测试时自动执行。
-- ADD INDEX 只新增元数据通常较快；MATERIALIZE INDEX 会扫描历史数据，5420 万行主表可能耗时较长，请在低峰维护窗口执行。

ALTER TABLE tb_ais_track_raw
  ADD INDEX IF NOT EXISTS idx_event_time_minmax event_time TYPE minmax GRANULARITY 4;

ALTER TABLE tb_ais_track_raw
  ADD INDEX IF NOT EXISTS idx_lon_lat_minmax (longitude_wgs, latitude_wgs) TYPE minmax GRANULARITY 4;

-- 如需让历史数据命中新增跳数索引，再逐条人工执行：
-- ALTER TABLE tb_ais_track_raw MATERIALIZE INDEX idx_event_time_minmax;
-- ALTER TABLE tb_ais_track_raw MATERIALIZE INDEX idx_lon_lat_minmax;

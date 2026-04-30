-- 索引效果检查示例。执行可选索引前后分别运行，对比 Indexes/Granules 命中情况。

EXPLAIN indexes = 1
SELECT count()
FROM tb_ais_event_simple_info
WHERE event_time >= toDateTime('2026-04-17 00:00:00', 'Asia/Shanghai')
  AND event_time < toDateTime('2026-04-17 01:00:00', 'Asia/Shanghai')
  AND longitude_wgs BETWEEN 121 AND 124
  AND latitude_wgs BETWEEN 38 AND 41;

EXPLAIN indexes = 1
SELECT uniqExact(ship_serial_no)
FROM tb_ship_bucket_index
WHERE bucket_start >= toDateTime('2026-04-17 00:00:00', 'Asia/Shanghai')
  AND bucket_start < toDateTime('2026-04-17 01:00:00', 'Asia/Shanghai')
  AND max_lng >= 121 AND min_lng <= 124
  AND max_lat >= 38 AND min_lat <= 41;

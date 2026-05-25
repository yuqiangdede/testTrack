-- 索引效果检查示例。执行可选索引前后分别运行，对比 Indexes/Granules 命中情况。

EXPLAIN indexes = 1
SELECT count()
FROM tb_ais_track_raw
WHERE event_time >= toDateTime('2026-04-17 00:00:00', 'Asia/Shanghai')
  AND event_time < toDateTime('2026-04-17 01:00:00', 'Asia/Shanghai')
  AND longitude_wgs BETWEEN 121 AND 124
  AND latitude_wgs BETWEEN 38 AND 41;

EXPLAIN indexes = 1
SELECT ship_serial_no
FROM tb_ship_bucket_index
WHERE bucket_start >= toDateTime('2026-04-17 00:00:00', 'Asia/Shanghai')
  AND bucket_start < toDateTime('2026-04-17 01:00:00', 'Asia/Shanghai')
  AND grid_05_lng BETWEEN 6020 AND 6080
  AND grid_05_lat BETWEEN 2560 AND 2620
GROUP BY ship_serial_no
HAVING maxMerge(max_lng) >= 121 AND minMerge(min_lng) <= 124
  AND maxMerge(max_lat) >= 38 AND minMerge(min_lat) <= 41;

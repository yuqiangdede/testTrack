-- Runtime ClickHouse tables used by the track APIs.

CREATE TABLE IF NOT EXISTS tb_ais_track_raw
(
  ship_serial_no LowCardinality(String),
  longitude_wgs Float32 CODEC(Gorilla),
  latitude_wgs Float32 CODEC(Gorilla),
  ground_speed Float32 CODEC(Gorilla),
  ground_course Float32 CODEC(Gorilla),
  event_time DateTime('Asia/Shanghai') CODEC(Delta, ZSTD),
  type UInt8
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (ship_serial_no, event_time)
TTL event_time + toIntervalYear(1)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS tb_ais_track_thin
(
  ship_serial_no LowCardinality(String),
  bucket_size UInt32,
  bucket_start DateTime('Asia/Shanghai') CODEC(Delta, ZSTD),
  event_time DateTime('Asia/Shanghai') CODEC(Delta, ZSTD),
  longitude_wgs Float32 CODEC(Gorilla),
  latitude_wgs Float32 CODEC(Gorilla),
  ground_speed Float32 CODEC(Gorilla),
  ground_course Float32 CODEC(Gorilla),
  type UInt8,
  grid_05_lng Int32 MATERIALIZED toInt32(floor((longitude_wgs + 180) / 0.05)),
  grid_05_lat Int32 MATERIALIZED toInt32(floor((latitude_wgs + 90) / 0.05)),
  grid_5_lng Int32 MATERIALIZED toInt32(floor((longitude_wgs + 180) / 0.5)),
  grid_5_lat Int32 MATERIALIZED toInt32(floor((latitude_wgs + 90) / 0.5)),
  dedup_version UInt32 MATERIALIZED toUInt32(4294967295 - toUnixTimestamp(event_time))
)
ENGINE = ReplacingMergeTree(dedup_version)
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (ship_serial_no, bucket_size, bucket_start)
TTL bucket_start + toIntervalYear(1)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS tb_ship_bucket_index
(
  grid_05_lng Int32,
  grid_05_lat Int32,
  grid_5_lng Int32,
  grid_5_lat Int32,
  ship_serial_no LowCardinality(String),
  bucket_start DateTime('Asia/Shanghai'),
  min_lng AggregateFunction(min, Float32),
  max_lng AggregateFunction(max, Float32),
  min_lat AggregateFunction(min, Float32),
  max_lat AggregateFunction(max, Float32),
  point_count AggregateFunction(count)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (grid_05_lng, grid_05_lat, bucket_start, ship_serial_no)
TTL bucket_start + toIntervalYear(1)
SETTINGS index_granularity = 8192;

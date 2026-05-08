CREATE TABLE IF NOT EXISTS tb_ship_track_simplified
(
  ship_serial_no String,
  simplify_level UInt8,
  event_time DateTime64(3, 'Asia/Shanghai'),
  longitude_wgs Float64,
  latitude_wgs Float64,
  ground_speed Nullable(Float64),
  ground_course Nullable(Float64),
  ship_name Nullable(String),
  isAis Nullable(UInt8),
  inserted_at DateTime DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (simplify_level, ship_serial_no, event_time);

CREATE TABLE IF NOT EXISTS tb_ship_simplify_offset
(
  ship_serial_no String,
  last_event_time DateTime64(3, 'Asia/Shanghai'),
  processed_rows UInt64,
  updated_at DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY ship_serial_no;

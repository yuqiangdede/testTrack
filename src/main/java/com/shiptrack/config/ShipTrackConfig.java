package com.shiptrack.config;

import java.util.List;

public class ShipTrackConfig {
  public ClickHouse clickhouse = new ClickHouse();
  public Tables tables = new Tables();
  public Columns columns = new Columns();
  public BucketIndexColumns bucketIndexColumns = new BucketIndexColumns();
  public Query query = new Query();
  public MapConfig map = new MapConfig();

  public static class ClickHouse {
    public String jdbcUrl = "jdbc:clickhouse://127.0.0.1:8123/default";
    public String username = "default";
    public String password = "";
  }

  public static class Tables {
    public String track = "tb_ais_event_simple_info";
    public String bucketIndex = "tb_ship_bucket_index";
  }

  public static class Columns {
    public String shipId = "ship_serial_no";
    public String shipName = "ship_name";
    public String eventTime = "event_time";
    public String longitude = "longitude_wgs";
    public String latitude = "latitude_wgs";
    public String speed = "ground_speed";
    public String heading = "ground_course";
  }

  public static class BucketIndexColumns {
    public String shipId = "ship_serial_no";
    public String bucketStart = "bucket_start";
    public String minLng = "min_lng";
    public String maxLng = "max_lng";
    public String minLat = "min_lat";
    public String maxLat = "max_lat";
  }

  public static class Query {
    public int latestLookbackHours = 5;
    public int realtimeWindowMinutes = 10;
    public int realtimePollSeconds = 5;
    public int clickhouseTimeoutSeconds = 30;
    public int latestPageSize = 30000;
    public int maxLatestShips = 300000;
    public int realtimeCacheMaxShips = 1000000;
    public int maxRealtimeDeltaShips = 10000;
    public int maxDensityCells = 30000;
    public int maxTrackPointsPerShip = 3000;
    public int maxSingleTrackPoints = 1200;
    public int maxMultiShips = 100;
    public int maxGlobalSegmentPoints = 50000;
    public int globalSegmentHours = 1;
    public int logMemorySeconds = 0;
  }

  public static class MapConfig {
    public String coordinateSystem = "wgs84";
    public List<Double> defaultCenter = List.of(122.4, 39.2);
    public int defaultZoom = 8;
  }
}

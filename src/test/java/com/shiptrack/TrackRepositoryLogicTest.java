package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import com.shiptrack.track.TrackRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class TrackRepositoryLogicTest {
  @Test
  void calculatesDensityGridByZoom() {
    TrackRepository repository = repository();
    assertThat(repository.densityGridSizeDegrees(13)).isEqualTo(0.05);
    assertThat(repository.densityGridSizeDegrees(11)).isEqualTo(0.05);
    assertThat(repository.densityGridSizeDegrees(9)).isEqualTo(0.1);
    assertThat(repository.densityGridSizeDegrees(7)).isEqualTo(0.5);
    assertThat(repository.densityGridSizeDegrees(6)).isEqualTo(0.5);
  }

  @Test
  void calculatesBucketSizeForSingleAndMulti() {
    TrackRepository repository = repository();
    String start = "2026-04-17T00:00:00.000Z";
    String end = "2026-04-17T01:00:00.000Z";
    assertThat(repository.calculateBucketSizeSeconds(13, start, end, 3000, "single")).isEqualTo(5);
    assertThat(repository.calculateBucketSizeSeconds(8, start, end, 3000, "multi")).isEqualTo(30);
  }

  @Test
  void calculatesBucketSizeForLocalTimeWindow() {
    TrackRepository repository = repository();
    String start = "2026-04-17 15:00:00";
    String end = "2026-04-17 17:00:00";
    assertThat(repository.calculateBucketSizeSeconds(8, start, end, 3000, "global")).isEqualTo(60);
  }

  @Test
  void mapsZoomToFixedThinBucket() {
    TrackRepository repository = repository();
    assertThat(repository.bucketSizeForZoom(6)).isEqualTo(1800);
    assertThat(repository.bucketSizeForZoom(8)).isEqualTo(300);
    assertThat(repository.bucketSizeForZoom(11)).isEqualTo(60);
    assertThat(repository.bucketSizeForZoom(14)).isEqualTo(60);
  }

  @Test
  void returnsDatabaseStats() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.queryOne(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("trackPoints", 123L, "ships", 45L));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.databaseStats()).containsEntry("trackPoints", 123L).containsEntry("ships", 45L);
  }

  @Test
  void returnsWindowStats() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("trackPoints", 12L, "ships", 5L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.windowStats("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"))
        .containsEntry("trackPoints", 12L)
        .containsEntry("ships", 5L);
  }

  @Test
  void returnsWindowStatsWithBoundingBox() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("trackPoints", 18L, "ships", 7L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.windowStats("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", new BBox(121, 38, 124, 41)))
        .containsEntry("trackPoints", 18L)
        .containsEntry("ships", 7L);
  }

  @Test
  void returnsLatestRowsWithTypeBasedAisAggregation() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.latest(null, 100, null, false, "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"))
        .hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(clickHouse).query(sqlCaptor.capture(), anyMap());
    assertThat(sqlCaptor.getValue()).contains("`type` IN (1, 4, 5, 7)");
    assertThat(sqlCaptor.getValue()).doesNotContain("match(toString(");
  }

  @Test
  void returnsSingleTrackRowsFromFixedThinTable() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.singleTrackRows("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", 8, null, "auto", null))
        .hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("PREWHERE");
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`");
    assertThat(sqlCaptor.getValue()).doesNotContain("LIMIT");
    assertThat(paramsCaptor.getValue()).containsEntry("shipId", "A1").containsEntry("bucketSeconds", 300).doesNotContainKeys("level", "limit");
  }

  @Test
  void returnsSingleTrackRowsInRawModeWithoutBucketGrouping() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.singleTrackRows("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", 8, null, "raw", null))
        .hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("PREWHERE");
    assertThat(sqlCaptor.getValue()).doesNotContain("GROUP BY bucket");
    assertThat(sqlCaptor.getValue()).contains("ORDER BY time ASC");
    assertThat(paramsCaptor.getValue()).containsEntry("shipId", "A1").doesNotContainKey("bucketSeconds").doesNotContainKey("limit");
  }

  @Test
  void returnsSingleTrackRowsWithManualBucketSeconds() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.singleTrackRows("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", 8, null, "manual", 45))
        .hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`");
    assertThat(paramsCaptor.getValue()).containsEntry("bucketSeconds", 60).doesNotContainKeys("level", "limit");
  }

  @Test
  void returnsMultiTrackRowsWithoutBoundingBoxWhenNoBBoxIsProvided() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    repository.trackRows(
        List.of("A1", "B2"),
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        8,
        null,
        "multi",
        "auto",
        null);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`");
    assertThat(sqlCaptor.getValue()).doesNotContain("{west").doesNotContain("{east").doesNotContain("{south").doesNotContain("{north");
    assertThat(sqlCaptor.getValue()).doesNotContain("LIMIT");
    assertThat(paramsCaptor.getValue()).containsEntry("bucketSeconds", 300).doesNotContainKeys("west", "east", "south", "north", "level", "limit");
  }

  @Test
  void readsBucketedMultiRowsOnlyFromThinTable() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.trackRows(
        List.of("A1", "B2"),
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        8,
        null,
        "multi",
        "auto",
        null)).hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`").contains("bucket_size = {bucketSeconds: UInt32}");
    assertThat(sqlCaptor.getValue()).doesNotContain("GROUP BY `ship_serial_no`, bucket");
    assertThat(paramsCaptor.getValue()).containsEntry("bucketSeconds", 300).doesNotContainKey("limit");
  }

  @Test
  void keepsMultiTrackRowsOnThinTableWhenRawModeIsRequested() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.trackRows(
        List.of("A1", "B2"),
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        8,
        null,
        "multi",
        "raw",
        null)).hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`");
    assertThat(sqlCaptor.getValue()).contains("bucket_size = {bucketSeconds: UInt32}");
    assertThat(sqlCaptor.getValue()).doesNotContain("FROM `tb_ais_track_raw`");
    assertThat(sqlCaptor.getValue()).doesNotContain("LIMIT");
    assertThat(paramsCaptor.getValue()).containsEntry("bucketSeconds", 300).doesNotContainKey("limit");
  }

  @Test
  void returnsMultiStatsWithoutDatabaseStats() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(
            List.of(Map.of("trackPoints", 12L, "ships", 5L)),
            List.of(Map.of("trackPoints", 6L, "ships", 2L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.multiStats("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", new BBox(121, 38, 124, 41)))
        .containsEntry("windowTrackPoints", 12L)
        .containsEntry("windowShips", 5L)
        .containsEntry("bboxTrackPoints", 6L)
        .containsEntry("bboxShips", 2L)
        .doesNotContainKeys("databaseTrackPoints", "databaseShips");
    verify(clickHouse, never()).queryOne(anyString());
  }

  @Test
  void pagesCandidatesFromBucketIndexOnly() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of());
    TrackRepository repository = repository(clickHouse);

    repository.candidates(
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        new BBox(121, 38, 124, 41),
        2,
        1200);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("countMerge(point_count) AS points");
    assertThat(sqlCaptor.getValue()).contains("trim(`ship_serial_no`) AS shipId");
    assertThat(sqlCaptor.getValue()).contains("GROUP BY trim(`ship_serial_no`)");
    assertThat(sqlCaptor.getValue()).contains("minMerge(`min_lng`) <= {east: Float64}");
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ship_bucket_index`");
    assertThat(sqlCaptor.getValue()).doesNotContain("FROM `tb_ais_track_thin`");
    assertThat(sqlCaptor.getValue()).doesNotContain("JOIN").doesNotContain("shipType").doesNotContain("isAis");
    assertThat(sqlCaptor.getValue()).contains("grid_05_lng BETWEEN {westGrid05Lng: Int32}");
    assertThat(sqlCaptor.getValue()).contains("LIMIT {limit: UInt32} OFFSET {offset: UInt32}");
    assertThat(paramsCaptor.getValue()).containsEntry("limit", 1000).containsEntry("offset", 1000)
        .doesNotContainKey("shipTypes")
        .containsEntry("westGrid05Lng", 6020).containsEntry("southGrid05Lat", 2560);
  }

  @Test
  void returnsDensityCellCount() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("cells", 88L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.densityCellCount("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", null, 8))
        .isEqualTo(88L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(clickHouse).query(sqlCaptor.capture(), anyMap());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`").contains("bucket_size = 300");
  }

  @Test
  void returnsStaticDensityRowsWithoutBucketTime() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of());
    TrackRepository repository = repository(clickHouse);

    repository.density("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", new BBox(121, 38, 124, 41), 8);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(clickHouse).query(sqlCaptor.capture(), anyMap());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`").contains("bucket_size = 300");
    assertThat(sqlCaptor.getValue()).doesNotContain("bucketStart");
    assertThat(sqlCaptor.getValue()).doesNotContain("dateDiff('second'");
    assertThat(sqlCaptor.getValue()).contains("GROUP BY lng, lat");
    assertThat(sqlCaptor.getValue()).contains("ORDER BY count DESC");
  }

  @Test
  void ignoresRequestedStepMinutesForStaticDensity() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of());
    TrackRepository repository = repository(clickHouse);

    repository.density("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", new BBox(121, 38, 124, 41), 8, 30);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).doesNotContain("dateDiff('second'");
    assertThat(sqlCaptor.getValue()).doesNotContain("bucketStart");
    assertThat(paramsCaptor.getValue()).doesNotContainKey("stepSeconds");
  }

  @Test
  void returnsGlobalSegmentFromSimplifiedTableFirst() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    repository.globalSegment("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", 8, "auto", null);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`");
    assertThat(sqlCaptor.getValue()).doesNotContain("tb_ship_bucket_index");
    assertThat(sqlCaptor.getValue()).doesNotContain("LIMIT");
    assertThat(sqlCaptor.getValue()).contains("bucket_size = 1800");
    assertThat(sqlCaptor.getValue()).contains("GROUP BY `ship_serial_no`, bucket_start");
    assertThat(sqlCaptor.getValue()).contains("argMax(`longitude_wgs`, `event_time`) AS lng");
    assertThat(sqlCaptor.getValue()).contains("ORDER BY time ASC, shipId ASC");
    assertThat(paramsCaptor.getValue()).containsEntry("start", "2026-04-17T00:00:00.000Z")
        .containsEntry("end", "2026-04-17T01:00:00.000Z")
        .doesNotContainKeys("bucketSeconds", "level");
    assertThat(sqlCaptor.getValue()).doesNotContain("{west").doesNotContain("{east").doesNotContain("{south").doesNotContain("{north");
    assertThat(sqlCaptor.getValue()).doesNotContain("bbox");
  }

  @Test
  void keepsGlobalSegmentOnPositionFramesWhenRawModeIsRequested() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.globalSegment("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", 8, "raw", null))
        .hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("FROM `tb_ais_track_thin`");
    assertThat(sqlCaptor.getValue()).doesNotContain("tb_ship_bucket_index");
    assertThat(sqlCaptor.getValue()).contains("bucket_size = 1800");
    assertThat(sqlCaptor.getValue()).contains("GROUP BY `ship_serial_no`, bucket_start");
    assertThat(sqlCaptor.getValue()).doesNotContain("LIMIT");
    assertThat(sqlCaptor.getValue()).contains("ORDER BY time ASC, shipId ASC");
    assertThat(paramsCaptor.getValue()).doesNotContainKey("bucketSeconds").containsEntry("start", "2026-04-17T00:00:00.000Z").containsEntry("end", "2026-04-17T01:00:00.000Z");
  }

  @Test
  void countsSingleTrackRawPointsWithTimeWindow() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of(Map.of("trackPoints", 77L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.singleTrackPointCount("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"))
        .isEqualTo(77L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("count() AS trackPoints");
    assertThat(sqlCaptor.getValue()).contains("PREWHERE `ship_serial_no` IN {shipIdVariants: Array(String)}");
    assertThat(sqlCaptor.getValue()).contains("`event_time` >= parseDateTime64BestEffort({start: String}, 3, 'Asia/Shanghai')");
    assertThat(sqlCaptor.getValue()).contains("`event_time` < parseDateTime64BestEffort({end: String}, 3, 'Asia/Shanghai')");
    assertThat(paramsCaptor.getValue()).containsEntry("shipIdVariants", List.of("A1", " A1", "A1 ", " A1 "))
        .containsEntry("start", "2026-04-17T00:00:00.000Z")
        .containsEntry("end", "2026-04-17T01:00:00.000Z");
  }

  @Test
  void countsMultiTrackRawPointsWithShipIdFilterAndTimeWindow() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of(Map.of("trackPoints", 88L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.multiTrackPointCount(List.of("A1", "B2"), "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"))
        .isEqualTo(88L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("count() AS trackPoints");
    assertThat(sqlCaptor.getValue()).contains("`ship_serial_no` IN {shipIdVariants: Array(String)}");
    assertThat(sqlCaptor.getValue()).contains("`event_time` >= parseDateTime64BestEffort({start: String}, 3, 'Asia/Shanghai')");
    assertThat(sqlCaptor.getValue()).contains("`event_time` < parseDateTime64BestEffort({end: String}, 3, 'Asia/Shanghai')");
    assertThat(paramsCaptor.getValue()).containsEntry("shipIdVariants", List.of("A1", " A1", "A1 ", " A1 ", "B2", " B2", "B2 ", " B2 "))
        .containsEntry("start", "2026-04-17T00:00:00.000Z")
        .containsEntry("end", "2026-04-17T01:00:00.000Z");
  }

  private TrackRepository repository() {
    return repository(mock(ClickHouseHttpClient.class));
  }

  private TrackRepository repository(ClickHouseHttpClient clickHouse) {
    ShipConfigService configService = mock(ShipConfigService.class);
    org.mockito.Mockito.when(configService.config()).thenReturn(new ShipTrackConfig());
    return new TrackRepository(clickHouse, configService);
  }
}

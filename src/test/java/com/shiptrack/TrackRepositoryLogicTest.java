package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
    assertThat(repository.densityGridSizeDegrees(13)).isEqualTo(0.0025);
    assertThat(repository.densityGridSizeDegrees(11)).isEqualTo(0.005);
    assertThat(repository.densityGridSizeDegrees(9)).isEqualTo(0.01);
    assertThat(repository.densityGridSizeDegrees(7)).isEqualTo(0.03);
    assertThat(repository.densityGridSizeDegrees(6)).isEqualTo(0.08);
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
  void returnsSingleTrackRowsWithDedicatedQuery() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(List.of(Map.of("shipId", "A1", "time", "2026-04-17 00:00:00")));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.singleTrackRows("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", 8, null))
        .hasSize(1);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("PREWHERE");
    assertThat(paramsCaptor.getValue()).containsEntry("shipId", "A1").containsEntry("limit", 1200);
  }

  @Test
  void returnsMultiStatsIndependently() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.queryOne(anyString())).thenReturn(Map.of("trackPoints", 123L, "ships", 45L));
    when(clickHouse.query(anyString(), anyMap()))
        .thenReturn(
            List.of(Map.of("trackPoints", 12L, "ships", 5L)),
            List.of(Map.of("trackPoints", 6L, "ships", 2L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.multiStats("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", new BBox(121, 38, 124, 41)))
        .containsEntry("databaseTrackPoints", 123L)
        .containsEntry("databaseShips", 45L)
        .containsEntry("windowTrackPoints", 12L)
        .containsEntry("windowShips", 5L)
        .containsEntry("bboxTrackPoints", 6L)
        .containsEntry("bboxShips", 2L);
  }

  @Test
  void pagesCandidatesWithTypeFilterAndOffset() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(anyString(), anyMap())).thenReturn(List.of());
    TrackRepository repository = repository(clickHouse);

    repository.candidates(
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        new BBox(121, 38, 124, 41),
        2,
        150,
        List.of("ais"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(clickHouse).query(sqlCaptor.capture(), paramsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("WHERE shipType = 'ais'");
    assertThat(sqlCaptor.getValue()).contains("LIMIT {limit: UInt32} OFFSET {offset: UInt32}");
    assertThat(paramsCaptor.getValue()).containsEntry("limit", 100).containsEntry("offset", 100);
  }

  @Test
  void returnsDensityCellCount() {
    ClickHouseHttpClient clickHouse = mock(ClickHouseHttpClient.class);
    when(clickHouse.query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("cells", 88L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.densityCellCount("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", null, 8))
        .isEqualTo(88L);
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

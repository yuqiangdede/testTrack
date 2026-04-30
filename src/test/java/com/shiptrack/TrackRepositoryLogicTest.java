package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.track.TrackRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    when(clickHouse.query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(List.of(Map.of("trackPoints", 12L, "ships", 5L)));
    TrackRepository repository = repository(clickHouse);

    assertThat(repository.windowStats("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"))
        .containsEntry("trackPoints", 12L)
        .containsEntry("ships", 5L);
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

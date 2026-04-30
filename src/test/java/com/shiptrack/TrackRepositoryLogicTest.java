package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.track.TrackRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

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

  private TrackRepository repository() {
    ShipConfigService configService = mock(ShipConfigService.class);
    org.mockito.Mockito.when(configService.config()).thenReturn(new ShipTrackConfig());
    return new TrackRepository(mock(ClickHouseHttpClient.class), configService);
  }
}

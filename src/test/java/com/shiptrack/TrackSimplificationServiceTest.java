package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.track.TrackSimplificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackSimplificationServiceTest {
  @Test
  void sedRdpKeepsEmptyAndShortInputs() {
    TrackSimplificationService service = service();

    assertThat(service.simplifyRows(List.of(), 0.01)).isEmpty();

    List<Map<String, Object>> single = List.of(point(0, 0));
    assertThat(service.simplifyRows(single, 0.01)).isSameAs(single);
  }

  @Test
  void sedRdpDropsMiddlePointsOnStraightLine() {
    TrackSimplificationService service = service();
    List<Map<String, Object>> rows = List.of(
        point(0, 0),
        point(1, 1),
        point(2, 2),
        point(3, 3));

    assertThat(service.simplifyRows(rows, 0.01))
        .extracting(row -> row.get("time"))
        .containsExactly("2026-04-17 00:00:00", "2026-04-17 00:00:03");
  }

  @Test
  void sedRdpKeepsShapeChangingPoint() {
    TrackSimplificationService service = service();
    List<Map<String, Object>> rows = List.of(
        point(0, 0),
        point(1, 3),
        point(2, 0));

    assertThat(service.simplifyRows(rows, 0.5))
        .extracting(row -> row.get("time"))
        .containsExactly("2026-04-17 00:00:00", "2026-04-17 00:00:01", "2026-04-17 00:00:02");
  }

  private TrackSimplificationService service() {
    ShipConfigService configService = mock(ShipConfigService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    return new TrackSimplificationService(mock(ClickHouseHttpClient.class), configService);
  }

  private Map<String, Object> point(double lng, double lat) {
    int second = (int) (lng == 0 ? 0 : lng);
    return Map.of(
        "lng", lng,
        "lat", lat,
        "time", "2026-04-17 00:00:0" + second);
  }
}

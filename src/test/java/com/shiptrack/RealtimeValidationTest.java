package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.realtime.RealtimeService;
import com.shiptrack.track.TrackRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RealtimeValidationTest {
  @Test
  void validatesIsoTimeWindow() {
    RealtimeService service = service();
    assertThat(service.validateTimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z").start())
        .isEqualTo("2026-04-17T00:00:00.000Z");
  }

  @Test
  void buildsRealtimeWindowFromPointAndMinutes() {
    RealtimeService service = service();
    assertThat(service.realtimeWindowFromParams("2026-04-17T01:00:00.000Z", "30"))
        .satisfies(window -> {
          assertThat(window.start()).isEqualTo("2026-04-17 08:30:00");
          assertThat(window.end()).isEqualTo("2026-04-17 09:00:00");
        });
  }

  @Test
  void defaultsRealtimeWindowToConfiguredAnchorWithoutWatermarkLookup() {
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService service = service(repository);

    assertThat(service.realtimeWindowFromParams(null, null))
        .satisfies(window -> {
          assertThat(window.start()).isEqualTo("2026-05-15 23:30:00");
          assertThat(window.end()).isEqualTo("2026-05-16 00:00:00");
        });
    verify(repository, never()).watermark();
  }

  @Test
  void defaultRealtimeResponseDoesNotQueryWhenStartupCacheIsUnavailable() {
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService service = service(repository);

    assertThat(service.latestResponse(service.realtimeWindowFromParams(null, null)))
        .containsEntry("source", "memory")
        .containsEntry("ready", false)
        .containsEntry("items", java.util.List.of());
    verifyNoInteractions(repository);
  }

  @Test
  void latestResponseIncludesShipTypeInCompactRows() {
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService service = service(repository);
    org.mockito.Mockito.when(repository.latest(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(Map.of(
            "shipId", "A1",
            "shipName", "A1",
            "lng", 122,
            "lat", 39,
            "shipType", 7,
            "time", "2026-04-17 00:00:00")));

    service.warmLatestCache(service.validateTimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    Map<String, Object> response = service.latestResponse(
        service.validateTimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));

    assertThat(response.get("fields")).asList().contains("isAis", "shipType");
    assertThat(((List<?>) ((List<?>) response.get("items")).get(0)).get(8)).isEqualTo(7.0);
  }

  @Test
  void viewportFiltersCachedShipsByBoundingBoxAndTypeWithoutQuerying() {
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService service = service(repository);
    org.mockito.Mockito.when(repository.latest(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(
            Map.of("shipId", "A1", "shipName", "A1", "lng", 122.0, "lat", 39.0, "shipType", 1, "time", "2026-04-17 00:10:00"),
            Map.of("shipId", "B2", "shipName", "B2", "lng", 122.2, "lat", 39.2, "shipType", 2, "time", "2026-04-17 00:11:00"),
            Map.of("shipId", "C3", "shipName", "C3", "lng", 124.0, "lat", 41.0, "shipType", 1, "time", "2026-04-17 00:12:00")));
    TimeWindow window = service.validateTimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z");
    service.warmLatestCache(window);
    clearInvocations(repository);

    Map<String, Object> response = service.viewportResponse(window, new BBox(121.5, 38.5, 122.5, 39.5), 12, Set.of("ais"));

    assertThat(response).containsEntry("mode", "points").containsEntry("total", 1);
    assertThat(response.get("items")).asList().hasSize(1);
    assertThat(((List<?>) ((List<?>) response.get("items")).get(0)).get(0)).isEqualTo("A1");
    verify(repository, never()).latest(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void viewportReturnsAggregateRowsAtLowZoom() {
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService service = service(repository);
    org.mockito.Mockito.when(repository.latest(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(
            Map.of("shipId", "A1", "shipName", "A1", "lng", 122.01, "lat", 39.01, "shipType", 1, "time", "2026-04-17 00:10:00"),
            Map.of("shipId", "B2", "shipName", "B2", "lng", 122.03, "lat", 39.02, "shipType", 2, "time", "2026-04-17 00:11:00")));
    TimeWindow window = service.validateTimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z");
    service.warmLatestCache(window);

    Map<String, Object> response = service.viewportResponse(window, new BBox(121.5, 38.5, 122.5, 39.5), 8, Set.of());

    assertThat(response).containsEntry("mode", "aggregate").containsEntry("total", 2);
    assertThat(response.get("fields")).asList().containsExactly("lng", "lat", "count");
    assertThat(((List<?>) ((List<?>) response.get("items")).get(0)).get(2)).isEqualTo(2L);
  }

  @Test
  void buildsGlobalWindowFromPointAndHours() {
    RealtimeService service = service();
    assertThat(service.globalWindowFromParams("2026-04-17T09:00:00.000Z", "2"))
        .satisfies(window -> {
          assertThat(window.start()).isEqualTo("2026-04-17 15:00:00");
          assertThat(window.end()).isEqualTo("2026-04-17 17:00:00");
        });
  }

  @Test
  void defaultsGlobalWindowHoursToConfiguredValue() {
    RealtimeService service = service();
    assertThat(service.globalWindowFromParams("2026-04-17T09:00:00.000Z", null))
        .satisfies(window -> {
          assertThat(window.start()).isEqualTo("2026-04-17 16:00:00");
          assertThat(window.end()).isEqualTo("2026-04-17 17:00:00");
        });
  }

  @Test
  void rejectsInvalidTimeWindowRange() {
    RealtimeService service = service();
    assertThatThrownBy(() -> service.validateTimeWindow("2026-04-17T01:00:00.000Z", "2026-04-17T00:00:00.000Z"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("time window range is invalid");
  }

  @Test
  void rejectsInvalidRealtimeWindowMinutes() {
    RealtimeService service = service();
    assertThatThrownBy(() -> service.realtimeWindowFromParams("2026-04-17T01:00:00.000Z", "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realtime window minutes is invalid");
  }

  @Test
  void rejectsInvalidGlobalReplayHours() {
    RealtimeService service = service();
    assertThatThrownBy(() -> service.globalWindowFromParams("2026-04-17T01:00:00.000Z", "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("global replay hours is invalid");
  }

  @Test
  void validatesZoomRange() {
    RealtimeService service = service();
    assertThat(service.validateZoom("8")).isEqualTo(8);
    assertThatThrownBy(() -> service.validateZoom("19"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zoom parameter is invalid");
  }

  private RealtimeService service() {
    return service(mock(TrackRepository.class));
  }

  private RealtimeService service(TrackRepository repository) {
    ShipConfigService configService = mock(ShipConfigService.class);
    org.mockito.Mockito.when(configService.config()).thenReturn(new ShipTrackConfig());
    return new RealtimeService(repository, configService);
  }
}

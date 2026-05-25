package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.model.BBox;
import com.shiptrack.model.TimeWindow;
import com.shiptrack.realtime.RealtimeService;
import com.shiptrack.telemetry.RequestMetricsService;
import com.shiptrack.track.TrackRepository;
import com.shiptrack.web.ApiController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiControllerTest {
  @Test
  void databaseStatsUsesRealtimeCache() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.databaseStats()).thenReturn(Map.of("trackPoints", 123L, "ships", 45L));
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    Map<String, Object> result = controller.databaseStats();

    assertThat(result).containsEntry("trackPoints", 123L).containsEntry("ships", 45L);
    verify(repository, never()).databaseStats();
  }

  @Test
  void realtimeViewportUsesRealtimeMemoryService() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.realtimeWindowFromParams(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17 00:00:00", "2026-04-17 00:30:00"));
    when(realtimeService.validateZoom("12")).thenReturn(12);
    when(realtimeService.viewportResponse(
        any(TimeWindow.class),
        any(BBox.class),
        anyInt(),
        any()))
        .thenReturn(Map.of("mode", "points", "items", List.of()));
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    Map<String, Object> result = controller.realtimeViewport(null, null, "2026-04-17T00:30:00.000Z", "30", "121", "38", "124", "41", "12", "ais,radar");

    assertThat(result).containsEntry("mode", "points");
    verify(realtimeService).viewportResponse(
        eq(new TimeWindow("2026-04-17 00:00:00", "2026-04-17 00:30:00")),
        eq(new BBox(121, 38, 124, 41)),
        eq(12),
        eq(java.util.Set.of("ais", "radar")));
    verify(repository, never()).latest(anyString(), anyInt(), any(), anyBoolean(), anyString(), anyString());
  }

  @Test
  void multiTrackIgnoresRequestBoundingBox() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.trackRows(anyList(), anyString(), anyString(), anyInt(), any(), anyString(), anyString(), any()))
        .thenReturn(List.of());
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    ApiController.MultiTrackRequest request = new ApiController.MultiTrackRequest();
    request.shipIds = List.of("A1");
    request.start = "2026-04-17T00:00:00.000Z";
    request.end = "2026-04-17T01:00:00.000Z";
    request.zoom = 8;
    request.samplingMode = "manual";
    request.bucketSeconds = 45;
    request.bbox = new ApiController.RequestBBox();
    request.bbox.west = 121;
    request.bbox.south = 38;
    request.bbox.east = 124;
    request.bbox.north = 41;

    controller.multi(request);

    verify(repository).trackRows(
        eq(List.of("A1")),
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"),
        eq(8),
        isNull(BBox.class),
        eq("multi"),
        eq("manual"),
        eq(45));
  }

  @Test
  void singleTrackPassesSamplingParameters() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.singleTrackRows(anyString(), anyString(), anyString(), anyInt(), any(), anyString(), any()))
        .thenReturn(List.of());
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    controller.single("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", "8", "raw", "120");

    verify(repository).singleTrackRows(
        eq("A1"),
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"),
        anyInt(),
        isNull(BBox.class),
        eq("raw"),
        eq(120));
  }

  @Test
  void singleTrackDefaultsToAutoSampling() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.singleTrackRows(anyString(), anyString(), anyString(), anyInt(), any(), anyString(), any()))
        .thenReturn(List.of());
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    controller.single("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z", "8", null, null);

    verify(repository).singleTrackRows(
        eq("A1"),
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"),
        anyInt(),
        isNull(BBox.class),
        eq("auto"),
        isNull(Integer.class));
  }

  @Test
  void globalSegmentPassesSamplingParameters() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.globalWindowFromParams(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.globalSegment(anyString(), anyString(), anyInt(), anyString(), any()))
        .thenReturn(List.of());
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    controller.globalSegment("2026-04-17T01:00:00.000Z", "1", "8", "manual", "90");

    verify(repository).globalSegment(
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"),
        anyInt(),
        eq("manual"),
        eq(90));
  }

  @Test
  void singleTrackPointStatsCountsRawPointsAfterTimeValidation() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.singleTrackPointCount(anyString(), anyString(), anyString())).thenReturn(321L);
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    Map<String, Object> result = controller.singleTrackPointStats("A1", "2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z");

    assertThat(result).containsEntry("trackPoints", 321L);
    verify(repository).singleTrackPointCount(
        eq("A1"),
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"));
  }

  @Test
  void multiTrackCompactRowsPreserveShipType() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.trackRows(anyList(), anyString(), anyString(), anyInt(), any(), anyString(), anyString(), any()))
        .thenReturn(List.of(Map.of(
            "shipId", "A1",
            "shipName", "A1",
            "lng", 122,
            "lat", 39,
            "speed", 3,
            "heading", 4,
            "isAis", 1,
            "shipType", 5,
            "time", "2026-04-17 00:00:00")));
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());
    ApiController.MultiTrackRequest request = new ApiController.MultiTrackRequest();
    request.shipIds = List.of("A1");
    request.start = "2026-04-17T00:00:00.000Z";
    request.end = "2026-04-17T01:00:00.000Z";

    Map<String, Object> result = controller.multi(request);

    assertThat(result.get("fields")).asList().containsSubsequence("isAis", "shipType", "time");
    assertThat(result.get("items")).asList().hasSize(1);
    assertThat(((List<?>) ((List<?>) result.get("items")).get(0)).get(7)).isEqualTo(5);
  }

  @Test
  void densityPassesOptionalStepMinutes() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(realtimeService.validateZoom(any())).thenReturn(8);
    when(repository.density(anyString(), anyString(), any(BBox.class), anyInt(), any())).thenReturn(List.of());
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    controller.density(
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        null,
        null,
        "121",
        "38",
        "124",
        "41",
        "8",
        "30");

    verify(repository).density(
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"),
        eq(new BBox(121, 38, 124, 41)),
        eq(8),
        eq(30));
  }

  @Test
  void densityRejectsInvalidStepBeforeQuery() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    when(configService.config()).thenReturn(new ShipTrackConfig());
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(realtimeService.validateZoom(any())).thenReturn(8);
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());

    assertThatThrownBy(() -> controller.density(
        "2026-04-17T00:00:00.000Z",
        "2026-04-17T01:00:00.000Z",
        null,
        null,
        "121",
        "38",
        "124",
        "41",
        "8",
        "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("density step minutes is invalid");

    verify(repository, never()).density(anyString(), anyString(), any(BBox.class), anyInt(), any());
  }

  @Test
  void multiTrackPointStatsLimitsShipsBeforeCounting() {
    ShipConfigService configService = mock(ShipConfigService.class);
    TrackRepository repository = mock(TrackRepository.class);
    RealtimeService realtimeService = mock(RealtimeService.class);
    ShipTrackConfig config = new ShipTrackConfig();
    config.query.maxMultiShips = 2;
    when(configService.config()).thenReturn(config);
    when(realtimeService.validateTimeWindow(anyString(), anyString()))
        .thenReturn(new TimeWindow("2026-04-17T00:00:00.000Z", "2026-04-17T01:00:00.000Z"));
    when(repository.multiTrackPointCount(anyList(), anyString(), anyString())).thenReturn(654L);
    ApiController controller = new ApiController(configService, repository, realtimeService, new RequestMetricsService());
    ApiController.MultiTrackRequest request = new ApiController.MultiTrackRequest();
    request.shipIds = List.of("A1", "B2", "C3");
    request.start = "2026-04-17T00:00:00.000Z";
    request.end = "2026-04-17T01:00:00.000Z";

    Map<String, Object> result = controller.multiTrackPointStats(request);

    assertThat(result).containsEntry("trackPoints", 654L);
    verify(repository).multiTrackPointCount(
        eq(List.of("A1", "B2")),
        eq("2026-04-17T00:00:00.000Z"),
        eq("2026-04-17T01:00:00.000Z"));
  }
}

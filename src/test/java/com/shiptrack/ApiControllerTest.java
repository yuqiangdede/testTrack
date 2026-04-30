package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

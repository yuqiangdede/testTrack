package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.shiptrack.config.ShipConfigService;
import com.shiptrack.config.ShipTrackConfig;
import com.shiptrack.realtime.RealtimeService;
import com.shiptrack.track.TrackRepository;
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
    ShipConfigService configService = mock(ShipConfigService.class);
    org.mockito.Mockito.when(configService.config()).thenReturn(new ShipTrackConfig());
    return new RealtimeService(mock(TrackRepository.class), configService);
  }
}

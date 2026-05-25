package com.shiptrack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shiptrack.model.TimeWindow;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlobalReplayWebSocketHandlerTest {
  @Test
  void splitsOneHourIntoTwoThirtyMinuteChunks() {
    List<TimeWindow> chunks = GlobalReplayWebSocketHandler.chunkWindows(
        new TimeWindow("2026-04-17 15:00:00", "2026-04-17 16:00:00"));

    assertThat(chunks).containsExactly(
        new TimeWindow("2026-04-17 15:00:00", "2026-04-17 15:30:00"),
        new TimeWindow("2026-04-17 15:30:00", "2026-04-17 16:00:00"));
  }

  @Test
  void keepsShortTailChunkWhenWindowIsNotAligned() {
    List<TimeWindow> chunks = GlobalReplayWebSocketHandler.chunkWindows(
        new TimeWindow("2026-04-17 15:00:00", "2026-04-17 16:10:00"));

    assertThat(chunks).containsExactly(
        new TimeWindow("2026-04-17 15:00:00", "2026-04-17 15:30:00"),
        new TimeWindow("2026-04-17 15:30:00", "2026-04-17 16:00:00"),
        new TimeWindow("2026-04-17 16:00:00", "2026-04-17 16:10:00"));
  }

  @Test
  void rejectsEmptyOrReversedWindowBeforeQuerying() {
    assertThatThrownBy(() -> GlobalReplayWebSocketHandler.chunkWindows(
        new TimeWindow("2026-04-17 16:00:00", "2026-04-17 16:00:00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("global replay window range is invalid");
  }
}

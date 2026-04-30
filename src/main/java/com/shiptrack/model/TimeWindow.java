package com.shiptrack.model;

public record TimeWindow(String start, String end) {
  public static TimeWindow empty() {
    return new TimeWindow("", "");
  }
}

package com.shiptrack.model;

public record BBox(double west, double south, double east, double north) {
  public void validate() {
    if (!Double.isFinite(west) || !Double.isFinite(south) || !Double.isFinite(east) || !Double.isFinite(north)) {
      throw new IllegalArgumentException("bbox parameters are required");
    }
    if (west >= east || south >= north) {
      throw new IllegalArgumentException("bbox range is invalid");
    }
  }
}

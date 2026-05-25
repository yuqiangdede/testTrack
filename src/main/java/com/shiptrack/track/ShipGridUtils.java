package com.shiptrack.track;

public final class ShipGridUtils {
  private ShipGridUtils() {}

  public static int lngGrid(double lng, double gridSize) {
    return (int) Math.floor((lng + 180D) / gridSize);
  }

  public static int latGrid(double lat, double gridSize) {
    return (int) Math.floor((lat + 90D) / gridSize);
  }
}

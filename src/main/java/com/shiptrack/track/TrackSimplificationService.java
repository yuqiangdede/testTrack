package com.shiptrack.track;

import com.shiptrack.clickhouse.ClickHouseHttpClient;
import com.shiptrack.config.ShipConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TrackSimplificationService {
  public TrackSimplificationService(ClickHouseHttpClient clickHouse, ShipConfigService configService) {
  }

  public int backfillAll() {
    return 0;
  }

  public List<Map<String, Object>> simplifyRows(List<Map<String, Object>> rows, double tolerance) {
    if (rows == null || rows.size() <= 2 || tolerance <= 0) {
      return rows == null ? List.of() : rows;
    }
    boolean[] keep = new boolean[rows.size()];
    keep[0] = true;
    keep[rows.size() - 1] = true;
    simplifyRange(rows, 0, rows.size() - 1, tolerance * tolerance, keep);
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < rows.size(); i += 1) {
      if (keep[i]) {
        result.add(rows.get(i));
      }
    }
    return result;
  }

  private void simplifyRange(List<Map<String, Object>> rows, int start, int end, double toleranceSquared, boolean[] keep) {
    if (end <= start + 1) {
      return;
    }
    double maxDistance = -1;
    int maxIndex = -1;
    Point a = point(rows.get(start));
    Point b = point(rows.get(end));
    for (int i = start + 1; i < end; i += 1) {
      double distance = squaredDistanceToSegment(point(rows.get(i)), a, b);
      if (distance > maxDistance) {
        maxDistance = distance;
        maxIndex = i;
      }
    }
    if (maxIndex >= 0 && maxDistance > toleranceSquared) {
      keep[maxIndex] = true;
      simplifyRange(rows, start, maxIndex, toleranceSquared, keep);
      simplifyRange(rows, maxIndex, end, toleranceSquared, keep);
    }
  }

  private Point point(Map<String, Object> row) {
    return new Point(toDouble(row.get("lng")), toDouble(row.get("lat")));
  }

  private double squaredDistanceToSegment(Point p, Point a, Point b) {
    double dx = b.x() - a.x();
    double dy = b.y() - a.y();
    if (dx == 0 && dy == 0) {
      return squaredDistance(p, a);
    }
    double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / (dx * dx + dy * dy);
    t = Math.max(0, Math.min(1, t));
    return squaredDistance(p, new Point(a.x() + t * dx, a.y() + t * dy));
  }

  private double squaredDistance(Point a, Point b) {
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    return dx * dx + dy * dy;
  }

  private double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private record Point(double x, double y) {}
}

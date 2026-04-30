package com.shiptrack.telemetry;

import org.springframework.stereotype.Service;

@Service
public class RequestMetricsService {
  private static final ThreadLocal<RequestMetrics> CURRENT = new ThreadLocal<>();

  public void start(String endpoint) {
    CURRENT.set(new RequestMetrics(endpoint));
  }

  public void recordDbElapsed(long elapsedNanos) {
    RequestMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.dbCalls++;
      metrics.dbElapsedNanos += Math.max(0, elapsedNanos);
    }
  }

  public RequestMetrics snapshot() {
    RequestMetrics metrics = CURRENT.get();
    return metrics == null ? RequestMetrics.empty() : metrics.copy();
  }

  public void stop() {
    CURRENT.remove();
  }

  public static long nanosToMillis(long nanos) {
    return Math.max(0, nanos / 1_000_000L);
  }

  public static final class RequestMetrics {
    private final String endpoint;
    private long dbElapsedNanos;
    private int dbCalls;

    private RequestMetrics(String endpoint) {
      this.endpoint = endpoint;
    }

    private RequestMetrics(String endpoint, long dbElapsedNanos, int dbCalls) {
      this.endpoint = endpoint;
      this.dbElapsedNanos = dbElapsedNanos;
      this.dbCalls = dbCalls;
    }

    private static RequestMetrics empty() {
      return new RequestMetrics("", 0, 0);
    }

    private RequestMetrics copy() {
      return new RequestMetrics(endpoint, dbElapsedNanos, dbCalls);
    }

    public String endpoint() {
      return endpoint;
    }

    public long dbElapsedNanos() {
      return dbElapsedNanos;
    }

    public int dbCalls() {
      return dbCalls;
    }
  }
}

package com.shiptrack.backfill;

public class BackfillOptions {
  public boolean execute;
  public boolean resume;
  public String sourceStart = "2026-04-17";
  public int sourceDays = 3;
  public String targetStart = "2026-01-07";
  public int targetDays = 100;
  public int batchDays = 5;
  public double reserveGiB = 20;
  public double safetyFactor = 1.2;
  public String hostDrive = "D";
  public int insertTimeoutSeconds = 0;
  public int selectTimeoutSeconds = 120;
}

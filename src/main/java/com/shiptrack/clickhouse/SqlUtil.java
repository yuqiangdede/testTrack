package com.shiptrack.clickhouse;

public final class SqlUtil {
  private SqlUtil() {}

  public static String ident(String value) {
    if (value == null || !value.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
      throw new IllegalArgumentException("Invalid ClickHouse identifier: " + value);
    }
    return "`" + value + "`";
  }

  public static String sqlDateParam(String name) {
    return "parseDateTime64BestEffort({" + name + ": String}, 3, 'Asia/Shanghai')";
  }

  public static String sqlString(String value) {
    return "'" + String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  public static String toDateTime64(String date) {
    return "toDateTime64(" + sqlString(date + " 00:00:00") + ", 3, 'Asia/Shanghai')";
  }

  public static String toDateTime(String date) {
    return "toDateTime(" + sqlString(date + " 00:00:00") + ", 'Asia/Shanghai')";
  }
}

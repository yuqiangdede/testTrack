package com.shiptrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shiptrack.clickhouse.SqlUtil;
import org.junit.jupiter.api.Test;

class SqlUtilTest {
  @Test
  void quotesValidIdentifiers() {
    assertThat(SqlUtil.ident("event_time")).isEqualTo("`event_time`");
  }

  @Test
  void rejectsInvalidIdentifiers() {
    assertThatThrownBy(() -> SqlUtil.ident("event_time;drop table x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void escapesSqlString() {
    assertThat(SqlUtil.sqlString("a'b\\c")).isEqualTo("'a\\'b\\\\c'");
  }
}

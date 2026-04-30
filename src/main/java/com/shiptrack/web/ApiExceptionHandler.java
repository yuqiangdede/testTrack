package com.shiptrack.web;

import com.shiptrack.clickhouse.ClickHouseException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@ConditionalOnProperty(name = "ship.mode", havingValue = "server", matchIfMissing = true)
public class ApiExceptionHandler {
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handle(Exception error) {
    String message = error.getMessage() == null ? "server error" : error.getMessage();
    HttpStatus status = error instanceof ClickHouseException && message.contains("ClickHouse connection failed")
        ? HttpStatus.SERVICE_UNAVAILABLE
        : HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}

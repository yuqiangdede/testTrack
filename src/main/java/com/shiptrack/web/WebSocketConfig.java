package com.shiptrack.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final RealtimeWebSocketHandler handler;
  private final GlobalReplayWebSocketHandler globalReplayHandler;

  public WebSocketConfig(RealtimeWebSocketHandler handler, GlobalReplayWebSocketHandler globalReplayHandler) {
    this.handler = handler;
    this.globalReplayHandler = globalReplayHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/ws/realtime").setAllowedOrigins("*");
    registry.addHandler(globalReplayHandler, "/ws/global-replay").setAllowedOrigins("*");
  }
}

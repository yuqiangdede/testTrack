package com.shiptrack.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.realtime.RealtimeService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
  private final RealtimeService realtimeService;
  private final ObjectMapper objectMapper;

  public RealtimeWebSocketHandler(RealtimeService realtimeService, ObjectMapper objectMapper) {
    this.realtimeService = realtimeService;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(realtimeService.readyPayload())));
  }
}

package com.shiptrack.web;

import com.shiptrack.config.ShipConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "ship.mode", havingValue = "server", matchIfMissing = true)
public class WebConfig implements WebMvcConfigurer {
  private final ShipConfigService configService;

  public WebConfig(ShipConfigService configService) {
    this.configService = configService;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String publicLocation = configService.root().resolve("public").toUri().toString();
    registry.addResourceHandler("/**").addResourceLocations(publicLocation);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
  }
}

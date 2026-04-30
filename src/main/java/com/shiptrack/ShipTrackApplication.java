package com.shiptrack;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ShipTrackApplication {
  public static void main(String[] args) {
    boolean backfill = args.length > 0 && "backfill".equals(args[0]);
    SpringApplication application = new SpringApplication(ShipTrackApplication.class);
    application.setDefaultProperties(Map.of("ship.mode", backfill ? "backfill" : "server"));
    if (backfill) {
      application.setWebApplicationType(WebApplicationType.NONE);
    }
    ConfigurableApplicationContext context = application.run(args);
    if (backfill) {
      System.exit(SpringApplication.exit(context));
    }
  }
}

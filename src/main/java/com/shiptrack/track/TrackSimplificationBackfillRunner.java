package com.shiptrack.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class TrackSimplificationBackfillRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(TrackSimplificationBackfillRunner.class);

  private final TrackSimplificationService simplificationService;
  private final ConfigurableApplicationContext context;

  public TrackSimplificationBackfillRunner(TrackSimplificationService simplificationService, ConfigurableApplicationContext context) {
    this.simplificationService = simplificationService;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption("ship.simplify.backfill")) {
      return;
    }
    int ships = simplificationService.backfillAll();
    log.info("track simplify backfill completed ships={}", ships);
    context.close();
  }
}

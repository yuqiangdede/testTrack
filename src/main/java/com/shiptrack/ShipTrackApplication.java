package com.shiptrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ShipTrackApplication {
  public static void main(String[] args) {
    SpringApplication.run(ShipTrackApplication.class, args);
  }
}

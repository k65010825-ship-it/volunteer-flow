package com.volunteerflow;

import com.volunteerflow.registration.PromotionExpiryProperties;
import com.volunteerflow.checkin.ActivityFinalizationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({PromotionExpiryProperties.class, ActivityFinalizationProperties.class})
public class VolunteerFlowApplication {

  public static void main(String[] args) {
    SpringApplication.run(VolunteerFlowApplication.class, args);
  }
}

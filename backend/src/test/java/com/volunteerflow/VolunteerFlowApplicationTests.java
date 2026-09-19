package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.volunteerflow.registration.PromotionExpiryJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class VolunteerFlowApplicationTests {

  @Autowired
  private ApplicationContext context;

  @Test
  void contextLoads() {}

  @Test
  void testProfileDoesNotRegisterAutomaticExpiryJob() {
    assertThat(context.getBeansOfType(PromotionExpiryJob.class)).isEmpty();
  }
}

package stage5support;

import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.event.*;
import com.volunteerflow.notification.*;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationAnswerValidator;
import com.volunteerflow.registration.RegistrationSubmissionService;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Outside com.volunteerflow: never discovered by the production application's component scan. */
@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan(basePackages = "com.volunteerflow", annotationClass = Mapper.class)
@Import({KafkaEventConfiguration.class, NotificationConsumerConfiguration.class, DomainEventCodec.class,
    EventTopicRouter.class, TransactionalOutboxEventPublisher.class, OutboxClaimService.class,
    OutboxStateService.class, OutboxRelayService.class, NotificationEventHandler.class, NotificationService.class,
    KafkaNotificationListener.class, EventPipelineMetrics.class, EventPipelineHealthIndicator.class,
    RegistrationSubmissionService.class, ActivityRegistrationPolicyService.class,
    OrganizationAuthorizationService.class, RegistrationAnswerValidator.class, AuditService.class})
public class Stage5PipelineApplication {
  @Bean Clock clock() { return Clock.systemUTC(); }
}

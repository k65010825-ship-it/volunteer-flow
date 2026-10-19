package com.volunteerflow.registration;

import com.volunteerflow.infrastructure.event.DomainEventCommand;
import com.volunteerflow.infrastructure.event.DomainEventPublisher;
import com.volunteerflow.infrastructure.event.NotificationEventTemplate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Captures each event, without notification deduplication that could hide duplicate publication. */
public final class CapturingDomainEventPublisher implements DomainEventPublisher {
  private final List<DomainEventCommand> commands = new ArrayList<>();
  private RuntimeException nextFailure;
  private Runnable beforePublish = () -> {};

  @Override
  public UUID publish(DomainEventCommand command) {
    beforePublish.run();
    if (nextFailure != null) {
      RuntimeException failure = nextFailure;
      nextFailure = null;
      throw failure;
    }
    commands.add(command);
    return UUID.randomUUID();
  }

  @Override
  public int publishMany(Collection<Long> userIds, NotificationEventTemplate template) {
    var recipients = new LinkedHashSet<>(userIds);
    recipients.forEach(userId -> publish(template.forUser(userId)));
    return recipients.size();
  }

  public List<DomainEventCommand> commands() { return List.copyOf(commands); }
  public void failNextPublish(RuntimeException failure) { nextFailure = failure; }
  public void beforePublish(Runnable assertion) { beforePublish = assertion; }
}

package com.volunteerflow.registration;

import com.volunteerflow.notification.NotificationWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** In-memory notification boundary that mirrors the database user/key uniqueness contract. */
public final class CapturingNotificationWriter implements NotificationWriter {
  private final Map<String, NotificationCommand> uniqueCommands = new LinkedHashMap<>();
  private RuntimeException nextFailure;
  private Runnable beforeWrite = () -> {};

  @Override
  public void write(NotificationCommand command) {
    beforeWrite.run();
    if (nextFailure != null) {
      RuntimeException failure = nextFailure;
      nextFailure = null;
      throw failure;
    }
    uniqueCommands.putIfAbsent(command.userId() + ":" + command.deduplicationKey(), command);
  }

  @Override
  public int writeMany(Collection<Long> userIds, NotificationTemplate template) {
    LinkedHashSet<Long> uniqueUserIds = new LinkedHashSet<>(userIds);
    uniqueUserIds.forEach(
        userId ->
            write(
                new NotificationCommand(
                    template.organizationId(),
                    userId,
                    template.type(),
                    template.title(),
                    template.content(),
                    template.businessType(),
                    template.businessId(),
                    template.deduplicationKey())));
    return uniqueUserIds.size();
  }

  public List<NotificationCommand> commands() {
    return new ArrayList<>(uniqueCommands.values());
  }

  public void failNextWrite(RuntimeException failure) {
    nextFailure = failure;
  }

  public void beforeWrite(Runnable assertion) {
    beforeWrite = assertion;
  }
}

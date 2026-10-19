package com.volunteerflow.infrastructure.event;

/** Consumer-facing immutable data only; never a persistence entity. */
public sealed interface DomainEventPayload permits NotificationIntentPayload, CheckinStatusPayload,
    RegistrationStatusPayload {}

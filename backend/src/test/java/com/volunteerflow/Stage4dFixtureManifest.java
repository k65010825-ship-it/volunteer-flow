package com.volunteerflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record Stage4dFixtureManifest(
    String runId,
    String dataset,
    @JsonSerialize(using = ToStringSerializer.class) long organizationId,
    @JsonSerialize(using = ToStringSerializer.class) long activityId,
    @JsonSerialize(using = ToStringSerializer.class) long positionId,
    @JsonSerialize(using = ToStringSerializer.class) long checkinSessionId,
    List<Member> members) {

  Stage4dFixtureManifest {
    members = List.copyOf(members);
  }

  static void write(
      Path outputDirectory,
      Stage4dFixtureIdentity identity,
      Stage4dFixtureManifest manifest,
      List<Token> tokens,
      ObjectMapper objectMapper)
      throws IOException {
    if (!identity.runId().equals(manifest.runId())
        || !identity.dataset().value().equals(manifest.dataset())) {
      throw new IllegalArgumentException("manifest identity does not match the requested fixture");
    }
    if (manifest.members().size() != 100 || tokens.size() != 100) {
      throw new IllegalArgumentException("fixture artifacts must contain exactly 100 members");
    }

    Set<Long> memberIds = new HashSet<>();
    Set<String> usernames = new HashSet<>();
    for (Member member : manifest.members()) {
      if (member.userId() <= 0
          || member.username() == null
          || member.username().isBlank()
          || !memberIds.add(member.userId())
          || !usernames.add(member.username())) {
        throw new IllegalArgumentException("manifest members must be unique and complete");
      }
    }

    Set<Long> tokenUserIds = new HashSet<>();
    Set<String> tokenValues = new HashSet<>();
    for (Token token : tokens) {
      if (token.userId() <= 0
          || token.accessToken() == null
          || token.accessToken().isBlank()
          || token.expiresAt() == null
          || !tokenUserIds.add(token.userId())
          || !tokenValues.add(token.accessToken())) {
        throw new IllegalArgumentException("tokens must be unique and complete");
      }
    }
    if (!tokenUserIds.equals(memberIds)) {
      throw new IllegalArgumentException("token user IDs must exactly match manifest member IDs");
    }

    writeValue(
        outputDirectory.resolve(identity.artifactName("manifest")), manifest, objectMapper);
    writeValue(
        outputDirectory.resolve(identity.artifactName("tokens")), List.copyOf(tokens), objectMapper);
  }

  static void writeValue(Path destination, Object value, ObjectMapper objectMapper)
      throws IOException {
    Path temporary =
        Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
    try {
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  record Member(
      @JsonSerialize(using = ToStringSerializer.class) long userId, String username) {}

  record Token(
      @JsonSerialize(using = ToStringSerializer.class) long userId,
      String accessToken,
      Instant expiresAt) {}

  record Verification(
      long confirmed,
      long waitlisted,
      List<Long> waitlistSequences,
      List<Long> userIds,
      long nextWaitlistSequence) {

    Verification {
      waitlistSequences = List.copyOf(waitlistSequences);
      userIds = List.copyOf(userIds);
    }
  }
}

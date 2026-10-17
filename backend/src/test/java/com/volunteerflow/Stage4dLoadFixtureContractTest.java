package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage4dLoadFixtureContractTest {

  private static final String RUN_ID = "6f96e55b-4d96-4d1d-9c6e-95d92df6e444";

  @TempDir Path temporaryDirectory;

  @Test
  void acceptsOnlyExactFixtureActions() {
    assertThat(
            List.of("snapshot", "seed", "verify", "cleanup", "assert-snapshot").stream()
                .map(Stage4dLoadFixtureIT::validatedAction))
        .containsExactly("snapshot", "seed", "verify", "cleanup", "assert-snapshot");

    for (String invalid : new String[] {"", " seed", "SEED", "reset", "drop"}) {
      assertThatThrownBy(() -> Stage4dLoadFixtureIT.validatedAction(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> Stage4dLoadFixtureIT.validatedAction(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresAnExistingOutputDirectory() throws Exception {
    Path existing = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
    Path file = Files.createFile(temporaryDirectory.resolve("not-a-directory"));

    assertThat(Stage4dLoadFixtureIT.validatedOutputDirectory(existing.toString()))
        .isEqualTo(existing.toAbsolutePath().normalize());
    assertThatThrownBy(
            () ->
                Stage4dLoadFixtureIT.validatedOutputDirectory(
                    temporaryDirectory.resolve("missing").toString()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dLoadFixtureIT.validatedOutputDirectory(file.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dLoadFixtureIT.validatedOutputDirectory("relative-output"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesNonSecretManifestAndSeparateTokensForExactlyOneHundredMembers() throws Exception {
    Stage4dFixtureIdentity identity = Stage4dFixtureIdentity.parse(RUN_ID, "normal");
    List<Stage4dFixtureManifest.Member> members =
        IntStream.rangeClosed(1, 100)
            .mapToObj(
                index ->
                    new Stage4dFixtureManifest.Member(
                        1_000L + index, identity.username(index)))
            .toList();
    List<Stage4dFixtureManifest.Token> tokens =
        IntStream.rangeClosed(1, 100)
            .mapToObj(
                index ->
                    new Stage4dFixtureManifest.Token(
                        1_000L + index,
                        "jwt-token-" + index,
                        Instant.parse("2026-10-17T12:00:00Z")))
            .toList();
    Stage4dFixtureManifest manifest =
        new Stage4dFixtureManifest(RUN_ID, "normal", 10L, 20L, 30L, members);

    Stage4dFixtureManifest.write(
        temporaryDirectory, identity, manifest, tokens, new ObjectMapper().findAndRegisterModules());

    JsonNode manifestJson =
        new ObjectMapper()
            .readTree(Files.readString(temporaryDirectory.resolve("manifest-normal.json")));
    JsonNode tokenJson =
        new ObjectMapper()
            .readTree(Files.readString(temporaryDirectory.resolve("tokens-normal.json")));
    assertThat(manifestJson.path("members").size()).isEqualTo(100);
    assertThat(manifestJson.toString()).doesNotContain("accessToken").doesNotContain("jwt-token-");
    assertThat(tokenJson.size()).isEqualTo(100);
    assertThat(tokenJson.findValuesAsText("accessToken")).hasSize(100).doesNotHaveDuplicates();
    assertThat(tokenJson.toString()).doesNotContain("password");
  }

  @Test
  void rejectsTokenSetsThatDoNotMatchManifestMembersExactly() {
    Stage4dFixtureIdentity identity = Stage4dFixtureIdentity.parse(RUN_ID, "outage");
    List<Stage4dFixtureManifest.Member> members =
        IntStream.rangeClosed(1, 100)
            .mapToObj(index -> new Stage4dFixtureManifest.Member((long) index, identity.username(index)))
            .toList();
    List<Stage4dFixtureManifest.Token> tokens =
        IntStream.rangeClosed(1, 100)
            .mapToObj(
                index ->
                    new Stage4dFixtureManifest.Token(
                        index == 100 ? 101L : (long) index,
                        "token-" + index,
                        Instant.parse("2026-10-17T12:00:00Z")))
            .toList();
    Stage4dFixtureManifest manifest =
        new Stage4dFixtureManifest(RUN_ID, "outage", 10L, 20L, 30L, members);

    assertThatThrownBy(
            () ->
                Stage4dFixtureManifest.write(
                    temporaryDirectory,
                    identity,
                    manifest,
                    tokens,
                    new ObjectMapper().findAndRegisterModules()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlyTheExactRegistrationOutcome() {
    List<Long> waitlist = IntStream.rangeClosed(1, 80).mapToObj(value -> (long) value).toList();
    List<Long> users = IntStream.rangeClosed(1, 100).mapToObj(value -> (long) value).toList();
    Stage4dFixtureManifest.Verification expected =
        new Stage4dFixtureManifest.Verification(20, 80, waitlist, users, 81);

    Stage4dLoadFixtureIT.assertExpectedVerification(expected);

    assertThatThrownBy(
            () ->
                Stage4dLoadFixtureIT.assertExpectedVerification(
                    new Stage4dFixtureManifest.Verification(21, 79, waitlist, users, 81)))
        .isInstanceOf(IllegalStateException.class);
    List<Long> duplicateSequence = new ArrayList<>(waitlist);
    duplicateSequence.set(79, 79L);
    assertThatThrownBy(
            () ->
                Stage4dLoadFixtureIT.assertExpectedVerification(
                    new Stage4dFixtureManifest.Verification(
                        20, 80, duplicateSequence, users, 81)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                Stage4dLoadFixtureIT.assertExpectedVerification(
                    new Stage4dFixtureManifest.Verification(20, 80, waitlist, users, 80)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cleanupScopeRejectsAmbiguousOrganizationsAndPreservesExactIds() {
    assertThat(Stage4dLoadFixtureIT.requireSingleOrganization(List.of())).isEmpty();
    assertThat(Stage4dLoadFixtureIT.requireSingleOrganization(List.of(42L))).contains(42L);
    assertThatThrownBy(() -> Stage4dLoadFixtureIT.requireSingleOrganization(List.of(42L, 43L)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void canonicalFingerprintIsStableButDetectsAnyRowChangeWithoutLeakingValues() {
    Map<String, List<List<String>>> first = new LinkedHashMap<>();
    first.put("organization", List.of(List.of("2", "private-name"), List.of("1", "alpha")));
    first.put("app_user", List.of(List.of("9", "secret-user")));
    Map<String, List<List<String>>> reordered = new LinkedHashMap<>();
    reordered.put("app_user", List.of(List.of("9", "secret-user")));
    reordered.put("organization", List.of(List.of("1", "alpha"), List.of("2", "private-name")));
    Map<String, List<List<String>>> changed = new LinkedHashMap<>(reordered);
    changed.put("app_user", List.of(List.of("9", "changed-user")));

    String fingerprint = Stage4dLoadFixtureIT.canonicalFingerprint(first);

    assertThat(fingerprint).matches("[0-9a-f]{64}");
    assertThat(Stage4dLoadFixtureIT.canonicalFingerprint(reordered)).isEqualTo(fingerprint);
    assertThat(Stage4dLoadFixtureIT.canonicalFingerprint(changed)).isNotEqualTo(fingerprint);
    assertThat(fingerprint).doesNotContain("private-name").doesNotContain("secret-user");
  }
}

package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityService;
import com.volunteerflow.auth.AccessTokenIssuer;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.checkin.ActivityFinalizationJob;
import com.volunteerflow.checkin.CheckinSessionService;
import com.volunteerflow.checkin.CheckinSessionService.OpenSessionRequest;
import com.volunteerflow.organization.CreateInviteRequest;
import com.volunteerflow.organization.OrganizationService;
import com.volunteerflow.registration.PromotionExpiryJob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSetMetaData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exact, UUID-scoped data fixture for the Stage 4D live load test. */
@SpringBootTest(properties = "volunteerflow.bootstrap-admin.username=")
class Stage4dLoadFixtureIT {

  private static final Set<String> ACTIONS =
      Set.of("snapshot", "seed", "verify", "cleanup", "assert-snapshot");

  private static final List<String> ORGANIZATION_TABLES_CHILD_FIRST =
      List.of(
          "outbox_event",
          "checkin_record",
          "checkin_session",
          "promotion_offer",
          "notification",
          "registration_answer",
          "registration_cycle",
          "registration",
          "activity_position_question",
          "activity_question",
          "activity_change",
          "activity_position",
          "activity",
          "organization_invite",
          "organization_member",
          "rbac_role_permission",
          "rbac_role",
          "audit_log");

  private final AppUserMapper users;
  private final OrganizationService organizations;
  private final ActivityService activities;
  private final CheckinSessionService checkinSessions;
  private final PasswordEncoder passwords;
  private final AccessTokenIssuer tokenIssuer;
  private final JdbcTemplate sql;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @MockitoBean private ActivityFinalizationJob automaticActivityFinalizer;
  @MockitoBean private PromotionExpiryJob automaticPromotionExpiry;

  @Autowired
  Stage4dLoadFixtureIT(
      AppUserMapper users,
      OrganizationService organizations,
      ActivityService activities,
      CheckinSessionService checkinSessions,
      PasswordEncoder passwords,
      AccessTokenIssuer tokenIssuer,
      JdbcTemplate sql,
      ObjectMapper objectMapper,
      Clock clock) {
    this.users = users;
    this.organizations = organizations;
    this.activities = activities;
    this.checkinSessions = checkinSessions;
    this.passwords = passwords;
    this.tokenIssuer = tokenIssuer;
    this.sql = sql;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Test
  void executeRequestedFixtureAction() throws Exception {
    String action = validatedAction(System.getProperty("stage4d.fixture.action"));
    Stage4dFixtureIdentity identity =
        Stage4dFixtureIdentity.parse(
            System.getProperty("stage4d.fixture.run-id"),
            System.getProperty("stage4d.fixture.dataset"));
    Path output =
        validatedOutputDirectory(System.getProperty("stage4d.fixture.output-dir"));

    switch (action) {
      case "snapshot" -> writeSnapshot(output, identity);
      case "seed" -> seed(output, identity);
      case "verify" -> verify(output, identity);
      case "cleanup" -> cleanup(identity);
      case "assert-snapshot" -> assertSnapshot(output, identity);
      default -> throw new IllegalStateException("Unsupported validated action " + action);
    }
  }

  static String validatedAction(String candidate) {
    if (candidate == null || !ACTIONS.contains(candidate)) {
      throw new IllegalArgumentException(
          "stage4d.fixture.action must be snapshot, seed, verify, cleanup, or assert-snapshot");
    }
    return candidate;
  }

  static Path validatedOutputDirectory(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      throw new IllegalArgumentException("stage4d.fixture.output-dir must be an existing directory");
    }
    Path path = Path.of(candidate);
    if (!path.isAbsolute() || !Files.isDirectory(path)) {
      throw new IllegalArgumentException(
          "stage4d.fixture.output-dir must be an existing absolute directory");
    }
    return path.toAbsolutePath().normalize();
  }

  static Optional<Long> requireSingleOrganization(List<Long> organizationIds) {
    if (organizationIds.size() > 1) {
      throw new IllegalStateException("fixture prefix matched more than one organization");
    }
    return organizationIds.stream().findFirst();
  }

  static void assertExpectedVerification(Stage4dFixtureManifest.Verification result) {
    List<Long> expectedSequences =
        IntStream.rangeClosed(1, 80).mapToObj(value -> (long) value).toList();
    if (result.confirmed() != 20
        || result.waitlisted() != 80
        || !result.waitlistSequences().equals(expectedSequences)
        || result.userIds().size() != 100
        || result.userIds().stream().distinct().count() != 100
        || result.nextWaitlistSequence() != 81) {
      throw new IllegalStateException("Stage 4D registration facts do not match 20/80 invariants");
    }
  }

  static String canonicalFingerprint(Map<String, List<List<String>>> tables) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      new TreeMap<>(tables)
          .forEach(
              (table, rows) -> {
                updateDigest(digest, table);
                rows.stream()
                    .map(ArrayList::new)
                    .sorted(Stage4dLoadFixtureIT::compareRows)
                    .forEach(
                        row -> {
                          updateDigest(digest, Integer.toString(row.size()));
                          row.forEach(value -> updateDigest(digest, value));
                        });
              });
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void seed(Path output, Stage4dFixtureIdentity identity) throws IOException {
    assertSchemaReady();
    assertThat(findOrganizationIds(identity.prefix())).isEmpty();
    for (String username : exactUsernames(identity)) {
      assertThat(count("SELECT COUNT(*) FROM app_user WHERE username=?", username)).isZero();
    }

    AppUser administrator =
        createUser(identity.prefix() + "-admin", "Stage 4D Administrator", "PLATFORM_ADMIN");
    List<AppUser> members =
        IntStream.rangeClosed(1, 100)
            .mapToObj(
                index ->
                    createUser(
                        identity.username(index),
                        "Stage 4D Member %03d".formatted(index),
                        "USER"))
            .toList();

    var organization =
        organizations.create(
            administrator.getId(),
            administrator.getPlatformRole(),
            new OrganizationService.CreateOrganizationRequest(
                identity.prefix(), "Isolated Stage 4D load fixture", administrator.getId()));
    administrator.setPlatformRole("USER");
    assertThat(users.updateById(administrator)).isEqualTo(1);

    var invitation =
        organizations.createInvite(
            administrator.getId(),
            organization.getId(),
            new CreateInviteRequest(100, Instant.now(clock).plus(1, ChronoUnit.DAYS)));
    members.forEach(member -> organizations.joinByInvite(member.getId(), invitation.code()));

    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var activity =
        activities.create(
            administrator.getId(),
            organization.getId(),
            new ActivityService.ActivityRequest(
                identity.prefix() + "-activity",
                "Stage 4D concurrent registration fixture",
                "Stage 4D isolated location",
                now.minusHours(1),
                now.plusDays(1),
                now.plusDays(1).plusHours(1),
                now.plusDays(2),
                now.plusDays(2).plusHours(2)));
    var position =
        activities.addPosition(
            administrator.getId(),
            activity.getId(),
            new ActivityService.PositionRequest(
                identity.prefix() + "-position",
                "100 members compete for 20 seats",
                20,
                "FIRST_COME",
                30,
                null,
                null,
                "Stage 4D isolated location"));
    activities.publish(administrator.getId(), activity.getId());
    var checkinSession =
        checkinSessions.open(
            administrator.getId(),
            activity.getId(),
            new OpenSessionRequest(120, "DYNAMIC_QR"));

    List<Stage4dFixtureManifest.Member> manifestMembers =
        members.stream()
            .map(member -> new Stage4dFixtureManifest.Member(member.getId(), member.getUsername()))
            .toList();
    List<Stage4dFixtureManifest.Token> tokens =
        members.stream()
            .map(
                member -> {
                  var issued = tokenIssuer.issue(member);
                  return new Stage4dFixtureManifest.Token(
                      member.getId(), issued.value(), issued.expiresAt());
                })
            .toList();
    Stage4dFixtureManifest manifest =
        new Stage4dFixtureManifest(
            identity.runId(),
            identity.dataset().value(),
            organization.getId(),
            activity.getId(),
            position.getId(),
            checkinSession.sessionId(),
            manifestMembers);
    Stage4dFixtureManifest.write(output, identity, manifest, tokens, objectMapper);
  }

  private void verify(Path output, Stage4dFixtureIdentity identity) throws IOException {
    long organizationId =
        requireSingleOrganization(findOrganizationIds(identity.prefix()))
            .orElseThrow(() -> new IllegalStateException("fixture organization was not found"));
    long activityId = requiredSingleId("activity", organizationId);
    long positionId = requiredSingleId("activity_position", organizationId);

    List<Map<String, Object>> rows =
        sql.queryForList(
            "SELECT user_id,status,waitlist_sequence FROM registration_cycle"
                + " WHERE organization_id=? AND activity_id=? AND position_id=?"
                + " ORDER BY user_id",
            organizationId,
            activityId,
            positionId);
    long confirmed = rows.stream().filter(row -> "CONFIRMED".equals(row.get("status"))).count();
    long waitlisted = rows.stream().filter(row -> "WAITLISTED".equals(row.get("status"))).count();
    List<Long> sequences =
        rows.stream()
            .map(row -> (Number) row.get("waitlist_sequence"))
            .filter(java.util.Objects::nonNull)
            .map(Number::longValue)
            .sorted()
            .toList();
    List<Long> userIds =
        rows.stream().map(row -> ((Number) row.get("user_id")).longValue()).sorted().toList();
    long registrationCount =
        count(
            "SELECT COUNT(*) FROM registration WHERE organization_id=? AND activity_id=?",
            organizationId,
            activityId);
    long distinctRegistrationUsers =
        count(
            "SELECT COUNT(DISTINCT user_id) FROM registration"
                + " WHERE organization_id=? AND activity_id=?",
            organizationId,
            activityId);
    if (rows.size() != 100 || registrationCount != 100 || distinctRegistrationUsers != 100) {
      throw new IllegalStateException("Stage 4D fixture must persist exactly 100 stable registrations");
    }
    Long nextSequence =
        sql.queryForObject(
            "SELECT next_waitlist_sequence FROM activity_position WHERE id=?",
            Long.class,
            positionId);
    var verification =
        new Stage4dFixtureManifest.Verification(
            confirmed, waitlisted, sequences, userIds, nextSequence == null ? -1 : nextSequence);
    assertExpectedVerification(verification);
    Stage4dFixtureManifest.writeValue(
        output.resolve(identity.artifactName("verification")), verification, objectMapper);
  }

  private void cleanup(Stage4dFixtureIdentity identity) {
    List<Long> organizationIds = findOrganizationIds(identity.prefix());
    Optional<Long> organizationId = requireSingleOrganization(organizationIds);
    if (organizationId.isPresent()) {
      for (String table : ORGANIZATION_TABLES_CHILD_FIRST) {
        sql.update(
            "DELETE FROM `" + table + "` WHERE organization_id=?",
            organizationId.orElseThrow());
        assertThat(
                count(
                    "SELECT COUNT(*) FROM `" + table + "` WHERE organization_id=?",
                    organizationId.orElseThrow()))
            .as(table + " cleanup for " + identity.prefix())
            .isZero();
      }
      sql.update(
          "DELETE FROM organization WHERE id=? AND name=?",
          organizationId.orElseThrow(),
          identity.prefix());
    }

    for (String username : exactUsernames(identity)) {
      List<Long> userIds =
          sql.queryForList("SELECT id FROM app_user WHERE username=?", Long.class, username);
      if (userIds.size() > 1) {
        throw new IllegalStateException("exact fixture username matched more than one user");
      }
      for (Long userId : userIds) {
        sql.update("DELETE FROM refresh_session WHERE user_id=?", userId);
        sql.update("DELETE FROM app_user WHERE id=? AND username=?", userId, username);
      }
      assertThat(count("SELECT COUNT(*) FROM app_user WHERE username=?", username)).isZero();
    }
    assertThat(findOrganizationIds(identity.prefix())).isEmpty();
  }

  private void writeSnapshot(Path output, Stage4dFixtureIdentity identity) throws IOException {
    BaselineSnapshot snapshot = currentSnapshot(identity.runId());
    Stage4dFixtureManifest.writeValue(
        output.resolve(identity.artifactName("baseline")), snapshot, objectMapper);
  }

  private void assertSnapshot(Path output, Stage4dFixtureIdentity identity) throws IOException {
    Path baselinePath = output.resolve(identity.artifactName("baseline"));
    BaselineSnapshot expected = objectMapper.readValue(baselinePath.toFile(), BaselineSnapshot.class);
    BaselineSnapshot actual = currentSnapshot(identity.runId());
    if (!expected.equals(actual)) {
      throw new IllegalStateException("pre-existing database fingerprint changed during Stage 4D");
    }
  }

  private BaselineSnapshot currentSnapshot(String runId) {
    String database = sql.queryForObject("SELECT DATABASE()", String.class);
    List<String> tables =
        sql.queryForList(
            "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema=DATABASE() AND table_type='BASE TABLE'"
                + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
            String.class);
    Map<String, List<List<String>>> canonicalRows = new LinkedHashMap<>();
    Map<String, TableFingerprint> fingerprints = new TreeMap<>();
    for (String table : tables) {
      List<List<String>> rows =
          sql.query(
              "SELECT * FROM `" + table + "`",
              resultSet -> {
                List<List<String>> values = new ArrayList<>();
                ResultSetMetaData metadata = resultSet.getMetaData();
                while (resultSet.next()) {
                  List<String> row = new ArrayList<>(metadata.getColumnCount());
                  for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String value = resultSet.getString(index);
                    row.add(value == null ? "<NULL>" : value);
                  }
                  values.add(row);
                }
                return values;
              });
      canonicalRows.put(table, rows);
      fingerprints.put(
          table, new TableFingerprint(rows.size(), canonicalFingerprint(Map.of(table, rows))));
    }
    return new BaselineSnapshot(database, runId, fingerprints, canonicalFingerprint(canonicalRows));
  }

  private AppUser createUser(String username, String realName, String platformRole) {
    AppUser user = new AppUser();
    user.setId(IdWorker.getId());
    user.setUsername(username);
    user.setPasswordHash(passwords.encode("S4d!" + UUID.randomUUID() + "aZ9"));
    user.setRealName(realName);
    user.setStudentNumber(username);
    user.setContact("stage4d-load-only");
    user.setStatus("ACTIVE");
    user.setPlatformRole(platformRole);
    user.setVersion(0);
    assertThat(users.insert(user)).isEqualTo(1);
    return user;
  }

  private List<String> exactUsernames(Stage4dFixtureIdentity identity) {
    List<String> usernames = new ArrayList<>(101);
    usernames.add(identity.prefix() + "-admin");
    IntStream.rangeClosed(1, 100).mapToObj(identity::username).forEach(usernames::add);
    return usernames;
  }

  private List<Long> findOrganizationIds(String exactName) {
    return sql.queryForList("SELECT id FROM organization WHERE name=?", Long.class, exactName);
  }

  private long requiredSingleId(String table, long organizationId) {
    List<Long> ids =
        sql.queryForList(
            "SELECT id FROM `" + table + "` WHERE organization_id=?", Long.class, organizationId);
    if (ids.size() != 1) {
      throw new IllegalStateException(table + " fixture row count must be exactly one");
    }
    return ids.get(0);
  }

  private void assertSchemaReady() {
    assertThat(sql.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("volunteer_flow");
    assertThat(
            count(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name='registration_cycle'"))
        .isEqualTo(1);
  }

  private long count(String statement, Object... arguments) {
    Long value = sql.queryForObject(statement, Long.class, arguments);
    return value == null ? 0 : value;
  }

  private static int compareRows(List<String> left, List<String> right) {
    int common = Math.min(left.size(), right.size());
    for (int index = 0; index < common; index++) {
      int comparison = left.get(index).compareTo(right.get(index));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(left.size(), right.size());
  }

  private static void updateDigest(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    digest.update((byte) ':');
    digest.update(bytes);
    digest.update((byte) ';');
  }

  record TableFingerprint(long rowCount, String sha256) {}

  record BaselineSnapshot(
      String database, String runId, Map<String, TableFingerprint> tables, String sha256) {
    BaselineSnapshot {
      tables = Map.copyOf(tables);
    }
  }
}

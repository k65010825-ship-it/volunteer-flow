package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Stage4dFixtureIdentityTest {

  private static final String RUN_ID = "6f96e55b-4d96-4d1d-9c6e-95d92df6e444";

  @Test
  void acceptsCanonicalUuidAndKnownDataset() {
    Stage4dFixtureIdentity normal = Stage4dFixtureIdentity.parse(RUN_ID, "normal");
    Stage4dFixtureIdentity outage = Stage4dFixtureIdentity.parse(RUN_ID, "outage");

    assertThat(normal.runId()).isEqualTo(RUN_ID);
    assertThat(normal.dataset()).isEqualTo(Stage4dFixtureIdentity.Dataset.NORMAL);
    assertThat(normal.prefix()).isEqualTo("s4d-" + RUN_ID + "-normal");
    assertThat(outage.dataset()).isEqualTo(Stage4dFixtureIdentity.Dataset.OUTAGE);
    assertThat(outage.prefix()).isEqualTo("s4d-" + RUN_ID + "-outage");
  }

  @Test
  void rejectsMalformedOrUppercaseRunIds() {
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(null, "normal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse("", "normal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse("not-a-uuid", "normal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(" " + RUN_ID, "normal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(RUN_ID.toUpperCase(), "normal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(RUN_ID + "/child", "normal"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownDatasets() {
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(RUN_ID, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(RUN_ID, "NORMAL"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(RUN_ID, " normal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage4dFixtureIdentity.parse(RUN_ID, "failure"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildsBoundedDeterministicNames() {
    Stage4dFixtureIdentity identity = Stage4dFixtureIdentity.parse(RUN_ID, "normal");

    assertThat(identity.username(1)).isEqualTo("s4d-" + RUN_ID + "-normal-member-001");
    assertThat(identity.username(100)).isEqualTo("s4d-" + RUN_ID + "-normal-member-100");
    assertThat(identity.username(1).length()).isLessThanOrEqualTo(64);
    assertThatThrownBy(() -> identity.username(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> identity.username(101)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void artifactNamesCannotEscapeOutputDirectory() {
    Stage4dFixtureIdentity identity = Stage4dFixtureIdentity.parse(RUN_ID, "outage");
    Stage4dFixtureIdentity normal = Stage4dFixtureIdentity.parse(RUN_ID, "normal");

    assertThat(identity.artifactName("manifest")).isEqualTo("manifest-outage.json");
    assertThat(identity.artifactName("tokens")).isEqualTo("tokens-outage.json");
    assertThat(identity.artifactName("verification")).isEqualTo("verification-outage.json");
    assertThat(identity.artifactName("baseline")).isEqualTo("baseline-outage.json");
    assertThat(normal.artifactName("baseline")).isEqualTo("baseline-normal.json");
    assertThat(normal.artifactName("baseline")).isNotEqualTo(identity.artifactName("baseline"));

    for (String unsafe : new String[] {"../tokens", "folder/file", "folder\\file", "..", "bad\nname", "unknown"}) {
      assertThatThrownBy(() -> identity.artifactName(unsafe))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}

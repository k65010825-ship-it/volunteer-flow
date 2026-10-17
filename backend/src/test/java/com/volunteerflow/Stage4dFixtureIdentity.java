package com.volunteerflow;

import java.util.UUID;

record Stage4dFixtureIdentity(String runId, Dataset dataset) {

  enum Dataset {
    NORMAL("normal"),
    OUTAGE("outage");

    private final String value;

    Dataset(String value) {
      this.value = value;
    }

    String value() {
      return value;
    }
  }

  static Stage4dFixtureIdentity parse(String runId, String dataset) {
    if (runId == null || runId.isEmpty()) {
      throw new IllegalArgumentException("stage4d.fixture.run-id must be a canonical UUID");
    }

    UUID parsed;
    try {
      parsed = UUID.fromString(runId);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "stage4d.fixture.run-id must be a canonical UUID", exception);
    }
    if (!parsed.toString().equals(runId)) {
      throw new IllegalArgumentException(
          "stage4d.fixture.run-id must be a lowercase canonical UUID");
    }

    Dataset parsedDataset =
        switch (dataset == null ? "" : dataset) {
          case "normal" -> Dataset.NORMAL;
          case "outage" -> Dataset.OUTAGE;
          default ->
              throw new IllegalArgumentException(
                  "stage4d.fixture.dataset must be normal or outage");
        };
    return new Stage4dFixtureIdentity(runId, parsedDataset);
  }

  String prefix() {
    return "s4d-" + runId + "-" + dataset.value();
  }

  String username(int memberIndex) {
    if (memberIndex < 1 || memberIndex > 100) {
      throw new IllegalArgumentException("member index must be between 1 and 100");
    }
    return "%s-member-%03d".formatted(prefix(), memberIndex);
  }

  String artifactName(String kind) {
    return switch (kind == null ? "" : kind) {
      case "manifest", "tokens", "verification" -> kind + "-" + dataset.value() + ".json";
      case "baseline" -> "baseline.json";
      default ->
          throw new IllegalArgumentException(
              "artifact kind must be manifest, tokens, verification, or baseline");
    };
  }
}

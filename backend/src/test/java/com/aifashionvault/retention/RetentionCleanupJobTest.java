package com.aifashionvault.retention;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionCleanupJobTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    @Test void scansAllClassesContinuesFailuresAndRedactsReport() {
        FakeInventory inventory = new FakeInventory();
        for (RetentionClass kind : RetentionClass.values()) inventory.items.add(new RetentionCandidate(kind, "user-secret-key", NOW.minus(Duration.ofHours(24))));
        inventory.failDelete = true;
        RetentionRunReport report = new RetentionCleanupJob(inventory, new RetentionPolicy(Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC))).run();
        assertThat(inventory.listed).containsExactly(RetentionClass.TEMPORARY_GENERATION_INPUT, RetentionClass.FAILED_INTERMEDIATE, RetentionClass.IMPORT_STAGING);
        assertThat(report.counts()).allSatisfy((kind, counts) -> assertThat(counts.get(RetentionOutcome.DELETE_FAILED)).isEqualTo(1));
        assertThat(report.counts().toString()).doesNotContain("user-secret-key");
        assertThat(report.counts()).isUnmodifiable();
    }
    @Test void listingFailureIsCountedAndDoesNotStopSubsequentClasses() {
        FakeInventory inventory = new FakeInventory();
        inventory.items.add(new RetentionCandidate(RetentionClass.FAILED_INTERMEDIATE, "failed", NOW.minus(Duration.ofDays(2))));
        inventory.items.add(new RetentionCandidate(RetentionClass.IMPORT_STAGING, "import", NOW.minus(Duration.ofDays(2))));
        inventory.failList = RetentionClass.FAILED_INTERMEDIATE;

        RetentionRunReport report = new RetentionCleanupJob(inventory, new RetentionPolicy(Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC))).run();

        assertThat(inventory.listed).containsExactly(RetentionClass.TEMPORARY_GENERATION_INPUT, RetentionClass.FAILED_INTERMEDIATE, RetentionClass.IMPORT_STAGING);
        assertThat(report.counts().get(RetentionClass.FAILED_INTERMEDIATE).get(RetentionOutcome.LIST_FAILED)).isOne();
        assertThat(inventory.deleted).doesNotContain("failed");
        assertThat(inventory.deleted).containsExactly("import");
    }

    @Test void unknownAndYoungArePreservedAndAlreadyAbsentIsDeleted() {
        FakeInventory inventory = new FakeInventory();
        inventory.items.add(new RetentionCandidate(RetentionClass.IMPORT_STAGING, "unknown", null));
        inventory.items.add(new RetentionCandidate(RetentionClass.IMPORT_STAGING, "young", NOW.minus(Duration.ofHours(23))));
        inventory.items.add(new RetentionCandidate(RetentionClass.IMPORT_STAGING, "gone", NOW.minus(Duration.ofHours(25))));
        inventory.absent = true;
        RetentionRunReport report = new RetentionCleanupJob(inventory, new RetentionPolicy(Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC))).run();
        var counts = report.counts().get(RetentionClass.IMPORT_STAGING);
        assertThat(counts.get(RetentionOutcome.UNKNOWN_AGE)).isOne();
        assertThat(counts.get(RetentionOutcome.TOO_YOUNG)).isOne();
        assertThat(counts.get(RetentionOutcome.DELETED)).isOne();
    }
    @Test void classMismatchFailsClosed() {
        RetentionInventory inventory = new RetentionInventory() {
            public List<RetentionCandidate> list(RetentionClass kind) { return kind == RetentionClass.IMPORT_STAGING ? List.of(new RetentionCandidate(RetentionClass.FAILED_INTERMEDIATE, "wrong", NOW.minus(Duration.ofDays(2)))) : List.of(); }
            public DeleteOutcome delete(RetentionCandidate c) { throw new AssertionError("must not delete mismatch"); }
        };
        RetentionRunReport report = new RetentionCleanupJob(inventory, new RetentionPolicy(Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC))).run();
        assertThat(report.counts().get(RetentionClass.IMPORT_STAGING).get(RetentionOutcome.SCANNED)).isOne();
    }
    static final class FakeInventory implements RetentionInventory {
        final List<RetentionCandidate> items = new ArrayList<>(); final List<RetentionClass> listed = new ArrayList<>(); final List<String> deleted = new ArrayList<>(); boolean failDelete; boolean absent; RetentionClass failList;
        public List<RetentionCandidate> list(RetentionClass kind) { listed.add(kind); if (kind == failList) throw new RuntimeException(); return items.stream().filter(c -> c.retentionClass() == kind).toList(); }
        public DeleteOutcome delete(RetentionCandidate c) { deleted.add(c.candidateId()); if (failDelete) throw new RuntimeException(); return absent ? DeleteOutcome.ALREADY_ABSENT : DeleteOutcome.DELETED; }
    }
}

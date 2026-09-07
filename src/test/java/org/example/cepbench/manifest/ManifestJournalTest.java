package org.example.cepbench.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestJournalTest {

    private static final String API = "https://api.example.test/srv/iot";

    @Test
    void recordsSurviveReopening(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-a", API)) {
            journal.recordCreated(ResourceKind.DEVICE_TYPE, ResourceKind.DEVICE_TYPE_KEY, "dt1", "type");
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "dev1", "device-one");
        }

        try (ManifestJournal reopened = ManifestJournal.open(dir, "run-a", API)) {
            ManifestState state = reopened.state();
            assertEquals("dt1", state.deviceType().orElseThrow().id());
            assertEquals("dev1", state.find(ResourceKind.DEVICE, "d0001").orElseThrow().id());
            assertEquals(1, state.count(ResourceKind.DEVICE));
        }
    }

    @Test
    void deletionAppendsTombstoneRatherThanRewriting(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-b", API)) {
            journal.recordCreated(ResourceKind.RULE, "r-single-0001", "rule1", "rule-one");
            journal.recordCreated(ResourceKind.RULE, "r-single-0002", "rule2", "rule-two");
            journal.recordDeleted(ResourceKind.RULE, "r-single-0001", "rule1");

            ManifestState state = journal.state();
            assertEquals(1, state.count(ResourceKind.RULE));
            assertTrue(state.find(ResourceKind.RULE, "r-single-0001").isEmpty());
            assertEquals("rule2", state.find(ResourceKind.RULE, "r-single-0002").orElseThrow().id());
        }

        // The create lines are still on disk; only the fold hides the deleted one. That is what makes
        // an interrupted cleanup safe to re-run.
        String raw = Files.readString(dir.resolve("run-b.jsonl"));
        assertTrue(raw.contains("\"id\":\"rule1\""));
        assertTrue(raw.contains("\"op\":\"DELETED\""));
    }

    @Test
    void emptyAfterEverythingIsDeleted(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-c", API)) {
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "dev1", "device-one");
            journal.recordDeleted(ResourceKind.DEVICE, "d0001", "dev1");

            assertTrue(journal.state().isEmpty());
        }
    }

    @Test
    void refusesAManifestWrittenAgainstADifferentHost(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-d", API)) {
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "dev1", "device-one");
        }

        // Ids from one environment mean nothing in another; issuing deletes with them could remove
        // somebody else's resources.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ManifestJournal.open(dir, "run-d", "https://other.example.test/srv/iot"));
        assertTrue(failure.getMessage().contains("run-d.jsonl"));
    }

    @Test
    void aTornFinalLineIsReportedRatherThanSilentlyDropped(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-e", API)) {
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "dev1", "device-one");
        }
        // Simulate the process being killed part way through writing a record.
        Files.writeString(dir.resolve("run-e.jsonl"), "{\"op\":\"CREATED\",\"kind\":\"DEV",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        try (ManifestJournal journal = ManifestJournal.open(dir, "run-e", API)) {
            ManifestState state = journal.state();
            assertEquals(1, state.count(ResourceKind.DEVICE), "intact records still load");
            assertFalse(state.unreadableLines().isEmpty(),
                    "the operator must be told a record may be missing before reusing the runId");
        }
    }

    @Test
    void laterCreateForTheSameKeyWins(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-f", API)) {
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "old", "device-one");
            journal.recordDeleted(ResourceKind.DEVICE, "d0001", "old");
            journal.recordCreated(ResourceKind.DEVICE, "d0001", "new", "device-one");

            assertEquals("new", journal.state().find(ResourceKind.DEVICE, "d0001").orElseThrow().id());
        }
    }

    @Test
    void ruleRecordsCarryTheirScenarioAndDevicesAcrossAReopen(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-g", API)) {
            journal.recordRuleCreated("r-multi-0001", "rule1", "rule-one",
                    "MULTI_SELECT_TWO_DEVICE", List.of("devA", "devB"));
        }

        ResourceRef rule = ManifestJournal.readState(dir, "run-g")
                .find(ResourceKind.RULE, "r-multi-0001").orElseThrow();

        assertEquals("MULTI_SELECT_TWO_DEVICE", rule.scenario());
        // Order matters: it is the order the ids appear in the rendered when clause.
        assertEquals(List.of("devA", "devB"), rule.deviceIds());
        assertTrue(rule.hasRuleDetail());
    }

    @Test
    void aRuleWrittenWithoutTheMappingReportsNoneRatherThanGuessing(@TempDir Path dir) throws IOException {
        try (ManifestJournal journal = ManifestJournal.open(dir, "run-h", API)) {
            journal.recordCreated(ResourceKind.RULE, "r-single-0001", "rule1", "rule-one");
        }

        ResourceRef rule = ManifestJournal.readState(dir, "run-h")
                .find(ResourceKind.RULE, "r-single-0001").orElseThrow();

        assertNull(rule.scenario());
        assertEquals(List.of(), rule.deviceIds());
        assertFalse(rule.hasRuleDetail());
    }

    @Test
    void readStateOnAnAbsentJournalIsEmptyAndCreatesNothing(@TempDir Path dir) throws IOException {
        ManifestState state = ManifestJournal.readState(dir, "never-provisioned");

        assertTrue(state.isEmpty());
        // The export path must never bring a journal into existence just by looking at it.
        assertFalse(Files.exists(dir.resolve("never-provisioned.jsonl")));
    }
}

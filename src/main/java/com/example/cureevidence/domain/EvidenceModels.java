package com.example.cureevidence.domain;

import java.time.OffsetDateTime;
import java.util.List;

public final class EvidenceModels {
    private EvidenceModels() {}

    public record EvidenceRange(OffsetDateTime start, OffsetDateTime end, String label) {}

    public record EvidenceSegment(String type, OffsetDateTime start, OffsetDateTime end,
                                  long durationSeconds, String label) {}

    public record RuleOutcome(String ruleId, String label, String status, OffsetDateTime evidenceStart,
                              OffsetDateTime evidenceEnd, String detail,
                              List<EvidenceRange> failureRanges, List<EvidenceSegment> segments) {}

    public record SensorEntry(String sensorId, String location, OffsetDateTime firstInBandAt) {}

    public record EvaluationResult(String runId, String specVersion, String inputSummary,
                                   OffsetDateTime evaluatedAt, String overallStatus,
                                   List<RuleOutcome> rules, List<SensorEntry> sensorEntries) {}

    public record DeviationInput(String id, String ruleId, String title, String description,
                                 String status, OffsetDateTime createdAt) {}
}

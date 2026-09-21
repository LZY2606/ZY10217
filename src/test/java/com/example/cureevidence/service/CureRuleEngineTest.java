package com.example.cureevidence.service;

import com.example.cureevidence.domain.EvidenceModels.EvaluationResult;
import com.example.cureevidence.domain.EvidenceModels.RuleOutcome;
import com.example.cureevidence.domain.FixtureDocument;
import com.example.cureevidence.service.CureRuleEngine.ControlPointInput;
import com.example.cureevidence.service.CureRuleEngine.EngineInput;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CureRuleEngineTest {
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-22T10:30:00+09:00");
    private final CureRuleEngine engine = new CureRuleEngine();

    @Test
    void fixedFixtureStartsOnlyWhenAllRequiredSensorsAreSimultaneouslyInBand() throws Exception {
        EvaluationResult result = engine.evaluate(fixtureInput("/fixtures/fixed-fixture.json"));
        RuleOutcome soak = rule(result, "SOAK_COMMON_HOLD");

        assertThat(result.overallStatus()).isEqualTo("NONCONFORMING");
        assertThat(soak.status()).isEqualTo("FAIL");
        assertThat(soak.segments()).extracting(segment -> segment.start().toString())
                .contains("2026-09-22T10:35+09:00", "2026-09-22T11:00+09:00");
        assertThat(soak.segments()).extracting(segment -> Math.toIntExact(segment.durationSeconds()))
                .contains(300, 1500).doesNotContain(1800);
        assertThat(soak.failureRanges())
                .anySatisfy(range -> {
                    assertThat(range.label()).contains("不得相加");
                })
                .anySatisfy(range -> {
                    assertThat(range.start()).isEqualTo(T0.plusMinutes(5));
                    assertThat(range.end()).isEqualTo(T0.plusMinutes(10));
                    assertThat(range.label()).contains("不足 1800s");
                })
                .anySatisfy(range -> {
                    assertThat(range.start()).isEqualTo(T0.plusMinutes(30));
                    assertThat(range.end()).isEqualTo(T0.plusMinutes(55));
                    assertThat(range.label()).contains("不足 1800s");
                });
        assertThat(rule(result, "EVIDENCE_CHAIN").segments()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void inclusiveLowerAndUpperLimitsQualifyAndUninterruptedSegmentPasses() {
        FixtureDocument.TemperatureSample lower = sample("A", 0, 175.0d, "OK");
        FixtureDocument.TemperatureSample upper = sample("B", 30, 185.0d, "OK");
        EngineInput input = syntheticInput(List.of(
                List.of(lower, sample("A", 10, 180.0d, "OK"), sample("A", 20, 180.0d, "OK"),
                        sample("A", 30, 185.0d, "OK")),
                List.of(sample("B", 0, 175.0d, "OK"), sample("B", 10, 180.0d, "OK"), sample("B", 20, 180.0d, "OK"),
                        upper),
                List.of(sample("C", 0, 175.0d, "OK"), sample("C", 10, 180.0d, "OK"), sample("C", 20, 180.0d, "OK"),
                        sample("C", 30, 185.0d, "OK"))), List.of(), List.of(sensor("A"), sensor("B"), sensor("C")));

        RuleOutcome soak = rule(engine.evaluate(input), "SOAK_COMMON_HOLD");

        assertThat(soak.status()).isEqualTo("PASS");
        assertThat(soak.segments()).hasSize(1);
        assertThat(soak.segments().get(0).durationSeconds()).isEqualTo(1800L);
    }

    @Test
    void clockRollbackAndReplacementSplitEvidenceAndDoNotSumSegments() {
        List<FixtureDocument.TemperatureSample> a = List.of(sample("A", 0, 178), sample("A", 10, 180),
                sample("A", 20, 181), sample("A", 30, 182), sample("A", 40, 181), sample("A", 50, 180));
        List<FixtureDocument.TemperatureSample> b = List.of(sample("B", 0, 178), sample("B", 10, 180),
                sample("B", 20, 181), sample("B", 19, 180, "CLOCK_ROLLBACK"),
                sample("B", 30, 182), sample("B", 40, 181), sample("B", 50, 180));
        List<FixtureDocument.TemperatureSample> c1 = List.of(sample("C", 0, 178), sample("C", 10, 180),
                sample("C", 20, 181));
        List<FixtureDocument.TemperatureSample> c2 = List.of(sample("CR", 40, 181), sample("CR", 50, 180));
        FixtureDocument.Sensor replacement = new FixtureDocument.Sensor("CR", "CR", "C point",
                "REQUIRED_REPLACEMENT", true, true, "C");
        FixtureDocument.SensorEvent event = new FixtureDocument.SensorEvent("EV-R", "SENSOR_REPLACEMENT",
                "CR", T0.plusMinutes(35), "replace", true);
        EngineInput input = syntheticInput(List.of(a, b, c1, c2), List.of(event),
                List.of(sensor("A"), sensor("B"),
                        new FixtureDocument.Sensor("C", "C", "C point", "REQUIRED", true, false, null), replacement));

        RuleOutcome soak = rule(engine.evaluate(input), "SOAK_COMMON_HOLD");

        assertThat(soak.status()).isEqualTo("FAIL");
        assertThat(soak.segments()).hasSize(2);
        assertThat(soak.segments()).allSatisfy(segment -> assertThat(segment.durationSeconds()).isLessThan(1800L));
        assertThat(soak.failureRanges()).anySatisfy(range -> assertThat(range.label()).contains("不得相加"));
    }

    private EngineInput fixtureInput(String path) throws Exception {
        FixtureDocument fixture;
        try (var stream = getClass().getResourceAsStream(path)) {
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            fixture = FixtureDocument.parse(raw, "sha", "");
        }
        List<ControlPointInput> points = fixture.controlPoints().stream()
                .map(point -> new ControlPointInput(point.id(), point.code(), point.label(),
                        point.required(), point.selected())).toList();
        return new EngineInput(fixture.run().id(), fixture.run().cureSpecVersion(), fixture.run().inputSummary(),
                fixture.cureStages().get(0).startAt(), fixture.cureStages().get(fixture.cureStages().size() - 1).endAt(),
                points, fixture.materialBatches(), fixture.designPlies(), fixture.actualPlies(),
                fixture.cureStages(), fixture.sensors(), fixture.temperatureSamples(),
                fixture.sensorEvents(), fixture.processSamples());
    }

    private EngineInput syntheticInput(List<List<FixtureDocument.TemperatureSample>> groups,
                                       List<FixtureDocument.SensorEvent> events,
                                       List<FixtureDocument.Sensor> sensors) {
        return new EngineInput("RUN-T", "SPEC-TEST", "synthetic", T0, T0.plusMinutes(60),
                List.of(new ControlPointInput("CP-A", "A", "A", true, true)),
                List.of(new FixtureDocument.MaterialBatch("MB", "M", "B", 1, 10, true)),
                List.of(), List.of(),
                List.of(new FixtureDocument.CureStage("S", "SOAK", "保温", 1, T0, T0.plusMinutes(60),
                        175, 185, 1800, 80, 580)),
                sensors,
                groups.stream().flatMap(List::stream).toList(), events,
                List.of(new FixtureDocument.ProcessSample(T0, 85, 600, 1)));
    }

    private FixtureDocument.TemperatureSample sample(String sensor, int minute, double temp) {
        return sample(sensor, minute, temp, "OK");
    }

    private FixtureDocument.TemperatureSample sample(String sensor, int minute, double temp, String quality) {
        return new FixtureDocument.TemperatureSample(sensor, T0.plusMinutes(minute), temp, quality, minute + 1);
    }

    private FixtureDocument.Sensor sensor(String id) {
        return new FixtureDocument.Sensor(id, id, id + " point", "REQUIRED", true, true, null);
    }

    private RuleOutcome rule(EvaluationResult result, String ruleId) {
        return result.rules().stream().filter(rule -> rule.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }
}

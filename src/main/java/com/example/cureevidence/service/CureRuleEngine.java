package com.example.cureevidence.service;

import com.example.cureevidence.domain.EvidenceModels.EvidenceRange;
import com.example.cureevidence.domain.EvidenceModels.EvidenceSegment;
import com.example.cureevidence.domain.EvidenceModels.EvaluationResult;
import com.example.cureevidence.domain.EvidenceModels.RuleOutcome;
import com.example.cureevidence.domain.EvidenceModels.SensorEntry;
import com.example.cureevidence.domain.FixtureDocument.ActualPly;
import com.example.cureevidence.domain.FixtureDocument.CureStage;
import com.example.cureevidence.domain.FixtureDocument.DesignPly;
import com.example.cureevidence.domain.FixtureDocument.MaterialBatch;
import com.example.cureevidence.domain.FixtureDocument.ProcessSample;
import com.example.cureevidence.domain.FixtureDocument.Sensor;
import com.example.cureevidence.domain.FixtureDocument.SensorEvent;
import com.example.cureevidence.domain.FixtureDocument.TemperatureSample;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CureRuleEngine {
    private static final Set<String> BREAK_EVENTS = Set.of("SENSOR_REPLACEMENT");

    public EvaluationResult evaluate(EngineInput input) {
        List<RuleOutcome> rules = new ArrayList<>();
        rules.add(evaluateLayup(input));
        rules.add(evaluateMaterial(input));
        rules.add(evaluateControlPoint(input));
        rules.add(evaluateSoak(input));
        rules.add(evaluateProcess("VACUUM_SOAK", "保温真空", "VACUUM_BUILD", input));
        rules.add(evaluateProcess("PRESSURE_SOAK", "保温压力", "PRESSURE", input));
        rules.add(evaluateEvidenceChain(input));

        boolean failed = rules.stream().anyMatch(rule -> "FAIL".equals(rule.status()));
        String overall = failed ? "NONCONFORMING" : "CONFORMING";
        return new EvaluationResult(input.runId(), input.specVersion(), input.inputSummary(),
                OffsetDateTime.now(), overall, rules, firstEntries(input));
    }

    private RuleOutcome evaluateLayup(EngineInput input) {
        List<EvidenceSegment> segments = new ArrayList<>();
        List<EvidenceRange> failures = new ArrayList<>();
        Map<Integer, ActualPly> actual = input.actualPlies().stream()
                .collect(Collectors.toMap(ActualPly::sequenceNo, ply -> ply, (a, b) -> a, LinkedHashMap::new));
        Map<String, String> materialByBatch = input.materialBatches().stream()
                .collect(Collectors.toMap(MaterialBatch::id, MaterialBatch::materialCode));
        for (DesignPly design : input.designPlies()) {
            ActualPly laid = actual.get(design.sequenceNo());
            OffsetDateTime start = laid == null ? null : laid.laidAt();
            OffsetDateTime end = laid == null ? null : laid.laidAt();
            boolean ok = laid != null && laid.laidAngle() == design.designAngle()
                    && Objects.equals(materialByBatch.get(laid.materialBatchId()), design.materialCode());
            String label = "第 " + design.sequenceNo() + " 层 " + design.designAngle() + "°";
            if (ok) {
                segments.add(new EvidenceSegment("PLY_MATCH", start, end, 0, label));
            } else {
                failures.add(new EvidenceRange(start, end, label + " 铺放方向或材料链不一致"));
            }
        }
        return outcome("LAYUP_MATCH", "设计铺层与实际铺放逐项关联", failures, segments,
                "每层均关联设计层、材料批、操作者和铺放时刻。");
    }

    private RuleOutcome evaluateMaterial(EngineInput input) {
        List<EvidenceSegment> segments = new ArrayList<>();
        List<EvidenceRange> failures = new ArrayList<>();
        for (MaterialBatch batch : input.materialBatches()) {
            if (batch.certificateValid() && batch.outTimeMinutes() <= batch.outTimeLimitMinutes()) {
                segments.add(new EvidenceSegment("MATERIAL_BATCH", input.runStart(), input.runEnd(),
                        batch.outTimeLimitMinutes() - batch.outTimeMinutes(),
                        batch.batchNo() + " 证书有效，剩余暴露时间 "
                                + (batch.outTimeLimitMinutes() - batch.outTimeMinutes()) + " 分钟"));
            } else {
                failures.add(new EvidenceRange(input.runStart(), input.runEnd(),
                        batch.batchNo() + " 材料证书或暴露时间不合格"));
            }
        }
        return outcome("MATERIAL_TRACE", "材料批次与暴露时间", failures, segments,
                "材料批号可从设计材料、实际铺层追溯到证书与暴露时间。");
    }

    private RuleOutcome evaluateControlPoint(EngineInput input) {
        List<EvidenceSegment> segments = new ArrayList<>();
        List<EvidenceRange> failures = new ArrayList<>();
        boolean selected = input.controlPoints().stream().anyMatch(point -> point.required() && point.selected());
        String text = selected ? "已选择必需部件控制点。" : "尚未选择必需部件控制点。";
        if (selected) {
            segments.add(new EvidenceSegment("CONTROL_POINT", input.runStart(), input.runEnd(), 0, text));
        } else {
            failures.add(new EvidenceRange(input.runStart(), input.runEnd(), text));
        }
        return outcome("CONTROL_POINT", "部件控制点选择", failures, segments, text);
    }

    private RuleOutcome evaluateSoak(EngineInput input) {
        CureStage soak = input.cureStages().stream()
                .filter(stage -> "SOAK".equals(stage.code()))
                .findFirst().orElseThrow();
        Map<String, List<TemperatureSample>> bySensor = input.temperatureSamples().stream()
                .collect(Collectors.groupingBy(TemperatureSample::sensorId, LinkedHashMap::new, Collectors.toList()));
        List<OffsetDateTime> breaks = chainBreaks(bySensor, input.sensorEvents());
        List<CommonFrame> frames = commonFrames(bySensor, input.sensors(), input.sensorEvents(),
                soak.startAt(), soak.endAt(), soak.minC(), soak.maxC());

        List<EvidenceSegment> segments = new ArrayList<>();
        List<EvidenceRange> failures = new ArrayList<>();
        CommonFrame runStart = null;
        CommonFrame lastInBand = null;
        for (CommonFrame frame : frames) {
            if (!frame.inBand()) {
                closeQualifiedRun(runStart, lastInBand == null ? null : lastInBand.time(),
                        soak.requiredHoldSeconds(), segments, failures);
                runStart = null;
                lastInBand = null;
                continue;
            }
            if (runStart == null) {
                runStart = frame;
            }
            lastInBand = frame;
            if (chainBreakBetween(frames, breaks, frame)) {
                closeQualifiedRun(runStart, frame.time(), soak.requiredHoldSeconds(), segments, failures);
                runStart = null;
                lastInBand = null;
            }
        }
        closeQualifiedRun(runStart, lastInBand == null ? null : lastInBand.time(),
                soak.requiredHoldSeconds(), segments, failures);

        if (segments.stream().noneMatch(segment -> segment.durationSeconds() >= soak.requiredHoldSeconds())) {
            OffsetDateTime firstCommon = frames.stream().filter(CommonFrame::inBand)
                    .map(CommonFrame::time).findFirst().orElse(soak.startAt());
            failures.add(0, new EvidenceRange(soak.startAt(), firstCommon,
                    "必需热电偶尚未同时进入 175-185°C 闭区间"));
            failures.add(new EvidenceRange(soak.startAt(), soak.endAt(),
                    "不存在满足 " + soak.requiredHoldSeconds() + " 秒的单一共同连续保温段；各传感器时间不得相加"));
        }
        RuleOutcome rule = outcome("SOAK_COMMON_HOLD", "全部必需热电偶共同连续保温", failures, segments,
                "保温仅在所有当前必需传感器同时处于闭区间时累计；离开、时钟回退、重复样本和替换均切断证据。");
        return new RuleOutcome(rule.ruleId(), rule.label(), rule.status(), soak.startAt(), soak.endAt(),
                rule.detail(), rule.failureRanges(), rule.segments());
    }

    private RuleOutcome evaluateProcess(String ruleId, String label, String kind, EngineInput input) {
        CureStage soak = input.cureStages().stream().filter(stage -> "SOAK".equals(stage.code())).findFirst().orElseThrow();
        List<EvidenceSegment> segments = new ArrayList<>();
        List<EvidenceRange> failures = new ArrayList<>();
        for (ProcessSample sample : input.processSamples()) {
            if (sample.sampleAt().isBefore(soak.startAt()) || sample.sampleAt().isAfter(soak.endAt())) {
                continue;
            }
            int actual = "VACUUM_BUILD".equals(kind) ? sample.vacuumKpa() : sample.pressureKpa();
            int limit = "VACUUM_BUILD".equals(kind) ? soak.minVacuumKpa() : soak.minPressureKpa();
            String prefix = "VACUUM_BUILD".equals(kind) ? "真空 " : "压力 ";
            if (actual >= limit) {
                segments.add(new EvidenceSegment("PROCESS_SAMPLE", sample.sampleAt(), sample.sampleAt(), 0,
                        prefix + actual + " kPa ≥ " + limit + " kPa"));
            } else {
                failures.add(new EvidenceRange(sample.sampleAt(), sample.sampleAt(),
                        prefix + actual + " kPa < " + limit + " kPa"));
            }
        }
        return outcome(ruleId, label, failures, segments,
                "真空和压力记录按保温阶段逐样本与规范阈值关联。");
    }

    private RuleOutcome evaluateEvidenceChain(EngineInput input) {
        Map<String, List<TemperatureSample>> bySensor = input.temperatureSamples().stream()
                .collect(Collectors.groupingBy(TemperatureSample::sensorId, LinkedHashMap::new, Collectors.toList()));
        List<OffsetDateTime> breaks = chainBreaks(bySensor, input.sensorEvents());
        List<EvidenceSegment> segments = breaks.stream()
                .map(point -> new EvidenceSegment("CHAIN_BREAK", point, point, 0,
                        "证据链边界：" + point + "（时钟回退、重复样本或传感器替换）"))
                .toList();
        List<EvidenceRange> failures = new ArrayList<>();
        input.sensorEvents().stream()
                .filter(event -> ("SUSPECTED_SENSOR_ANOMALY".equals(event.eventType())
                        || "SENSOR_REPLACEMENT".equals(event.eventType())) && !event.confirmed())
                .forEach(event -> failures.add(new EvidenceRange(event.eventAt(), event.eventAt(),
                        "待确认：" + event.detail())));
        String detail = "时钟回退、重复样本和传感器替换只产生分段边界，不拼接成连续保温；传感器异常需人工确认。";
        return outcome("EVIDENCE_CHAIN", "证据链时钟、重复样本与传感器替换", failures, segments, detail);
    }

    private void closeQualifiedRun(CommonFrame start, OffsetDateTime end, long requiredSeconds,
                                   List<EvidenceSegment> segments, List<EvidenceRange> failures) {
        if (start == null) {
            return;
        }
        long seconds = Duration.between(start.time(), end).getSeconds();
        if (seconds <= 0) {
            return;
        }
        String label = "全部当前必需传感器共同位于允许带：" + seconds + "s";
        if (seconds >= requiredSeconds) {
            segments.add(new EvidenceSegment("QUALIFIED_SOAK", start.time(), end, seconds, label));
        } else {
            segments.add(new EvidenceSegment("INSUFFICIENT_SOAK_SEGMENT", start.time(), end, seconds,
                    label + "；证据段已分割且不得相加"));
            failures.add(new EvidenceRange(start.time(), end,
                    label + "，不足 " + requiredSeconds + "s；替换/中断后的片段不得与前片相加"));
        }
    }

    private List<OffsetDateTime> chainBreaks(Map<String, List<TemperatureSample>> bySensor,
                                             List<SensorEvent> events) {
        List<OffsetDateTime> breaks = new ArrayList<>();
        for (List<TemperatureSample> samples : bySensor.values()) {
            java.util.Set<OffsetDateTime> seen = new java.util.HashSet<>();
            if (!samples.isEmpty()) {
                seen.add(samples.get(0).sampleAt());
            }
            for (int i = 1; i < samples.size(); i++) {
                TemperatureSample previous = samples.get(i - 1);
                TemperatureSample current = samples.get(i);
                if (current.sampleAt().isBefore(previous.sampleAt())) {
                    breaks.add(current.sampleAt());
                }
                if (!seen.add(current.sampleAt())) {
                    breaks.add(current.sampleAt());
                }
            }
        }
        events.stream().filter(event -> BREAK_EVENTS.contains(event.eventType()))
                .map(SensorEvent::eventAt).forEach(breaks::add);
        return breaks.stream().distinct().sorted().toList();
    }

    private List<CommonFrame> commonFrames(Map<String, List<TemperatureSample>> bySensor, List<Sensor> sensors,
                                           List<SensorEvent> events, OffsetDateTime stageStart,
                                           OffsetDateTime stageEnd, double minC, double maxC) {
        List<SensorSlot> slots = sensorSlots(sensors, events);
        Map<OffsetDateTime, Map<String, TemperatureSample>> atTime = new LinkedHashMap<>();
        for (SensorSlot slot : slots) {
            for (TemperatureSample sample : bySensor.getOrDefault(slot.sensorId, List.of())) {
                if (sample.sampleAt().isBefore(stageStart) || sample.sampleAt().isAfter(stageEnd)) {
                    continue;
                }
                if (!slot.activeAt(sample.sampleAt())) {
                    continue;
                }
                atTime.computeIfAbsent(sample.sampleAt(), ignored -> new HashMap<>()).put(slot.slotId, sample);
            }
        }
        List<CommonFrame> frames = new ArrayList<>();
        for (OffsetDateTime time : atTime.keySet().stream().sorted().toList()) {
            Map<String, TemperatureSample> values = atTime.get(time);
            long activeSlots = slots.stream().filter(slot -> slot.activeAt(time)).count();
            if (values.size() != activeSlots) {
                continue;
            }
            boolean inBand = values.values().stream().allMatch(sample ->
                    sample.temperatureC() >= minC && sample.temperatureC() <= maxC);
            frames.add(new CommonFrame(time, inBand, values));
        }
        return frames;
    }

    private boolean chainBreakBetween(List<CommonFrame> frames, List<OffsetDateTime> breaks, CommonFrame current) {
        int index = frames.indexOf(current);
        if (index < 0 || index + 1 >= frames.size()) {
            return false;
        }
        OffsetDateTime next = frames.get(index + 1).time();
        return breaks.stream().anyMatch(point -> !point.isBefore(current.time()) && point.isBefore(next));
    }

    private List<SensorSlot> sensorSlots(List<Sensor> sensors, List<SensorEvent> events) {
        Map<String, Sensor> byId = sensors.stream().collect(Collectors.toMap(Sensor::id, sensor -> sensor));
        List<SensorSlot> slots = new ArrayList<>();
        for (Sensor sensor : sensors) {
            if (!sensor.required()) {
                continue;
            }
            if (sensor.replacesSensorId() != null) {
                Sensor original = byId.get(sensor.replacesSensorId());
                OffsetDateTime replacementAt = events.stream()
                        .filter(event -> "SENSOR_REPLACEMENT".equals(event.eventType()))
                        .filter(event -> sensor.id().equals(event.sensorId()))
                        .map(SensorEvent::eventAt).findFirst().orElse(sensor.active() ? OffsetDateTime.MIN : OffsetDateTime.MAX);
                slots.add(new SensorSlot(original.channel(), original.id(), null, replacementAt));
                slots.add(new SensorSlot(original.channel(), sensor.id(), replacementAt, null));
            } else {
                boolean hasReplacement = sensors.stream().anyMatch(candidate -> sensor.id().equals(candidate.replacesSensorId()));
                if (!hasReplacement) {
                    slots.add(new SensorSlot(sensor.channel(), sensor.id(), null, null));
                }
            }
        }
        slots.sort(Comparator.comparing(SensorSlot::slotId));
        return slots;
    }

    private List<SensorEntry> firstEntries(EngineInput input) {
        CureStage soak = input.cureStages().stream().filter(stage -> "SOAK".equals(stage.code())).findFirst().orElseThrow();
        List<SensorEntry> entries = new ArrayList<>();
        for (Sensor sensor : input.sensors()) {
            if (!sensor.required() || sensor.replacesSensorId() != null) {
                continue;
            }
            OffsetDateTime first = input.temperatureSamples().stream()
                    .filter(sample -> sample.sensorId().equals(sensor.id()))
                    .filter(sample -> !sample.sampleAt().isBefore(soak.startAt()))
                    .filter(sample -> sample.temperatureC() >= soak.minC()
                            && sample.temperatureC() <= soak.maxC())
                    .map(TemperatureSample::sampleAt).findFirst().orElse(null);
            entries.add(new SensorEntry(sensor.id(), sensor.location(), first));
        }
        return entries;
    }

    private RuleOutcome outcome(String ruleId, String label, List<EvidenceRange> failures,
                                List<EvidenceSegment> segments, String detail) {
        return new RuleOutcome(ruleId, label, failures.isEmpty() ? "PASS" : "FAIL",
                segments.isEmpty() ? null : segments.get(0).start(),
                segments.isEmpty() ? null : segments.get(segments.size() - 1).end(),
                detail, failures, segments);
    }

    private record CommonFrame(OffsetDateTime time, boolean inBand, Map<String, TemperatureSample> values) {}

    private record SensorSlot(String slotId, String sensorId, OffsetDateTime startAt, OffsetDateTime endAt) {
        boolean activeAt(OffsetDateTime time) {
            return (startAt == null || !time.isBefore(startAt)) && (endAt == null || time.isBefore(endAt));
        }
    }

    public record ControlPointInput(String id, String code, String label, boolean required, boolean selected) {}

    public record EngineInput(String runId, String specVersion, String inputSummary, OffsetDateTime runStart,
                              OffsetDateTime runEnd, List<ControlPointInput> controlPoints,
                              List<MaterialBatch> materialBatches, List<DesignPly> designPlies,
                              List<ActualPly> actualPlies, List<CureStage> cureStages,
                              List<Sensor> sensors, List<TemperatureSample> temperatureSamples,
                              List<SensorEvent> sensorEvents, List<ProcessSample> processSamples) {}
}

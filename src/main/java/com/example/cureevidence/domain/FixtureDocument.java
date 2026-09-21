package com.example.cureevidence.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public record FixtureDocument(
        String rawJson,
        String sha256,
        String version,
        Run run,
        List<ControlPoint> controlPoints,
        List<MaterialBatch> materialBatches,
        List<DesignPly> designPlies,
        List<ActualPly> actualPlies,
        List<CureStage> cureStages,
        List<Sensor> sensors,
        List<TemperatureSample> temperatureSamples,
        List<SensorEvent> sensorEvents,
        List<ProcessSample> processSamples
) {
    public record Run(String id, String partName, String cureSpecVersion, String inputSummary) {}
    public record ControlPoint(String id, String code, String label, boolean required, boolean selected) {}
    public record MaterialBatch(String id, String materialCode, String batchNo, int outTimeMinutes,
                                int outTimeLimitMinutes, boolean certificateValid) {}
    public record DesignPly(String id, int sequenceNo, String materialCode, int designAngle, int nominalWeightGpm2) {}
    public record ActualPly(String id, String designPlyId, int sequenceNo, String materialBatchId,
                            int laidAngle, String operatorId, OffsetDateTime laidAt) {}
    public record CureStage(String id, String code, String label, int sequenceNo, OffsetDateTime startAt,
                            OffsetDateTime endAt, Integer minC, Integer maxC, Integer requiredHoldSeconds,
                            Integer minVacuumKpa, Integer minPressureKpa) {}
    public record Sensor(String id, String channel, String location, String role, boolean required,
                         boolean active, String replacesSensorId) {}
    public record TemperatureSample(String sensorId, OffsetDateTime sampleAt, double temperatureC,
                                    String qualityFlag, int sequenceNo) {}
    public record SensorEvent(String id, String eventType, String sensorId, OffsetDateTime eventAt,
                              String detail, boolean confirmed) {}
    public record ProcessSample(OffsetDateTime sampleAt, int vacuumKpa, int pressureKpa, int sequenceNo) {}

    public static FixtureDocument parse(String rawJson, String sha256, String fixtureVersion) {
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawJson);
            JsonNode runNode = root.path("run");
            Run run = new Run(text(runNode, "id"), text(runNode, "partName"),
                    text(runNode, "cureSpecVersion"), text(runNode, "inputSummary"));

            List<ControlPoint> controlPoints = new ArrayList<>();
            root.path("controlPoints").forEach(node -> controlPoints.add(new ControlPoint(
                    text(node, "id"), text(node, "code"), text(node, "label"),
                    node.path("required").asBoolean(), node.path("selected").asBoolean())));

            List<MaterialBatch> batches = new ArrayList<>();
            root.path("materialBatches").forEach(node -> batches.add(new MaterialBatch(
                    text(node, "id"), text(node, "materialCode"), text(node, "batchNo"),
                    node.path("outTimeMinutes").asInt(), node.path("outTimeLimitMinutes").asInt(),
                    node.path("certificateValid").asBoolean())));

            List<DesignPly> designPlies = new ArrayList<>();
            root.path("designPlies").forEach(node -> designPlies.add(new DesignPly(
                    text(node, "id"), node.path("sequenceNo").asInt(), text(node, "materialCode"),
                    node.path("designAngle").asInt(), node.path("nominalWeightGpm2").asInt())));

            List<ActualPly> actualPlies = new ArrayList<>();
            root.path("actualPlies").forEach(node -> actualPlies.add(new ActualPly(
                    text(node, "id"), text(node, "designPlyId"), node.path("sequenceNo").asInt(),
                    text(node, "materialBatchId"), node.path("laidAngle").asInt(),
                    text(node, "operatorId"), time(text(node, "laidAt")))));

            List<CureStage> stages = new ArrayList<>();
            root.path("cureStages").forEach(node -> stages.add(new CureStage(
                    text(node, "id"), text(node, "code"), text(node, "label"),
                    node.path("sequenceNo").asInt(), time(text(node, "startAt")), time(text(node, "endAt")),
                    integer(node, "minC"), integer(node, "maxC"), integer(node, "requiredHoldSeconds"),
                    integer(node, "minVacuumKpa"), integer(node, "minPressureKpa"))));

            List<Sensor> sensors = new ArrayList<>();
            root.path("sensors").forEach(node -> sensors.add(new Sensor(
                    text(node, "id"), text(node, "channel"), text(node, "location"),
                    text(node, "role"), node.path("required").asBoolean(), node.path("active").asBoolean(),
                    textOrNull(node, "replacesSensorId"))));

            List<TemperatureSample> tempSamples = new ArrayList<>();
            root.path("temperatureSamples").forEach(group -> {
                String sensorId = text(group, "sensorId");
                JsonNode samples = group.path("samples");
                for (int i = 0; i < samples.size(); i++) {
                    JsonNode sample = samples.get(i);
                    tempSamples.add(new TemperatureSample(sensorId, dateTime(sample.get(0).asText()),
                            sample.get(1).asDouble(), sample.path(2).asText("OK"), i + 1));
                }
            });

            List<SensorEvent> events = new ArrayList<>();
            root.path("sensorEvents").forEach(node -> events.add(new SensorEvent(
                    text(node, "id"), text(node, "eventType"), textOrNull(node, "sensorId"),
                    time(text(node, "eventAt")), text(node, "detail"),
                    node.path("confirmed").asBoolean())));

            List<ProcessSample> processSamples = new ArrayList<>();
            JsonNode processNode = root.path("processSamples");
            for (int i = 0; i < processNode.size(); i++) {
                JsonNode sample = processNode.get(i);
                processSamples.add(new ProcessSample(dateTime(sample.get(0).asText()),
                        sample.get(1).asInt(), sample.get(2).asInt(), i + 1));
            }
            String version = root.path("version").asText("FIX-UNKNOWN");
            return new FixtureDocument(rawJson, sha256, version, run, controlPoints, batches, designPlies,
                    actualPlies, stages, sensors, tempSamples, events, processSamples);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot parse fixed fixture", ex);
        }
    }
    private static String text(JsonNode node, String name) {
        return node.path(name).asText();
    }

    private static String textOrNull(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static OffsetDateTime time(String value) {
        return OffsetDateTime.parse(value);
    }

    private static OffsetDateTime dateTime(String hhmm) {
        return OffsetDateTime.parse("2026-09-22T" + hhmm + ":00+09:00");
    }
}

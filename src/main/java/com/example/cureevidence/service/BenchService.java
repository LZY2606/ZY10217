package com.example.cureevidence.service;

import com.example.cureevidence.domain.EvidenceModels.EvaluationResult;
import com.example.cureevidence.domain.EvidenceModels.EvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BenchService {
    private final JdbcTemplate jdbcTemplate;
    private final EvaluationService evaluationService;
    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;

    public BenchService(JdbcTemplate jdbcTemplate, EvaluationService evaluationService,
                        DatabaseService databaseService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.evaluationService = evaluationService;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> bench() {
        String runId = jdbcTemplate.queryForObject("SELECT id FROM runs ORDER BY imported_at LIMIT 1", String.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run(runId));
        result.put("controlPoints", controlPoints(runId));
        result.put("plies", plies(runId));
        result.put("stages", stages(runId));
        result.put("sensors", sensors(runId));
        result.put("temperatureSamples", temperatureSamples(runId));
        result.put("sensorEvents", sensorEvents(runId));
        result.put("processSamples", processSamples(runId));
        EvaluationResult evaluation = evaluationService.currentEvaluation(runId);
        result.put("evaluation", evaluation);
        result.put("sensorEntries", evaluation.sensorEntries());
        result.put("actions", actions(runId));
        result.put("deviations", deviations(runId));
        return result;
    }

    @Transactional
    public void confirmSensorAnomaly(String eventId, String actor) {
        String runId = jdbcTemplate.queryForObject("SELECT run_id FROM sensor_events WHERE id = ?", String.class, eventId);
        jdbcTemplate.update("""
                UPDATE sensor_events SET confirmed = 1, confirmed_by = ?, confirmed_at = ? WHERE id = ?
                """, actor, OffsetDateTime.now().toString(), eventId);
        addAction(runId, "CONFIRM_SENSOR_ANOMALY", "SENSOR_EVENT", eventId,
                "确认传感器异常或替换记录，证据段仍保持分割", actor);
        evaluationService.evaluateAndPersist(runId);
    }

    @Transactional
    public void selectControlPoint(String pointId, String actor) {
        String runId = jdbcTemplate.queryForObject("SELECT run_id FROM control_points WHERE id = ?", String.class, pointId);
        jdbcTemplate.update("UPDATE control_points SET selected = CASE WHEN id = ? THEN 1 ELSE 0 END WHERE run_id = ?",
                pointId, runId);
        addAction(runId, "SELECT_CONTROL_POINT", "CONTROL_POINT", pointId, "选择部件控制点", actor);
        evaluationService.evaluateAndPersist(runId);
    }

    @Transactional
    public void createDeviation(String ruleId, String title, String description, String actor) {
        String runId = jdbcTemplate.queryForObject("SELECT id FROM runs ORDER BY imported_at LIMIT 1", String.class);
        String id = "DEV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcTemplate.update("""
                INSERT INTO deviations(id, run_id, rule_id, title, description, status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, id, runId, ruleId, title, description, actor, OffsetDateTime.now().toString());
        addAction(runId, "CREATE_DEVIATION_BRANCH", "RULE", ruleId == null ? "ALL" : ruleId,
                title + "：" + description, actor);
    }

    @Transactional
    public void reset() {
        databaseService.resetAndImportFixedFixture();
    }

    public String exportJson() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bench());
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot export run record", ex);
        }
    }

    private Map<String, Object> run(String runId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, part_name AS partName, cure_spec_version AS specVersion, fixture_version AS fixtureVersion,
                       fixture_sha256 AS fixtureSha256, imported_at AS importedAt, input_summary AS inputSummary
                FROM runs WHERE id = ?
                """, runId);
    }

    private List<Map<String, Object>> controlPoints(String runId) {
        return jdbcTemplate.query("""
                SELECT id, code, label, required, selected FROM control_points WHERE run_id = ? ORDER BY code
                """, (rs, rowNum) -> Map.of(
                        "id", rs.getString("id"), "code", rs.getString("code"), "label", rs.getString("label"),
                        "required", rs.getBoolean("required"), "selected", rs.getBoolean("selected")), runId);
    }

    private List<Map<String, Object>> plies(String runId) {
        return jdbcTemplate.queryForList("""
                SELECT d.sequence_no AS sequenceNo, d.design_angle AS designAngle, a.laid_angle AS laidAngle,
                       d.material_code AS materialCode, b.batch_no AS batchNo, a.operator_id AS operatorId,
                       a.laid_at AS laidAt
                FROM design_plies d JOIN actual_plies a ON a.design_ply_id = d.id
                LEFT JOIN material_batches b ON b.id = a.material_batch_id
                WHERE d.run_id = ? ORDER BY d.sequence_no
                """, runId);
    }

    private List<Map<String, Object>> stages(String runId) {
        return jdbcTemplate.queryForList("""
                SELECT code, label, sequence_no AS sequenceNo, start_at AS startAt, end_at AS endAt,
                       min_c AS minC, max_c AS maxC, required_hold_seconds AS requiredHoldSeconds,
                       min_vacuum_kpa AS minVacuumKpa, min_pressure_kpa AS minPressureKpa
                FROM cure_stages WHERE run_id = ? ORDER BY sequence_no
                """, runId);
    }

    private List<Map<String, Object>> sensors(String runId) {
        return jdbcTemplate.query("""
                SELECT id, channel, location, role, required, active, replaces_sensor_id AS replacesSensorId
                FROM sensors WHERE run_id = ? ORDER BY channel
                """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("channel", rs.getString("channel"));
                    row.put("location", rs.getString("location"));
                    row.put("role", rs.getString("role"));
                    row.put("required", rs.getBoolean("required"));
                    row.put("active", rs.getBoolean("active"));
                    row.put("replacesSensorId", rs.getString("replacesSensorId"));
                    return row;
                }, runId);
    }

    private List<Map<String, Object>> temperatureSamples(String runId) {
        return jdbcTemplate.queryForList("""
                SELECT sensor_id AS sensorId, sample_at AS sampleAt, temperature_c AS temperatureC,
                       quality_flag AS qualityFlag, sequence_no AS sequenceNo
                FROM temperature_samples WHERE run_id = ? ORDER BY sample_at, sensor_id, sequence_no
                """, runId);
    }

    private List<Map<String, Object>> sensorEvents(String runId) {
        return jdbcTemplate.query("""
                SELECT id, event_type AS eventType, sensor_id AS sensorId, event_at AS eventAt, detail,
                       confirmed, confirmed_by AS confirmedBy, confirmed_at AS confirmedAt
                FROM sensor_events WHERE run_id = ? ORDER BY event_at
                """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("eventType", rs.getString("eventType"));
                    row.put("sensorId", rs.getString("sensorId"));
                    row.put("eventAt", rs.getString("eventAt"));
                    row.put("detail", rs.getString("detail"));
                    row.put("confirmed", rs.getBoolean("confirmed"));
                    row.put("confirmedBy", rs.getString("confirmedBy"));
                    row.put("confirmedAt", rs.getString("confirmedAt"));
                    return row;
                }, runId);
    }

    private List<Map<String, Object>> processSamples(String runId) {
        return jdbcTemplate.queryForList("""
                SELECT sample_at AS sampleAt, vacuum_kpa AS vacuumKpa, pressure_kpa AS pressureKpa
                FROM process_samples WHERE run_id = ? ORDER BY sequence_no
                """, runId);
    }

    private List<Map<String, Object>> actions(String runId) {
        return jdbcTemplate.queryForList("""
                SELECT action_type AS actionType, target_type AS targetType, target_id AS targetId,
                       note, actor, created_at AS createdAt FROM actions WHERE run_id = ? ORDER BY id DESC
                """, runId);
    }

    private List<Map<String, Object>> deviations(String runId) {
        return jdbcTemplate.queryForList("""
                SELECT id, rule_id AS ruleId, title, description, status, created_by AS createdBy,
                       created_at AS createdAt FROM deviations WHERE run_id = ? ORDER BY created_at DESC
                """, runId);
    }

    private void addAction(String runId, String type, String targetType, String targetId, String note, String actor) {
        jdbcTemplate.update("""
                INSERT INTO actions(run_id, action_type, target_type, target_id, note, actor, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, type, targetType, targetId, note, actor, OffsetDateTime.now().toString());
    }
}

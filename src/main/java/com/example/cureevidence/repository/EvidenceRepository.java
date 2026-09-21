package com.example.cureevidence.repository;

import com.example.cureevidence.domain.FixtureDocument.ActualPly;
import com.example.cureevidence.domain.FixtureDocument.CureStage;
import com.example.cureevidence.domain.FixtureDocument.DesignPly;
import com.example.cureevidence.domain.FixtureDocument.MaterialBatch;
import com.example.cureevidence.domain.FixtureDocument.ProcessSample;
import com.example.cureevidence.domain.FixtureDocument.Sensor;
import com.example.cureevidence.domain.FixtureDocument.SensorEvent;
import com.example.cureevidence.domain.FixtureDocument.TemperatureSample;
import com.example.cureevidence.service.CureRuleEngine.ControlPointInput;
import com.example.cureevidence.service.CureRuleEngine.EngineInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class EvidenceRepository {
    private final JdbcTemplate jdbcTemplate;

    public EvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public EngineInput loadEngineInput(String runId) {
        RunRow run = jdbcTemplate.queryForObject("""
                SELECT id, cure_spec_version, input_summary FROM runs WHERE id = ?
                """, (rs, rowNum) -> new RunRow(rs.getString(1), rs.getString(2), rs.getString(3)), runId);
        OffsetDateTime runStart = OffsetDateTime.parse(jdbcTemplate.queryForObject(
                "SELECT MIN(start_at) FROM cure_stages WHERE run_id = ?", String.class, runId));
        OffsetDateTime runEnd = OffsetDateTime.parse(jdbcTemplate.queryForObject(
                "SELECT MAX(end_at) FROM cure_stages WHERE run_id = ?", String.class, runId));
        var points = jdbcTemplate.query("""
                SELECT id, code, label, required, selected FROM control_points WHERE run_id = ? ORDER BY code
                """, (rs, rowNum) -> new ControlPointInput(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getBoolean(5)), runId);
        var batches = jdbcTemplate.query("""
                SELECT id, material_code, batch_no, out_time_minutes, out_time_limit_minutes, certificate_valid
                FROM material_batches WHERE run_id = ? ORDER BY batch_no
                """, (rs, rowNum) -> new MaterialBatch(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getBoolean(6)), runId);
        var designPlies = jdbcTemplate.query("""
                SELECT id, sequence_no, material_code, design_angle, nominal_weight_gpm2
                FROM design_plies WHERE run_id = ? ORDER BY sequence_no
                """, (rs, rowNum) -> new DesignPly(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5)), runId);
        var actualPlies = jdbcTemplate.query("""
                SELECT id, design_ply_id, sequence_no, material_batch_id, laid_angle, operator_id, laid_at
                FROM actual_plies WHERE run_id = ? ORDER BY sequence_no
                """, (rs, rowNum) -> new ActualPly(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getInt(5), rs.getString(6), OffsetDateTime.parse(rs.getString(7))), runId);
        var stages = jdbcTemplate.query("""
                SELECT id, code, label, sequence_no, start_at, end_at, min_c, max_c, required_hold_seconds,
                min_vacuum_kpa, min_pressure_kpa FROM cure_stages WHERE run_id = ? ORDER BY sequence_no
                """, (rs, rowNum) -> new CureStage(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), OffsetDateTime.parse(rs.getString(5)), OffsetDateTime.parse(rs.getString(6)),
                (Integer) rs.getObject(7), (Integer) rs.getObject(8), (Integer) rs.getObject(9),
                (Integer) rs.getObject(10), (Integer) rs.getObject(11)), runId);
        var sensors = jdbcTemplate.query("""
                SELECT id, channel, location, role, required, active, replaces_sensor_id
                FROM sensors WHERE run_id = ? ORDER BY channel
                """, (rs, rowNum) -> new Sensor(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7)), runId);
        var tempSamples = jdbcTemplate.query("""
                SELECT sensor_id, sample_at, temperature_c, quality_flag, sequence_no
                FROM temperature_samples WHERE run_id = ? ORDER BY sequence_no, id
                """, (rs, rowNum) -> new TemperatureSample(rs.getString(1), OffsetDateTime.parse(rs.getString(2)),
                rs.getDouble(3), rs.getString(4), rs.getInt(5)), runId);
        var events = jdbcTemplate.query("""
                SELECT id, event_type, sensor_id, event_at, detail, confirmed
                FROM sensor_events WHERE run_id = ? ORDER BY event_at, id
                """, (rs, rowNum) -> new SensorEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                OffsetDateTime.parse(rs.getString(4)), rs.getString(5), rs.getBoolean(6)), runId);
        var processSamples = jdbcTemplate.query("""
                SELECT sample_at, vacuum_kpa, pressure_kpa, sequence_no
                FROM process_samples WHERE run_id = ? ORDER BY sequence_no
                """, (rs, rowNum) -> new ProcessSample(OffsetDateTime.parse(rs.getString(1)), rs.getInt(2),
                rs.getInt(3), rs.getInt(4)), runId);
        return new EngineInput(run.id(), run.specVersion(), run.inputSummary(), runStart, runEnd,
                points, batches, designPlies, actualPlies, stages, sensors, tempSamples, events, processSamples);
    }

    public String firstRunId() {
        return jdbcTemplate.queryForObject("SELECT id FROM runs ORDER BY imported_at LIMIT 1", String.class);
    }

    private record RunRow(String id, String specVersion, String inputSummary) {}
}

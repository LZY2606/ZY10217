package com.example.cureevidence.service;

import com.example.cureevidence.domain.FixtureDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class DatabaseService {
    private final JdbcTemplate jdbcTemplate;
    private final FixtureService fixtureService;
    private final EvaluationService evaluationService;

    public DatabaseService(JdbcTemplate jdbcTemplate, FixtureService fixtureService,
                           EvaluationService evaluationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fixtureService = fixtureService;
        this.evaluationService = evaluationService;
    }

    public boolean isEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM runs", Integer.class);
        return count == null || count == 0;
    }

    @Transactional
    public void resetAndImportFixedFixture() {
        FixtureDocument fixture = fixtureService.load();
        replaceDatabase(fixture);
        evaluationService.evaluateAndPersist(fixture.run().id());
    }

    @Transactional
    public void replaceDatabase(FixtureDocument fixture) {
        jdbcTemplate.update("DELETE FROM deviations");
        jdbcTemplate.update("DELETE FROM actions");
        jdbcTemplate.update("DELETE FROM evidence_segments");
        jdbcTemplate.update("DELETE FROM rule_results");
        jdbcTemplate.update("DELETE FROM evaluations");
        jdbcTemplate.update("DELETE FROM process_samples");
        jdbcTemplate.update("DELETE FROM sensor_events");
        jdbcTemplate.update("DELETE FROM temperature_samples");
        jdbcTemplate.update("DELETE FROM sensors");
        jdbcTemplate.update("DELETE FROM cure_stages");
        jdbcTemplate.update("DELETE FROM actual_plies");
        jdbcTemplate.update("DELETE FROM design_plies");
        jdbcTemplate.update("DELETE FROM material_batches");
        jdbcTemplate.update("DELETE FROM control_points");
        jdbcTemplate.update("DELETE FROM runs");

        FixtureDocument.Run run = fixture.run();
        jdbcTemplate.update("""
                INSERT INTO runs(id, part_name, cure_spec_version, fixture_version, fixture_sha256, imported_at, input_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.partName(), run.cureSpecVersion(), fixture.version(), fixture.sha256(),
                OffsetDateTime.now().toString(), run.inputSummary());

        for (FixtureDocument.ControlPoint point : fixture.controlPoints()) {
            jdbcTemplate.update("""
                    INSERT INTO control_points(id, run_id, code, label, required, selected)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, point.id(), run.id(), point.code(), point.label(), point.required(), point.selected());
        }
        for (FixtureDocument.MaterialBatch batch : fixture.materialBatches()) {
            jdbcTemplate.update("""
                    INSERT INTO material_batches(id, run_id, material_code, batch_no, out_time_minutes,
                    out_time_limit_minutes, certificate_valid) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, batch.id(), run.id(), batch.materialCode(), batch.batchNo(), batch.outTimeMinutes(),
                    batch.outTimeLimitMinutes(), batch.certificateValid());
        }
        for (FixtureDocument.DesignPly ply : fixture.designPlies()) {
            jdbcTemplate.update("""
                    INSERT INTO design_plies(id, run_id, sequence_no, material_code, design_angle, nominal_weight_gpm2)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, ply.id(), run.id(), ply.sequenceNo(), ply.materialCode(), ply.designAngle(),
                    ply.nominalWeightGpm2());
        }
        for (FixtureDocument.ActualPly ply : fixture.actualPlies()) {
            jdbcTemplate.update("""
                    INSERT INTO actual_plies(id, run_id, design_ply_id, sequence_no, material_batch_id, laid_angle,
                    operator_id, laid_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, ply.id(), run.id(), ply.designPlyId(), ply.sequenceNo(), ply.materialBatchId(),
                    ply.laidAngle(), ply.operatorId(), ply.laidAt().toString());
        }
        for (FixtureDocument.CureStage stage : fixture.cureStages()) {
            jdbcTemplate.update("""
                    INSERT INTO cure_stages(id, run_id, code, label, sequence_no, start_at, end_at, min_c, max_c,
                    required_hold_seconds, min_vacuum_kpa, min_pressure_kpa)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, stage.id(), run.id(), stage.code(), stage.label(), stage.sequenceNo(),
                    stage.startAt().toString(), stage.endAt().toString(), stage.minC(), stage.maxC(),
                    stage.requiredHoldSeconds(), stage.minVacuumKpa(), stage.minPressureKpa());
        }
        for (FixtureDocument.Sensor sensor : fixture.sensors()) {
            jdbcTemplate.update("""
                    INSERT INTO sensors(id, run_id, channel, location, role, required, active, replaces_sensor_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, sensor.id(), run.id(), sensor.channel(), sensor.location(), sensor.role(),
                    sensor.required(), sensor.active(), sensor.replacesSensorId());
        }
        for (FixtureDocument.TemperatureSample sample : fixture.temperatureSamples()) {
            jdbcTemplate.update("""
                    INSERT INTO temperature_samples(run_id, sensor_id, sample_at, temperature_c, quality_flag, sequence_no)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, run.id(), sample.sensorId(), sample.sampleAt().toString(), sample.temperatureC(),
                    sample.qualityFlag(), sample.sequenceNo());
        }
        for (FixtureDocument.SensorEvent event : fixture.sensorEvents()) {
            jdbcTemplate.update("""
                    INSERT INTO sensor_events(id, run_id, event_type, sensor_id, event_at, detail, confirmed)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, event.id(), run.id(), event.eventType(), event.sensorId(), event.eventAt().toString(),
                    event.detail(), event.confirmed());
        }
        for (FixtureDocument.ProcessSample sample : fixture.processSamples()) {
            jdbcTemplate.update("""
                    INSERT INTO process_samples(run_id, sample_at, vacuum_kpa, pressure_kpa, sequence_no)
                    VALUES (?, ?, ?, ?, ?)
                    """, run.id(), sample.sampleAt().toString(), sample.vacuumKpa(), sample.pressureKpa(),
                    sample.sequenceNo());
        }
    }
}

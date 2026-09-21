package com.example.cureevidence.service;

import com.example.cureevidence.domain.EvidenceModels.EvidenceRange;
import com.example.cureevidence.domain.EvidenceModels.EvaluationResult;
import com.example.cureevidence.domain.EvidenceModels.RuleOutcome;
import com.example.cureevidence.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class EvaluationService {
    private final EvidenceRepository evidenceRepository;
    private final CureRuleEngine engine;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvaluationService(EvidenceRepository evidenceRepository, CureRuleEngine engine,
                             JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.engine = engine;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public EvaluationResult currentEvaluation(String runId) {
        return engine.evaluate(evidenceRepository.loadEngineInput(runId));
    }

    @Transactional
    public EvaluationResult evaluateAndPersist(String runId) {
        EvaluationResult result = currentEvaluation(runId);
        jdbcTemplate.update("DELETE FROM rule_results WHERE evaluation_id IN (SELECT id FROM evaluations WHERE run_id = ?)", runId);
        jdbcTemplate.update("DELETE FROM evaluations WHERE run_id = ?", runId);
        jdbcTemplate.update("""
                INSERT INTO evaluations(run_id, spec_version, overall_status, input_summary, evaluated_at)
                VALUES (?, ?, ?, ?, ?)
                """, result.runId(), result.specVersion(), result.overallStatus(),
                result.inputSummary(), result.evaluatedAt().toString());
        Long evaluationId = jdbcTemplate.queryForObject("SELECT id FROM evaluations WHERE run_id = ?",
                Long.class, runId);
        for (RuleOutcome rule : result.rules()) {
            jdbcTemplate.update("""
                    INSERT INTO rule_results(evaluation_id, rule_id, label, status, evidence_start, evidence_end,
                    detail, failure_ranges_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, evaluationId, rule.ruleId(), rule.label(), rule.status(),
                    rule.evidenceStart() == null ? null : rule.evidenceStart().toString(),
                    rule.evidenceEnd() == null ? null : rule.evidenceEnd().toString(),
                    rule.detail(), toJson(rule.failureRanges()));
            Long ruleResultId = jdbcTemplate.queryForObject(
                    "SELECT id FROM rule_results WHERE evaluation_id = ? AND rule_id = ?",
                    Long.class, evaluationId, rule.ruleId());
            rule.segments().forEach(segment -> jdbcTemplate.update("""
                    INSERT INTO evidence_segments(rule_result_id, segment_type, start_at, end_at,
                    duration_seconds, label) VALUES (?, ?, ?, ?, ?, ?)
                    """, ruleResultId, segment.type(), segment.start().toString(), segment.end().toString(),
                    segment.durationSeconds(), segment.label()));
        }
        return result;
    }

    public List<PersistedRule> persistedRules(String runId) {
        return jdbcTemplate.query("""
                SELECT rr.rule_id, rr.label, rr.status, rr.evidence_start, rr.evidence_end, rr.detail,
                       rr.failure_ranges_json
                FROM evaluations e JOIN rule_results rr ON rr.evaluation_id = e.id
                WHERE e.run_id = ? ORDER BY rr.id
                """, (rs, rowNum) -> new PersistedRule(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), runId);
    }

    private String toJson(List<EvidenceRange> ranges) {
        try {
            return objectMapper.writeValueAsString(ranges.stream()
                    .map(range -> Map.of("start", range.start() == null ? null : range.start().toString(),
                            "end", range.end() == null ? null : range.end().toString(),
                            "label", range.label()))
                    .toList());
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize failure ranges", ex);
        }
    }

    public record PersistedRule(String ruleId, String label, String status, String evidenceStart,
                                String evidenceEnd, String detail, String failureRangesJson) {}
}

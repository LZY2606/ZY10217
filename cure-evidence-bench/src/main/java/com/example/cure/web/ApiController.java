package com.example.cure.web;

import com.example.cure.Time;
import com.example.cure.engine.EvaluationService;
import com.example.cure.repo.DataStore;
import com.example.cure.repo.FixtureSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final List<String> TABLES = List.of(
            "spec_versions", "parts", "plies", "material_lots", "layup_records",
            "cure_cycles", "thermocouples", "tc_replacements", "tc_samples",
            "cycle_stages", "env_samples", "review_actions", "deviations",
            "evaluation_runs", "rule_results");

    private final DataStore ds;
    private final FixtureSeeder seeder;
    private final EvaluationService evaluator;
    private final ObjectMapper om = new ObjectMapper();

    public ApiController(DataStore ds, FixtureSeeder seeder, EvaluationService evaluator) {
        this.ds = ds;
        this.seeder = seeder;
        this.evaluator = evaluator;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        EvaluationService.Evaluation evaluation = evaluator.evaluate(true);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("spec", ds.queryOne("SELECT * FROM spec_versions WHERE version=?",
                FixtureSeeder.SPEC));
        root.put("part", ds.queryOne("SELECT * FROM parts WHERE id=?", FixtureSeeder.PART_ID));
        root.put("plies", ds.query(
                "SELECT p.*, l.lot_code, l.laid_ts, l.operator, l.layup_serial "
                        + "FROM plies p LEFT JOIN layup_records l "
                        + "ON p.part_id=l.part_id AND p.seq=l.seq "
                        + "WHERE p.part_id=? ORDER BY p.seq", FixtureSeeder.PART_ID));
        root.put("lot", ds.queryOne("SELECT * FROM material_lots WHERE lot_code=?",
                FixtureSeeder.LOT));
        root.put("cycle", ds.queryOne("SELECT * FROM cure_cycles WHERE id=?", FixtureSeeder.CYCLE_ID));
        root.put("stages", ds.query(
                "SELECT * FROM cycle_stages WHERE cycle_id=? ORDER BY stage_order",
                FixtureSeeder.CYCLE_ID));
        root.put("thermocouples", ds.query(
                "SELECT * FROM thermocouples WHERE cycle_id=? ORDER BY id",
                FixtureSeeder.CYCLE_ID));
        root.put("replacements", ds.query(
                "SELECT * FROM tc_replacements WHERE cycle_id=? ORDER BY ts",
                FixtureSeeder.CYCLE_ID));
        root.put("tcSamples", ds.query(
                "SELECT tc_id,ord,ts,temp FROM tc_samples WHERE cycle_id=? ORDER BY tc_id,ord",
                FixtureSeeder.CYCLE_ID));
        root.put("envSamples", ds.query(
                "SELECT kind,ord,ts,value FROM env_samples WHERE cycle_id=? ORDER BY kind,ord",
                FixtureSeeder.CYCLE_ID));
        root.put("actions", ds.query(
                "SELECT * FROM review_actions WHERE cycle_id=? ORDER BY id",
                FixtureSeeder.CYCLE_ID));
        root.put("deviations", ds.query(
                "SELECT * FROM deviations WHERE cycle_id=? ORDER BY id",
                FixtureSeeder.CYCLE_ID));
        root.put("runs", ds.query(
                "SELECT id,created_ts,overall,spec_version,input_hash,longest_joint_sec,"
                        + "joint_total_sec FROM evaluation_runs WHERE cycle_id=? ORDER BY id DESC LIMIT 20",
                FixtureSeeder.CYCLE_ID));
        root.put("latestRuleResults", latestRuleResults());
        root.put("evaluation", evaluation);
        root.put("serverTime", Time.mdhm(System.currentTimeMillis() / 1000));
        return root;
    }

    private List<Map<String, Object>> latestRuleResults() {
        Map<String, Object> run = ds.queryOne(
                "SELECT id FROM evaluation_runs WHERE cycle_id=? ORDER BY id DESC LIMIT 1",
                FixtureSeeder.CYCLE_ID);
        if (run == null) {
            return List.of();
        }
        long runId = ((Number) run.get("id")).longValue();
        return ds.query("SELECT * FROM rule_results WHERE run_id=? ORDER BY id", runId);
    }

    @PostMapping("/actions/confirm-anomaly")
    public Map<String, Object> confirmAnomaly(@RequestBody Map<String, Object> body) throws Exception {
        long start = ((Number) body.get("startTs")).longValue();
        long end = ((Number) body.get("endTs")).longValue();
        String tcId = String.valueOf(body.getOrDefault("tcId", ""));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startTs", start);
        payload.put("endTs", end);
        payload.put("tcId", tcId);
        payload.put("note", String.valueOf(body.getOrDefault("note", "传感器异常")));
        ds.update("INSERT INTO review_actions(cycle_id,type,target,payload,actor,created_ts)"
                        + " VALUES(?,?,?,?,?,?)",
                FixtureSeeder.CYCLE_ID, "CONFIRM_ANOMALY", tcId,
                om.writeValueAsString(payload), actor(body), now());
        return Map.of("evaluation", evaluator.evaluate());
    }

    @PostMapping("/actions/select-control")
    public Map<String, Object> selectControl(@RequestBody Map<String, Object> body) {
        String tcId = String.valueOf(body.get("tcId"));
        ds.update("INSERT INTO review_actions(cycle_id,type,target,payload,actor,created_ts)"
                        + " VALUES(?,?,?,?,?,?)",
                FixtureSeeder.CYCLE_ID, "SELECT_CONTROL", tcId, "{}", actor(body), now());
        return Map.of("evaluation", evaluator.evaluate());
    }

    @PostMapping("/deviations")
    public Map<String, Object> createDeviation(@RequestBody Map<String, Object> body) {
        Integer n = ds.jdbc().queryForObject(
                "SELECT COALESCE(MAX(branch_no),0)+1 FROM deviations WHERE cycle_id=? AND rule_code=?",
                Integer.class, FixtureSeeder.CYCLE_ID, String.valueOf(body.get("ruleCode")));
        ds.update("INSERT INTO deviations(cycle_id,rule_code,branch_no,status,owner,reason,created_ts)"
                        + " VALUES(?,?,?,?,?,?,?)",
                FixtureSeeder.CYCLE_ID, String.valueOf(body.get("ruleCode")), n, "OPEN",
                String.valueOf(body.getOrDefault("owner", "质量工程师")),
                String.valueOf(body.getOrDefault("reason", "")), now());
        return Map.of("evaluation", evaluator.evaluate());
    }

    @PostMapping("/deviations/resolve")
    public Map<String, Object> resolveDeviation(@RequestBody Map<String, Object> body) {
        ds.update("UPDATE deviations SET status=?, resolution=?, resolved_ts=? WHERE id=?",
                String.valueOf(body.get("status")),
                String.valueOf(body.getOrDefault("resolution", "")), now(),
                ((Number) body.get("id")).longValue());
        return Map.of("evaluation", evaluator.evaluate());
    }

    @GetMapping("/export")
    public Map<String, Object> exportData() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("format", "cure-evidence-bench-1");
        dump.put("exportedTs", now());
        Map<String, Object> tables = new LinkedHashMap<>();
        for (String t : TABLES) {
            tables.put(t, ds.exportTable(t));
        }
        dump.put("tables", tables);
        return dump;
    }

    @PostMapping("/import")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> importData(@RequestBody Map<String, Object> body) {
        Object tablesObj = body.get("tables");
        if (!(tablesObj instanceof Map)) {
            throw new IllegalArgumentException("缺少 tables 字段");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tables = (Map<String, Object>) tablesObj;
        ds.jdbc().execute("PRAGMA foreign_keys = OFF");
        for (int i = TABLES.size() - 1; i >= 0; i--) {
            ds.update("DELETE FROM " + TABLES.get(i));
        }
        try {
            for (String t : TABLES) {
                Object rows = tables.get(t);
                if (rows instanceof List<?> list) {
                    ds.importTable(t, list);
                }
            }
            ds.jdbc().execute("DELETE FROM sqlite_sequence");
        } finally {
            ds.jdbc().execute("PRAGMA foreign_keys = ON");
        }
        return Map.of("evaluation", evaluator.evaluate(false), "imported", true);
    }

    @PostMapping("/reset")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> reset() {
        for (int i = TABLES.size() - 1; i >= 0; i--) {
            ds.update("DELETE FROM " + TABLES.get(i));
        }
        ds.jdbc().execute("DELETE FROM sqlite_sequence");
        seeder.seed();
        return Map.of("evaluation", evaluator.evaluate());
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }

    private String actor(Map<String, Object> body) {
        return String.valueOf(body.getOrDefault("actor", "评审员"));
    }
}

package com.example.cure.engine;

import com.example.cure.Time;
import com.example.cure.engine.SoakEngine.InBand;
import com.example.cure.engine.SoakEngine.IntegrityEvent;
import com.example.cure.engine.SoakEngine.JointRange;
import com.example.cure.engine.SoakEngine.Position;
import com.example.cure.engine.SoakEngine.Sample;
import com.example.cure.engine.SoakEngine.Segment;
import com.example.cure.engine.SoakEngine.WindowResult;
import com.example.cure.repo.DataStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {

    static final String CYCLE_ID = "CYCLE-20260918-01";
    static final long MIN = 60;

    private final DataStore ds;
    private final ObjectMapper om = new ObjectMapper();

    public EvaluationService(DataStore ds) {
        this.ds = ds;
    }

    public record RuleView(String code, String title, boolean pass, String message,
                          Map<String, Object> evidence, List<long[]> failRanges) {
    }

    public record Evaluation(String overall, String specVersion, String inputHash,
                             long longestJointSec, long jointTotalSec,
                             List<RuleView> rules,
                             List<IntegrityEvent> integrityEvents,
                             List<Map<String, Object>> suspectedAnomalies,
                             long controlEntryTs,
                             List<JointRange> jointRanges) {
    }

    @Transactional
    public Evaluation evaluate() {
        return evaluate(true);
    }

    @Transactional
    public Evaluation evaluate(boolean persist) {
        Map<String, Object> spec = ds.queryOne(
                "SELECT * FROM spec_versions WHERE version=(SELECT spec_version FROM cure_cycles WHERE id=?)",
                CYCLE_ID);
        double low = ((Number) spec.get("band_low")).doubleValue();
        double high = ((Number) spec.get("band_high")).doubleValue();
        long minSoak = ((Number) spec.get("min_soak_sec")).longValue();
        double maxRamp = ((Number) spec.get("max_ramp_c_min")).doubleValue();
        double minPressure = ((Number) spec.get("min_pressure_mpa")).doubleValue();
        double maxVacuum = ((Number) spec.get("max_vacuum_kpa")).doubleValue();

        Map<String, Object> cycle = ds.queryOne("SELECT * FROM cure_cycles WHERE id=?", CYCLE_ID);
        long rampStart = ((Number) cycle.get("planned_start_ts")).longValue();
        List<Map<String, Object>> stages = ds.query(
                "SELECT * FROM cycle_stages WHERE cycle_id=? ORDER BY stage_order", CYCLE_ID);
        long holdStart = 0;
        long holdEnd = 0;
        for (Map<String, Object> st : stages) {
            if ("保温".equals(st.get("name"))) {
                holdStart = ((Number) st.get("start_ts")).longValue();
                holdEnd = ((Number) st.get("end_ts")).longValue();
            }
        }

        List<Map<String, Object>> tcs = ds.query(
                "SELECT * FROM thermocouples WHERE cycle_id=? ORDER BY id", CYCLE_ID);
        List<Map<String, Object>> replacements = ds.query(
                "SELECT * FROM tc_replacements WHERE cycle_id=? ORDER BY ts", CYCLE_ID);
        List<Map<String, Object>> actions = ds.query(
                "SELECT * FROM review_actions WHERE cycle_id=? ORDER BY id", CYCLE_ID);
        List<Map<String, Object>> deviations = ds.query(
                "SELECT * FROM deviations WHERE cycle_id=? ORDER BY id", CYCLE_ID);

        List<long[]> confirmed = confirmedAnomalyRanges(actions);
        String controlTc = controlTcId(tcs, actions);

        // 按替换链把同一物理位置的热电偶归为一组
        Map<String, List<String>> chains = replacementChains(tcs, replacements);
        List<Position> positions = new ArrayList<>();
        List<IntegrityEvent> events = new ArrayList<>();
        long controlEntryTs = 0;

        for (Map.Entry<String, List<String>> chain : chains.entrySet()) {
            String posId = chain.getKey();
            List<Segment> segs = new ArrayList<>();
            String tcIds = String.join(",", chain.getValue());
            int offset = 0;
            for (String tcId : chain.getValue()) {
                List<Map<String, Object>> rows = ds.query(
                        "SELECT * FROM tc_samples WHERE cycle_id=? AND tc_id=? ORDER BY ord",
                        CYCLE_ID, tcId);
                List<Sample> samples = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    samples.add(new Sample(((Number) r.get("ts")).longValue(),
                            ((Number) r.get("temp")).doubleValue()));
                }
                List<Segment> tcsSegs = SoakEngine.toSegments(tcId, samples, events);
                for (Segment sg : tcsSegs) {
                    segs.add(new Segment(sg.tcId(), sg.segIndex() + offset, sg.samples(), sg.startReason()));
                }
                offset += tcsSegs.size();
                if (chain.getValue().size() > 1) {
                    for (Map<String, Object> rp : replacements) {
                        if (tcId.equals(rp.get("old_tc_id"))) {
                            events.add(new IntegrityEvent("SENSOR_REPLACEMENT", tcId,
                                    ((Number) rp.get("ts")).longValue(),
                                    tcId + " 被 " + rp.get("new_tc_id") + " 替换，连续保温证据在此分割"));
                        }
                    }
                }
            }
            positions.add(new Position(posId, tcIds, segs));
            if (chain.getValue().contains(controlTc)) {
                controlEntryTs = firstEntryTs(segs, low, high);
            }
        }
        events.sort(Comparator.comparingLong(IntegrityEvent::ts));

        List<Map<String, Object>> suspected = detectSuspectedAnomalies(positions, confirmed, low, high);

        List<RuleView> rules = new ArrayList<>();
        rules.add(checkLayup());
        rules.add(checkMaterialLot());
        rules.add(checkRamp(positions, low, high, rampStart, holdStart, maxRamp, confirmed, controlEntryTs));
        WindowResult window = SoakEngine.evaluateWindow(positions, low, high, holdStart, holdEnd);
        rules.add(checkSoak(window, holdStart, holdEnd, minSoak, low, high));
        rules.add(checkPressure(holdStart, holdEnd, minPressure));
        rules.add(checkVacuum(holdStart, holdEnd, maxVacuum));
        rules.add(checkIntegrity(events, positions, holdStart, holdEnd));
        rules.add(checkCoverage(positions, holdStart, holdEnd, low, high));

        Map<String, String> acceptedByRule = new LinkedHashMap<>();
        for (Map<String, Object> d : deviations) {
            if ("ACCEPTED".equals(d.get("status"))) {
                acceptedByRule.put((String) d.get("rule_code"), String.valueOf(d.get("id")));
            }
        }
        Map<String, String> openByRule = new LinkedHashMap<>();
        for (Map<String, Object> d : deviations) {
            if ("OPEN".equals(d.get("status"))) {
                openByRule.put((String) d.get("rule_code"), String.valueOf(d.get("id")));
            }
        }

        boolean hasFailure = false;
        boolean hasOpen = false;
        for (RuleView r : rules) {
            if (!r.pass()) {
                if (acceptedByRule.containsKey(r.code())) {
                    continue;
                }
                if (openByRule.containsKey(r.code())) {
                    hasOpen = true;
                } else {
                    hasFailure = true;
                }
            }
        }
        String overall = hasFailure ? "NON_CONFORMING"
                : hasOpen ? "DEVIATION_PENDING"
                : acceptedByRule.isEmpty() ? "CONFORMING" : "CONFORMING_WITH_DEVIATION";

        String inputHash = inputHash(spec, cycle, stages, chains, confirmed, controlTc,
                deviations, ds);

        if (persist) {
            persistRun(overall, spec.get("version").toString(), inputHash, window, rules,
                    events, suspected, controlEntryTs);
        }

        return new Evaluation(overall, spec.get("version").toString(), inputHash,
                window.longestSeconds(), window.jointTotalSeconds(),
                rules, events, suspected, controlEntryTs, window.jointRanges());
    }

    private RuleView checkLayup() {
        List<Map<String, Object>> plies = ds.query(
                "SELECT p.*, l.lot_code, l.laid_ts, l.operator, l.layup_serial, l.id AS layup_id "
                        + "FROM plies p LEFT JOIN layup_records l "
                        + "ON p.part_id=l.part_id AND p.seq=l.seq "
                        + "WHERE p.part_id=? ORDER BY p.seq", "P-2609-01");
        boolean pass = true;
        List<Map<String, Object>> links = new ArrayList<>();
        Integer previousOrientation = null;
        for (Map<String, Object> p : plies) {
            boolean linked = p.get("lot_code") != null;
            pass &= linked;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", p.get("seq"));
            m.put("materialCode", p.get("material_code"));
            m.put("orientationDeg", p.get("orientation_deg"));
            m.put("lotCode", p.get("lot_code"));
            m.put("layupSerial", p.get("layup_serial"));
            m.put("laidTs", p.get("laid_ts"));
            m.put("laidLabel", p.get("laid_ts") == null ? null : Time.mdhm(((Number) p.get("laid_ts")).longValue()));
            m.put("operator", p.get("operator"));
            m.put("linked", linked);
            if (previousOrientation != null) {
                m.put("deltaFromPrev", ((Number) p.get("orientation_deg")).intValue() - previousOrientation);
            }
            previousOrientation = ((Number) p.get("orientation_deg")).intValue();
            links.add(m);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("plyCount", plies.size());
        evidence.put("links", links);
        return new RuleView("R1-LAYUP", "设计铺层与实际铺放逐层关联", pass,
                pass ? plies.size() + " 层全部关联到材料批次与铺放记录" : "存在未关联铺层",
                evidence, pass ? List.of() : List.of(new long[]{0, 0}));
    }

    private RuleView checkMaterialLot() {
        Map<String, Object> lot = ds.queryOne(
                "SELECT * FROM material_lots WHERE lot_code=?", "LOT-2026-0711");
        Map<String, Object> cycle = ds.queryOne("SELECT planned_start_ts FROM cure_cycles WHERE id=?", CYCLE_ID);
        long useTs = ((Number) cycle.get("planned_start_ts")).longValue();
        boolean pass = lot != null
                && ((Number) lot.get("received_ts")).longValue() <= useTs
                && useTs <= ((Number) lot.get("expiry_ts")).longValue();
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (lot != null) {
            evidence.put("lotCode", lot.get("lot_code"));
            evidence.put("materialCode", lot.get("material_code"));
            evidence.put("certNo", lot.get("cert_no"));
            evidence.put("receivedTs", lot.get("received_ts"));
            evidence.put("expiryTs", lot.get("expiry_ts"));
        }
        return new RuleView("R2-LOT", "材料批次证书有效且在使用期内", pass,
                pass ? "批次证书在有效期内覆盖炉次时间" : "材料批次无效或超出有效期",
                evidence, List.of());
    }

    private RuleView checkRamp(List<Position> positions, double low, double high,
                               long rampStart, long rampEnd, double maxRamp,
                               List<long[]> confirmed, long controlEntryTs) {
        List<Map<String, Object>> breaches = new ArrayList<>();
        double worstRate = 0;
        for (Position p : positions) {
            for (Segment seg : p.segments()) {
                Sample prev = null;
                for (Sample s : seg.samples()) {
                    if (s.ts() < rampStart || s.ts() > rampEnd) {
                        prev = s;
                        continue;
                    }
                    if (prev != null && s.ts() > prev.ts()) {
                        double rate = (s.temp() - prev.temp()) * MIN / (s.ts() - prev.ts());
                        worstRate = Math.max(worstRate, rate);
                        if (rate > maxRamp && !insideAny(prev.ts(), s.ts(), confirmed)) {
                            breaches.add(mapOf("tcId", seg.tcId(), "start", prev.ts(),
                                    "end", s.ts(), "rate", Math.round(rate * 100) / 100.0));
                        }
                    }
                    if (s.temp() > high + 2.0 && !insideAny(s.ts(), s.ts() + MIN, confirmed)) {
                        breaches.add(mapOf("tcId", seg.tcId(), "start", s.ts(),
                                "end", Math.min(s.ts() + MIN, rampEnd), "temp", s.temp()));
                    }
                    prev = s;
                }
            }
        }
        List<long[]> ranges = mergeRanges(breaches.stream()
                .map(b -> new long[]{((Number) b.get("start")).longValue(),
                        ((Number) b.get("end")).longValue()})
                .toList());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("maxRampCMin", maxRamp);
        evidence.put("worstRateCMin", Math.round(worstRate * 100) / 100.0);
        evidence.put("controlEntryTs", controlEntryTs);
        evidence.put("controlEntryLabel", Time.hm(controlEntryTs));
        evidence.put("breaches", breaches);
        evidence.put("confirmedAnomalyRanges", confirmed);
        boolean pass = ranges.isEmpty();
        return new RuleView("R3-RAMP", "升温速率与超温限值（已确认异常剔除）", pass,
                pass ? "控制点 " + Time.hm(controlEntryTs) + " 到温；无超温或超速"
                        : "存在 " + ranges.size() + " 段超温/超速证据（可确认传感器异常后重评）",
                evidence, ranges);
    }

    private RuleView checkSoak(WindowResult window, long holdStart, long holdEnd,
                               long minSoak, double low, double high) {
        List<Map<String, Object>> jointEvidence = new ArrayList<>();
        for (JointRange j : window.jointRanges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", j.start());
            m.put("end", j.end());
            m.put("startLabel", Time.hm(j.start()));
            m.put("endLabel", Time.hm(j.end()));
            m.put("seconds", j.seconds());
            m.put("minutes", j.seconds() / MIN);
            m.put("segmentKeys", j.segmentKeys());
            jointEvidence.add(m);
        }
        List<long[]> failRanges = new ArrayList<>();
        long cur = holdStart;
        for (JointRange j : window.jointRanges()) {
            if (j.start() > cur) {
                failRanges.add(new long[]{cur, j.start()});
            }
            cur = Math.max(cur, j.end());
        }
        if (cur < holdEnd) {
            failRanges.add(new long[]{cur, holdEnd});
        }
        boolean pass = window.longestSeconds() >= minSoak;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("band", low + "~" + high + "°C（闭区间）");
        evidence.put("holdStart", holdStart);
        evidence.put("holdEnd", holdEnd);
        evidence.put("holdStartLabel", Time.hm(holdStart));
        evidence.put("holdEndLabel", Time.hm(holdEnd));
        evidence.put("minContinuousSec", minSoak);
        evidence.put("longestJointSec", window.longestSeconds());
        evidence.put("longestJointMin", window.longestSeconds() / MIN);
        evidence.put("jointTotalSec", window.jointTotalSeconds());
        evidence.put("jointTotalMin", window.jointTotalSeconds() / MIN);
        evidence.put("jointRanges", jointEvidence);
        evidence.put("perPositionInBandMin", window.inBandSecondsByPosition());
        return new RuleView("R4-SOAK", "全部必需热电偶共同达标连续保温", pass,
                pass ? "共同达标最长连续保温 " + (window.longestSeconds() / MIN) + " 分钟 ≥ 120 分钟"
                        : "共同达标最长连续保温仅 " + (window.longestSeconds() / MIN)
                                + " 分钟 < 120 分钟（不可逐支相加）",
                evidence, failRanges);
    }

    private RuleView checkPressure(long holdStart, long holdEnd, double minPressure) {
        return checkEnv("pressure", "R5-PRESSURE", "保温阶段压力不低于规范下限",
                holdStart, holdEnd, minPressure, true,
                "保温阶段压力均 ≥ " + minPressure + " MPa");
    }

    private RuleView checkVacuum(long holdStart, long holdEnd, double maxVacuum) {
        return checkEnv("vacuum", "R6-VACUUM", "保温阶段真空度不高于规范上限",
                holdStart, holdEnd, maxVacuum, false,
                "保温阶段真空均 ≤ " + maxVacuum + " kPa");
    }

    private RuleView checkEnv(String kind, String code, String title,
                              long holdStart, long holdEnd, double threshold,
                              boolean higherIsOk, String passMessage) {
        List<Map<String, Object>> rows = ds.query(
                "SELECT * FROM env_samples WHERE cycle_id=? AND kind=? ORDER BY ts", CYCLE_ID, kind);
        List<Map<String, Object>> extreme = new ArrayList<>();
        double ext = higherIsOk ? Double.MAX_VALUE : -Double.MAX_VALUE;
        long extTs = holdStart;
        for (Map<String, Object> r : rows) {
            long ts = ((Number) r.get("ts")).longValue();
            if (ts < holdStart - MIN || ts > holdEnd) {
                continue;
            }
            double v = ((Number) r.get("value")).doubleValue();
            if ((higherIsOk && v < ext) || (!higherIsOk && v > ext)) {
                ext = v;
                extTs = ts;
            }
        }
        boolean pass = higherIsOk ? ext >= threshold : ext <= threshold;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("threshold", threshold);
        evidence.put("extremeValue", ext);
        evidence.put("extremeTs", extTs);
        evidence.put("extremeLabel", Time.hm(extTs));
        evidence.put("sampleCount", rows.size());
        return new RuleView(code, title, pass,
                pass ? passMessage : "保温阶段存在越限记录",
                evidence, pass ? List.of() : List.of(new long[]{extTs, extTs}));
    }

    private RuleView checkIntegrity(List<IntegrityEvent> events, List<Position> positions,
                                    long holdStart, long holdEnd) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> evList = new ArrayList<>();
        for (IntegrityEvent e : events) {
            evList.add(mapOf("type", e.type(), "tcId", e.tcId(),
                    "ts", e.ts(), "label", Time.hm(e.ts()), "detail", e.detail()));
        }
        evidence.put("events", evList);
        evidence.put("note", "时钟回退、重复样本与传感器替换均强制分割证据段，不拼接连续保温");
        return new RuleView("R7-INTEGRITY", "采集完整性事件已记录且证据段已分割", true,
                "共记录 " + events.size() + " 个完整性事件，全部按规则分割证据段",
                evidence, List.of());
    }

    private RuleView checkCoverage(List<Position> positions, long holdStart, long holdEnd,
                                   double low, double high) {
        long windowSec = holdEnd - holdStart;
        List<Map<String, Object>> cov = new ArrayList<>();
        boolean pass = true;
        for (Position p : positions) {
            List<InBand> all = SoakEngine.inBandIntervals(p.segments(), low, high);
            long covered = 0;
            for (InBand ib : all) {
                long s = Math.max(ib.start(), holdStart);
                long e = Math.min(ib.end(), holdEnd);
                if (s < e) {
                    covered += e - s;
                }
            }
            double ratio = windowSec == 0 ? 0 : (double) covered / windowSec;
            pass &= ratio >= 0.90;
            cov.add(mapOf("position", p.positionId(), "tcIds", p.tcIds(),
                    "coverageMin", covered / MIN, "ratio", Math.round(ratio * 1000) / 1000.0));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("positions", cov);
        evidence.put("replacementPolicy", "替换缺口视为该位置缺测，共同达标中断");
        return new RuleView("R8-COVERAGE", "必需热电偶位置覆盖与替换链可追溯", pass,
                pass ? "全部必需位置在保温窗内覆盖率 ≥ 90%，替换链可追溯" : "存在位置覆盖率不足",
                evidence, List.of());
    }

    private Map<String, List<String>> replacementChains(List<Map<String, Object>> tcs,
                                                        List<Map<String, Object>> replacements) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (Map<String, Object> tc : tcs) {
            parent.put(tc.get("id").toString(), tc.get("id").toString());
        }
        java.util.function.UnaryOperator<String> root = x -> {
            String r = x;
            while (!parent.get(r).equals(r)) {
                r = parent.get(r);
            }
            parent.put(x, r);
            return r;
        };
        for (Map<String, Object> rp : replacements) {
            String a = rp.get("old_tc_id").toString();
            String b = rp.get("new_tc_id").toString();
            parent.put(root.apply(a), root.apply(b));
        }
        Map<String, List<String>> chains = new LinkedHashMap<>();
        for (Map<String, Object> tc : tcs) {
            if (((Number) tc.get("required")).intValue() == 0) {
                continue;
            }
            String rootId = root.apply(tc.get("id").toString());
            chains.computeIfAbsent(rootId, k -> new ArrayList<>()).add(tc.get("id").toString());
        }
        for (List<String> v : chains.values()) {
            v.sort(Comparator.naturalOrder());
        }
        return chains;
    }

    @SuppressWarnings("unchecked")
    private List<long[]> confirmedAnomalyRanges(List<Map<String, Object>> actions) {
        List<long[]> out = new ArrayList<>();
        for (Map<String, Object> a : actions) {
            if ("CONFIRM_ANOMALY".equals(a.get("type"))) {
                try {
                    Map<String, Object> payload = om.readValue(String.valueOf(a.get("payload")), Map.class);
                    long start = ((Number) payload.get("startTs")).longValue();
                    long end = ((Number) payload.get("endTs")).longValue();
                    out.add(new long[]{start, end});
                } catch (Exception ignored) {
                }
            }
        }
        return mergeRanges(out);
    }

    private String controlTcId(List<Map<String, Object>> tcs, List<Map<String, Object>> actions) {
        String selected = null;
        for (Map<String, Object> a : actions) {
            if ("SELECT_CONTROL".equals(a.get("type"))) {
                selected = String.valueOf(a.get("target"));
            }
        }
        if (selected != null) {
            return selected;
        }
        for (Map<String, Object> tc : tcs) {
            if (((Number) tc.get("is_control")).intValue() == 1) {
                return tc.get("id").toString();
            }
        }
        return tcs.get(0).get("id").toString();
    }

    private long firstEntryTs(List<Segment> segments, double low, double high) {
        List<InBand> bands = SoakEngine.inBandIntervals(segments, low, high);
        return bands.isEmpty() ? 0 : bands.get(0).start();
    }

    private List<Map<String, Object>> detectSuspectedAnomalies(List<Position> positions,
            List<long[]> confirmed, double low, double high) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Position p : positions) {
            List<Sample> bad = new ArrayList<>();
            for (Segment seg : p.segments()) {
                for (Sample s : seg.samples()) {
                    if (s.temp() > high + 2.0) {
                        bad.add(s);
                    }
                }
            }
            bad.sort(Comparator.comparingLong(Sample::ts));
            for (int i = 0; i < bad.size(); i++) {
                int j = i;
                double peak = bad.get(i).temp();
                while (j + 1 < bad.size() && bad.get(j + 1).ts() - bad.get(j).ts() <= 2 * MIN) {
                    j++;
                    peak = Math.max(peak, bad.get(j).temp());
                }
                long start = bad.get(i).ts() - MIN;
                long end = bad.get(j).ts() + MIN;
                if (!insideAny(start, end, confirmed)) {
                    out.add(mapOf("tcId", p.tcIds().contains(",") ? p.tcIds() : p.tcIds(),
                            "startTs", start, "endTs", end,
                            "startLabel", Time.hm(start), "endLabel", Time.hm(end),
                            "temp", peak, "kind", "OVER_BAND_SPIKE"));
                }
                i = j;
            }
        }
        return out;
    }

    private boolean insideAny(long start, long end, List<long[]> ranges) {
        for (long[] r : ranges) {
            if (start < r[1] && end > r[0]) {
                return true;
            }
        }
        return false;
    }

    private List<long[]> mergeRanges(List<long[]> in) {
        List<long[]> sorted = in.stream()
                .sorted(Comparator.comparingLong(a -> a[0]))
                .collect(java.util.stream.Collectors.toList());
        List<long[]> out = new ArrayList<>();
        for (long[] r : sorted) {
            if (out.isEmpty() || r[0] > out.get(out.size() - 1)[1]) {
                out.add(new long[]{r[0], r[1]});
            } else {
                out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], r[1]);
            }
        }
        return out;
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private String inputHash(Map<String, Object> spec, Map<String, Object> cycle,
                             List<Map<String, Object>> stages, Map<String, List<String>> chains,
                             List<long[]> confirmed, String controlTc,
                             List<Map<String, Object>> deviations, DataStore data) {
        try {
            Map<String, Object> digest = new LinkedHashMap<>();
            digest.put("spec", spec);
            digest.put("cycle", cycle);
            digest.put("stages", stages);
            digest.put("chains", chains);
            digest.put("sampleCounts", data.query(
                    "SELECT tc_id, COUNT(*) AS n, MIN(ts) AS minTs, MAX(ts) AS maxTs "
                            + "FROM tc_samples WHERE cycle_id=? GROUP BY tc_id ORDER BY tc_id", CYCLE_ID));
            digest.put("envCounts", data.query(
                    "SELECT kind, COUNT(*) AS n, MIN(ts) AS minTs, MAX(ts) AS maxTs "
                            + "FROM env_samples WHERE cycle_id=? GROUP BY kind ORDER BY kind", CYCLE_ID));
            digest.put("confirmedAnomalies", confirmed);
            digest.put("controlTc", controlTc);
            digest.put("deviations", deviations.stream()
                    .map(d -> mapOf("id", d.get("id"), "rule", d.get("rule_code"),
                            "status", d.get("status"), "resolution", d.get("resolution")))
                    .toList());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(om.writeValueAsBytes(digest));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("input hash failed", e);
        }
    }

    private void persistRun(String overall, String specVersion, String inputHash,
                            WindowResult window, List<RuleView> rules,
                            List<IntegrityEvent> events, List<Map<String, Object>> suspected,
                            long controlEntryTs) {
        Map<String, Object> detail = mapOf(
                "jointRanges", window.jointRanges().stream().map(j -> mapOf(
                        "start", j.start(), "end", j.end(), "seconds", j.seconds(),
                        "startLabel", Time.hm(j.start()), "endLabel", Time.hm(j.end()),
                        "segmentKeys", j.segmentKeys())).toList(),
                "integrityEvents", events.stream().map(e -> mapOf(
                        "type", e.type(), "tcId", e.tcId(), "ts", e.ts(),
                        "label", Time.hm(e.ts()), "detail", e.detail())).toList(),
                "suspectedAnomalies", suspected,
                "controlEntryTs", controlEntryTs,
                "controlEntryLabel", Time.hm(controlEntryTs));
        String detailJson;
        try {
            detailJson = om.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        ds.update("INSERT INTO evaluation_runs(cycle_id,created_ts,overall,spec_version,"
                        + "input_hash,longest_joint_sec,joint_total_sec,detail_json) VALUES(?,?,?,?,?,?,?,?)",
                CYCLE_ID, System.currentTimeMillis() / 1000, overall, specVersion, inputHash,
                window.longestSeconds(), window.jointTotalSeconds(), detailJson);
        long runId = ds.jdbc().queryForObject("SELECT last_insert_rowid()", Long.class);
        for (RuleView r : rules) {
            List<Map<String, Object>> failLabels = new ArrayList<>();
            for (long[] fr : r.failRanges()) {
                failLabels.add(mapOf("start", fr[0], "end", fr[1],
                        "startLabel", Time.hm(fr[0]), "endLabel", Time.hm(fr[1])));
            }
            try {
                ds.update("INSERT INTO rule_results(run_id,rule_code,title,pass,message,"
                                + "evidence_json,fail_ranges_json) VALUES(?,?,?,?,?,?,?)",
                        runId, r.code(), r.title(), r.pass() ? 1 : 0, r.message(),
                        om.writeValueAsString(r.evidence()),
                        om.writeValueAsString(failLabels));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

package com.example.cure.repo;

import static com.example.cure.Time.t;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 固定演示炉次（Asia/Tokyo，2026-09-18）：
 * 升温 08:00-09:30，保温 09:30-12:40，降温 12:40-14:10，每分钟一条样本。
 * 三支必需热电偶错峰进入允许带 175-180°C（闭区间）：
 * TC-01 09:20、TC-02A 09:30、TC-03 09:38；共同达标 09:38 起算。
 * TC-02A 于 10:53 被替换为 TC-02B（10:55 首条，恰为上限 180°C），证据被分割。
 * TC-03 升温段 08:43-08:44 有超温尖峰（待确认异常），09:02 有重复样本，
 * 09:15 之后出现时钟回退（09:13）样本；保温段 12:26 掉至 176.0，
 * 12:27 恰为下限 175.0（闭区间判合格），12:28 回到 180.0。
 */
@Component
public class FixtureSeeder {

    public static final String CYCLE_ID = "CYCLE-20260918-01";
    public static final String PART_ID = "P-2609-01";
    public static final String SPEC = "CP-CURE-2024-A";
    public static final String LOT = "LOT-2026-0711";
    static final String MATERIAL = "M-CARBON-PREG-A";

    public static final long BASE = t(2026, 9, 18, 8, 0);
    public static final long MIN = 60;

    private final DataStore ds;

    public FixtureSeeder(DataStore ds) {
        this.ds = ds;
    }

    @Transactional
    public void seed() {
        seedSpec();
        seedPartAndPlies();
        seedCycle();
        seedThermocouples();
        seedTcSamples();
        seedEnv();
        seedActionsAndDeviations();
    }

    private void seedSpec() {
        ds.update("INSERT INTO spec_versions(version,band_low,band_high,min_soak_sec,"
                        + "max_ramp_c_min,min_pressure_mpa,max_vacuum_kpa,required_tc_count,description) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)",
                SPEC, 175.0, 180.0, 7200, 2.5, 0.55, -60.0, 3,
                "碳纤维预浸料热压罐固化规范 A 版：保温允许带 175-180°C（闭区间），"
                        + "共同达标连续保温不少于 120 分钟");
    }

    private void seedPartAndPlies() {
        ds.update("INSERT INTO parts(id,code,name,spec_version) VALUES(?,?,?,?)",
                PART_ID, "WING-SKIN-A", "主翼下蒙皮 A 件", SPEC);
        int[] orientations = {0, 45, 90, -45, -45, 90, 45, 0};
        long layupBase = t(2026, 9, 17, 9, 0);
        for (int i = 0; i < orientations.length; i++) {
            int seq = i + 1;
            ds.update("INSERT INTO plies(part_id,seq,material_code,orientation_deg,nominal_thickness_mm)"
                            + " VALUES(?,?,?,?,?)",
                    PART_ID, seq, MATERIAL, orientations[i], 0.125);
            ds.update("INSERT INTO layup_records(part_id,seq,lot_code,laid_ts,operator,layup_serial)"
                            + " VALUES(?,?,?,?,?,?)",
                    PART_ID, seq, LOT, layupBase + seq * 6 * MIN,
                    "王磊", String.format("LAY-20260917-%03d", seq));
        }
        ds.update("INSERT INTO material_lots(lot_code,material_code,cert_no,received_ts,expiry_ts)"
                        + " VALUES(?,?,?,?,?)",
                LOT, MATERIAL, "CERT-7781",
                t(2026, 6, 1, 0, 0), t(2027, 6, 1, 0, 0));
    }

    private void seedCycle() {
        ds.update("INSERT INTO cure_cycles(id,part_id,spec_version,planned_start_ts,planned_end_ts)"
                        + " VALUES(?,?,?,?,?)",
                CYCLE_ID, PART_ID, SPEC,
                t(2026, 9, 18, 8, 0), t(2026, 9, 18, 14, 10));
        addStage(1, "升温", 8, 0, 9, 30);
        addStage(2, "保温", 9, 30, 12, 40);
        addStage(3, "降温", 12, 40, 14, 10);
    }

    private void addStage(int order, String name, int sh, int sm, int eh, int em) {
        ds.update("INSERT INTO cycle_stages(cycle_id,stage_order,name,start_ts,end_ts) VALUES(?,?,?,?,?)",
                CYCLE_ID, order, name, t(2026, 9, 18, sh, sm), t(2026, 9, 18, eh, em));
    }

    private void seedThermocouples() {
        addTc("TC-01", "前缘控制点", 1, 1);
        addTc("TC-02A", "蒙皮中央(旧支)", 1, 0);
        addTc("TC-02B", "蒙皮中央(替换支)", 1, 0);
        addTc("TC-03", "后梁区", 1, 0);
        ds.update("INSERT INTO tc_replacements(cycle_id,old_tc_id,new_tc_id,ts) VALUES(?,?,?,?)",
                CYCLE_ID, "TC-02A", "TC-02B", t(2026, 9, 18, 10, 53));
    }

    private void addTc(String id, String label, int required, int control) {
        ds.update("INSERT INTO thermocouples(id,cycle_id,name,position_label,required,is_control)"
                        + " VALUES(?,?,?,?,?,?)",
                id, CYCLE_ID, id, label, required, control);
    }

    private void seedTcSamples() {
        int ord = 0;
        // TC-01：08:00 40°C 线性到 09:20 恰为下限 175°C，09:25 到 180°C 平台
        for (int m = 0; m <= 370; m++) {
            double temp;
            if (m <= 80) {
                temp = round1(40.0 + 135.0 * m / 80.0);
            } else if (m <= 85) {
                temp = round1(175.0 + (m - 80));
            } else if (m <= 280) {
                temp = 180.0;
            } else {
                temp = round1(180.0 - 120.0 * (m - 280) / 90.0);
            }
            insertSample("TC-01", ord++, m, temp);
        }
        // TC-02A：09:30（m=90）恰为 175°C，09:35 到 180°C；10:52（m=172）为最后一条
        int ordA = 0;
        for (int m = 0; m <= 172; m++) {
            double temp;
            if (m <= 90) {
                temp = round1(40.0 + 135.0 * m / 90.0);
            } else if (m <= 95) {
                temp = round1(175.0 + (m - 90));
            } else {
                temp = 180.0;
            }
            insertSample("TC-02A", ordA++, m, temp);
        }
        // TC-02B：10:55（m=175）首条恰为上限 180°C，保温后随炉降温
        int ordB = 0;
        for (int m = 175; m <= 370; m++) {
            double temp = m <= 280 ? 180.0 : round1(180.0 - 120.0 * (m - 280) / 90.0);
            insertSample("TC-02B", ordB++, m, temp);
        }
        // TC-03：09:38（m=98）恰为下限 175°C，09:43 到 180°C；
        // 含升温尖峰（08:43/08:44）、重复样本（09:02）、时钟回退（09:15 后出现 09:13）；
        // 保温段 12:26 掉至 174.0（出带，中断），12:27 恰为下限 175.0（闭区间合格），12:28 恢复
        int ord3 = 0;
        for (int m = 0; m <= 370; m++) {
            if (m == 62) {
                insertSample("TC-03", ord3++, m, tc3Temp(62));
                insertSample("TC-03", ord3++, m, tc3Temp(62));
                continue;
            }
            if (m == 76) {
                insertSample("TC-03", ord3++, 75, tc3Temp(75));
                insertSample("TC-03", ord3++, 73, tc3Temp(73));
                continue;
            }
            double temp;
            if (m == 43) {
                temp = 203.0;
            } else if (m == 44) {
                temp = 188.0;
            } else if (m == 266) {
                temp = 174.0;
            } else {
                temp = tc3Temp(m);
            }
            insertSample("TC-03", ord3++, m, temp);
        }
    }

    private double tc3Temp(int m) {
        if (m <= 98) {
            return round1(40.0 + 135.0 * m / 98.0);
        }
        if (m <= 103) {
            return round1(175.0 + (m - 98));
        }
        if (m <= 280) {
            return 180.0;
        }
        return round1(180.0 - 120.0 * (m - 280) / 90.0);
    }

    private void insertSample(String tcId, int ord, int minute, double temp) {
        ds.update("INSERT INTO tc_samples(cycle_id,tc_id,ord,ts,temp) VALUES(?,?,?,?,?)",
                CYCLE_ID, tcId, ord, BASE + minute * MIN, temp);
    }

    private void seedEnv() {
        int ord = 0;
        for (int m = 0; m <= 370; m += 2) {
            double pressure;
            if (m <= 10) {
                pressure = round2(0.10 + 0.50 * m / 10.0);
            } else if (m <= 280) {
                pressure = 0.60;
            } else {
                pressure = round2(0.60 - 0.55 * (m - 280) / 90.0);
            }
            double vacuum;
            if (m < 10) {
                vacuum = -20.0;
            } else if (m <= 280) {
                vacuum = -92.0;
            } else {
                vacuum = round2(-92.0 + 87.0 * (m - 280) / 90.0);
            }
            ds.update("INSERT INTO env_samples(cycle_id,kind,ord,ts,value) VALUES(?,?,?,?,?)",
                    CYCLE_ID, "pressure", ord, BASE + m * MIN, pressure);
            ds.update("INSERT INTO env_samples(cycle_id,kind,ord,ts,value) VALUES(?,?,?,?,?)",
                    CYCLE_ID, "vacuum", ord, BASE + m * MIN, vacuum);
            ord++;
        }
    }

    private void seedActionsAndDeviations() {
        // 空仓库起步：无评审动作、无偏差分支，全部由页面操作产生
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

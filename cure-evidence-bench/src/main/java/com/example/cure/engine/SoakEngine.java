package com.example.cure.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保温判定核心。
 *
 * 口径：
 * 1) 每支热电偶按录入序（ord）分段：时间戳重复即“重复样本”、
 *    时间戳较前一条回退即“时钟回退”，二者强制断开证据段；
 *    传感器替换（不同 tc_id 属于同一位置链）同样断开。
 * 2) 段内样本时间严格递增，按闭区间 [bandLow, bandHigh] 判定合格，
 *    相邻样本均合格才覆盖其间分钟格，段与段之间绝不拼接。
 * 3) “共同达标”区间在规则时间窗内取所有必需位置合格区间的逐格交集，
 *    只有所选证据段组合完全相同的相邻格才合并，任何一支离开或被分割即中断。
 */
public final class SoakEngine {

    public record Sample(long ts, double temp) {
    }

    public record Segment(String tcId, int segIndex, List<Sample> samples, String startReason) {
    }

    public record InBand(long start, long end, String tcId, int segIndex) {
    }

    public record JointRange(long start, long end, long seconds, List<String> segmentKeys) {
    }

    public record IntegrityEvent(String type, String tcId, long ts, String detail) {
    }

    public record Position(String positionId, String tcIds, List<Segment> segments) {
    }

    public record WindowResult(List<JointRange> jointRanges,
                               List<long[]> gapRanges,
                               long longestSeconds,
                               long jointTotalSeconds,
                               Map<String, Long> inBandSecondsByPosition) {
    }

    private SoakEngine() {
    }

    /** 按录入序切段，同时记录重复样本/时钟回退事件。 */
    public static List<Segment> toSegments(String tcId, List<Sample> ordSamples,
                                           List<IntegrityEvent> events) {
        List<Segment> segments = new ArrayList<>();
        List<Sample> cur = new ArrayList<>();
        String startReason = "STREAM_START";
        int segIndex = 0;
        Sample prev = null;
        for (Sample s : ordSamples) {
            if (prev != null && s.ts() == prev.ts()) {
                flush(segments, tcId, segIndex++, cur, startReason);
                startReason = "DUPLICATE_TS@" + s.ts();
                events.add(new IntegrityEvent("DUPLICATE_SAMPLE", tcId, s.ts(),
                        "时间戳 " + s.ts() + " 出现重复样本，证据段在此断开"));
                cur = new ArrayList<>();
            } else if (prev != null && s.ts() < prev.ts()) {
                flush(segments, tcId, segIndex++, cur, startReason);
                startReason = "CLOCK_ROLLBACK@" + s.ts();
                events.add(new IntegrityEvent("CLOCK_ROLLBACK", tcId, s.ts(),
                        "时间戳由 " + prev.ts() + " 回退到 " + s.ts() + "，证据段在此断开"));
                cur = new ArrayList<>();
            }
            cur.add(s);
            prev = s;
        }
        flush(segments, tcId, segIndex, cur, startReason);
        return segments;
    }

    private static void flush(List<Segment> out, String tcId, int idx,
                              List<Sample> samples, String reason) {
        if (!samples.isEmpty()) {
            out.add(new Segment(tcId, idx, List.copyOf(samples), reason));
        }
    }

    /**
     * 段内按时间戳排序后，样本值采用“保持到下一个时间戳”口径：
     * 相邻样本均合格才覆盖其间分钟格；区间结束于首个不合格样本的时间戳。
     * 不同证据段永不合并；闭区间边界 [low, high] 判合格。
     */
    public static List<InBand> inBandIntervals(List<Segment> segments, double low, double high) {
        List<InBand> out = new ArrayList<>();
        for (Segment seg : segments) {
            List<Sample> sorted = new ArrayList<>(seg.samples());
            sorted.sort(Comparator.comparingLong(Sample::ts));
            Long start = null;
            for (int i = 0; i < sorted.size(); i++) {
                Sample cur = sorted.get(i);
                boolean ok = cur.temp() >= low && cur.temp() <= high;
                if (ok && start == null) {
                    start = cur.ts();
                } else if (!ok && start != null) {
                    out.add(new InBand(start, cur.ts(), seg.tcId(), seg.segIndex()));
                    start = null;
                }
            }
            if (start != null) {
                Sample last = sorted.get(sorted.size() - 1);
                out.add(new InBand(start, last.ts(), seg.tcId(), seg.segIndex()));
            }
        }
        return out;
    }

    /**
     * 在 [windowStart, windowEnd] 内逐分钟格求所有位置同时合格的连续区间。
     * 相邻格必须来自完全相同的证据段组合才合并，杜绝跨段拼接。
     */
    public static WindowResult evaluateWindow(List<Position> positions, double low, double high,
                                              long windowStart, long windowEnd) {
        List<List<InBand>> perPosition = new ArrayList<>();
        Map<String, Long> inBandTotals = new LinkedHashMap<>();
        List<Long> gridList = new ArrayList<>();
        gridList.add(windowStart);
        gridList.add(windowEnd);
        for (Position p : positions) {
            List<InBand> clipped = new ArrayList<>();
            long total = 0;
            for (InBand ib : inBandIntervals(p.segments(), low, high)) {
                long s = Math.max(ib.start(), windowStart);
                long e = Math.min(ib.end(), windowEnd);
                if (s <= e) {
                    clipped.add(new InBand(s, e, ib.tcId(), ib.segIndex()));
                    gridList.add(s);
                    gridList.add(e);
                    total += e - s;
                }
            }
            perPosition.add(clipped);
            inBandTotals.put(p.positionId(), total);
        }
        gridList.sort(Comparator.naturalOrder());
        List<Long> grid = gridList.stream().distinct().toList();

        List<JointRange> joint = new ArrayList<>();
        List<long[]> gaps = new ArrayList<>();
        long longest = 0;
        long jointTotal = 0;

        Long runStart = null;
        long runEnd = 0;
        long runSeconds = 0;
        List<String> runKeys = null;
        Long gapStart = null;

        for (int i = 0; i + 1 < grid.size(); i++) {
            long u = grid.get(i);
            long v = grid.get(i + 1);
            if (u < windowStart || v > windowEnd) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            boolean all = true;
            for (List<InBand> intervals : perPosition) {
                InBand hit = null;
                for (InBand ib : intervals) {
                    if (ib.start() <= u && ib.end() >= v) {
                        hit = ib;
                        break;
                    }
                }
                if (hit == null) {
                    all = false;
                    break;
                }
                keys.add(hit.tcId() + "#" + hit.segIndex());
            }
            keys.sort(Comparator.naturalOrder());

            if (all) {
                gapStart = closeGap(gaps, gapStart, u);
                long cellSec = v - u;
                if (runStart == null) {
                    runStart = u;
                    runEnd = v;
                    runSeconds = cellSec;
                    runKeys = keys;
                } else if (runEnd == u && keys.equals(runKeys)) {
                    runEnd = v;
                    runSeconds += cellSec;
                } else {
                    joint.add(new JointRange(runStart, runEnd, runSeconds, List.copyOf(runKeys)));
                    runStart = u;
                    runEnd = v;
                    runSeconds = cellSec;
                    runKeys = keys;
                }
                longest = Math.max(longest, runSeconds);
                jointTotal += cellSec;
            } else {
                if (runStart != null) {
                    joint.add(new JointRange(runStart, runEnd, runSeconds, List.copyOf(runKeys)));
                    runStart = null;
                }
                if (gapStart == null) {
                    gapStart = u;
                }
            }
        }
        if (runStart != null) {
            joint.add(new JointRange(runStart, runEnd, runSeconds, List.copyOf(runKeys)));
        }
        if (gapStart != null) {
            gaps.add(new long[]{gapStart, windowEnd});
        }
        return new WindowResult(List.copyOf(joint), List.copyOf(gaps),
                longest, jointTotal, inBandTotals);
    }

    private static Long closeGap(List<long[]> gaps, Long gapStart, long at) {
        if (gapStart != null) {
            gaps.add(new long[]{gapStart, at});
        }
        return null;
    }
}

package com.example.cure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.cure.engine.SoakEngine;
import com.example.cure.engine.SoakEngine.IntegrityEvent;
import com.example.cure.engine.SoakEngine.JointRange;
import com.example.cure.engine.SoakEngine.Position;
import com.example.cure.engine.SoakEngine.Sample;
import com.example.cure.engine.SoakEngine.Segment;
import com.example.cure.engine.SoakEngine.WindowResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoakEngineTest {

    private static final long T0 = 0;
    private static final long M = 60;

    private List<Sample> samples(double... tempAtMinute) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < tempAtMinute.length; i++) {
            out.add(new Sample(T0 + i * M, tempAtMinute[i]));
        }
        return out;
    }

    private WindowResult run(List<Position> positions, long windowMin) {
        return SoakEngine.evaluateWindow(positions, 175.0, 180.0, T0, T0 + windowMin * M);
    }

    @Test
    void closedIntervalBoundariesCountAsInBand() {
        List<IntegrityEvent> events = new ArrayList<>();
        List<Segment> segs = SoakEngine.toSegments("TC-X",
                samples(174.0, 175.0, 180.0, 181.0), events);
        WindowResult w = run(List.of(new Position("P1", "TC-X", segs)), 4);
        // 分钟格 [0,1) 出带；[1,2)、[2,3) 上下限均合格；[3,4) 出带
        assertEquals(2 * M, w.longestSeconds());
        assertEquals(2 * M, w.jointTotalSeconds());
    }

    @Test
    void clockRollbackSplitsEvidence() {
        // 第 1 段：0-3 分钟在带；回退样本出现在 4 分钟后（时间戳 2，低温）；
        // 第 2 段：5-8 分钟在带。两段在带区间不相连，即使相连也不得拼成一段。
        List<Sample> raw = List.of(
                new Sample(T0, 180.0),
                new Sample(T0 + M, 180.0),
                new Sample(T0 + 2 * M, 180.0),
                new Sample(T0 + 3 * M, 100.0),
                new Sample(T0 + 4 * M, 180.0),
                new Sample(T0 + 2 * M, 100.0),
                new Sample(T0 + 5 * M, 180.0),
                new Sample(T0 + 6 * M, 180.0),
                new Sample(T0 + 7 * M, 180.0),
                new Sample(T0 + 8 * M, 180.0));
        List<IntegrityEvent> events = new ArrayList<>();
        List<Segment> segs = SoakEngine.toSegments("TC-X", raw, events);
        assertEquals(2, segs.size());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("CLOCK_ROLLBACK")));
        WindowResult w = run(List.of(new Position("P1", "TC-X", segs)), 9);
        List<JointRange> ranges = w.jointRanges();
        assertEquals(2, ranges.size());
        assertEquals(3 * M, ranges.get(0).seconds());
        assertEquals(3 * M, ranges.get(1).seconds());
    }


    @Test
    void duplicateSamplesSplitEvidence() {
        List<Sample> raw = List.of(
                new Sample(T0, 180.0),
                new Sample(T0 + M, 180.0),
                new Sample(T0 + M, 180.0),
                new Sample(T0 + 2 * M, 180.0),
                new Sample(T0 + 3 * M, 180.0));
        List<IntegrityEvent> events = new ArrayList<>();
        List<Segment> segs = SoakEngine.toSegments("TC-X", raw, events);
        assertEquals(2, segs.size());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("DUPLICATE_SAMPLE")));
        WindowResult w = run(List.of(new Position("P1", "TC-X", segs)), 4);
        assertEquals(2, w.jointRanges().size());
        assertEquals(2 * M, w.longestSeconds());
    }

    @Test
    void jointSoakIsIntersectionNotSumAndReplacementBreaksIt() {
        List<IntegrityEvent> e1 = new ArrayList<>();
        // A 位置：0-4 分钟全部在带
        List<Segment> a = SoakEngine.toSegments("A", samples(180, 180, 180, 180, 180), e1);
        // B 位置：由两支热电偶拼成，替换点在 2 分钟；两段各自连续也不得合并
        List<Segment> b1 = SoakEngine.toSegments("B1", samples(180, 180, 180), new ArrayList<>());
        List<Segment> b2 = SoakEngine.toSegments("B2", samples(180, 180), new ArrayList<>());
        List<Segment> b = new ArrayList<>();
        b.add(new Segment("B1", 0, b1.get(0).samples(), "STREAM_START"));
        b.add(new Segment("B2", 1, b2.get(0).samples().stream()
                .map(s -> new Sample(s.ts() + 2 * M, s.temp())).toList(), "REPLACEMENT"));

        WindowResult w = run(List.of(
                new Position("PA", "A", a), new Position("PB", "B1,B2", b)), 4);
        assertEquals(2, w.jointRanges().size());
        assertEquals(2 * M, w.longestSeconds(), "替换必须分割共同保温，不能拼成 4 分钟");
        assertEquals(3 * M, w.jointTotalSeconds());
    }
}

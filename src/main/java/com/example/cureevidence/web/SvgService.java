package com.example.cureevidence.web;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SvgService {
    private static final int WIDTH = 980;
    private static final int HEIGHT = 420;
    private static final int LEFT = 70;
    private static final int RIGHT = 940;
    private static final int TOP = 30;
    private static final int BOTTOM = 350;

    public String thermalSvg(Map<String, Object> bench) {
        List<Map<String, Object>> stages = castList(bench.get("stages"));
        List<Map<String, Object>> samples = castList(bench.get("temperatureSamples"));
        List<Map<String, Object>> events = castList(bench.get("sensorEvents"));
        Map<String, Object> soak = stages.stream().filter(stage -> "SOAK".equals(stage.get("code"))).findFirst().orElseThrow();
        OffsetDateTime start = time(stages.get(0).get("startAt"));
        OffsetDateTime end = time(stages.get(stages.size() - 1).get("endAt"));
        long minutes = Duration.between(start, end).toMinutes();

        StringBuilder svg = header();
        svg.append("<rect x='0' y='0' width='980' height='420' fill='#0f172a' rx='18'/>");
        int bandX = x(time(soak.get("startAt")), start, minutes);
        int bandW = x(time(soak.get("endAt")), start, minutes) - bandX;
        svg.append("<rect x='").append(bandX).append("' y='").append(y(185))
                .append("' width='").append(bandW).append("' height='").append(y(175) - y(185))
                .append("' fill='#22c55e' opacity='0.16'/>");
        for (int temp = 60; temp <= 200; temp += 20) {
            int yy = y(temp);
            svg.append("<line x1='70' x2='940' y1='").append(yy).append("' y2='").append(yy)
                    .append("' stroke='#334155' stroke-width='1'/>");
            svg.append("<text x='18' y='").append(yy + 4).append("' fill='#94a3b8' font-size='12'>")
                    .append(temp).append("°C</text>");
        }
        for (Map<String, Object> stage : stages) {
            int xx = x(time(stage.get("startAt")), start, minutes);
            svg.append("<line x1='").append(xx).append("' x2='").append(xx).append("' y1='28' y2='350' stroke='#64748b' stroke-dasharray='4 5'/>");
            svg.append("<text x='").append(xx + 6).append("' y='24' fill='#cbd5e1' font-size='12'>")
                    .append(escape(stage.get("label"))).append("</text>");
        }
        Map<String, String> colors = Map.of("TC1", "#38bdf8", "TC2", "#a78bfa", "TC3", "#fb7185", "TC3R", "#fbbf24");
        Map<String, List<Map<String, Object>>> bySensor = new LinkedHashMap<>();
        samples.forEach(sample -> bySensor.computeIfAbsent(String.valueOf(sample.get("sensorId")), ignored -> new java.util.ArrayList<>()).add(sample));
        bySensor.forEach((sensorId, rows) -> {
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                int xx = x(time(rows.get(i).get("sampleAt")), start, minutes);
                int yy = y(number(rows.get(i).get("temperatureC")));
                path.append(i == 0 ? "M" : "L").append(xx).append(' ').append(yy).append(' ');
            }
            svg.append("<path d='").append(path).append("' fill='none' stroke='")
                    .append(colors.getOrDefault(sensorId, "#e2e8f0")).append("' stroke-width='3'/>");
        });
        for (Map<String, Object> event : events) {
            int xx = x(time(event.get("eventAt")), start, minutes);
            String color = Boolean.TRUE.equals(event.get("confirmed")) ? "#22c55e" : "#f97316";
            svg.append("<line x1='").append(xx).append("' x2='").append(xx).append("' y1='35' y2='345' stroke='")
                    .append(color).append("' stroke-width='2' stroke-dasharray='7 4'/>");
            svg.append("<circle cx='").append(xx).append("' cy='46' r='5' fill='").append(color).append("'/>");
        }
        svg.append("<text x='70' y='386' fill='#e2e8f0' font-size='13'>绿色区域：175–185°C 闭区间；橙色线：待确认异常/替换；曲线必须所有必需传感器共同位于带内才累计。</text>");
        svg.append("</svg>");
        return svg.toString();
    }

    public String stackSvg(Map<String, Object> bench) {
        List<Map<String, Object>> plies = castList(bench.get("plies"));
        StringBuilder svg = header();
        svg.append("<rect width='980' height='420' fill='#0f172a' rx='18'/>");
        int rowHeight = 30;
        int startY = 54;
        for (int i = 0; i < plies.size(); i++) {
            Map<String, Object> ply = plies.get(i);
            int yy = startY + i * rowHeight;
            boolean match = number(ply.get("designAngle")) == number(ply.get("laidAngle"));
            String color = match ? "#166534" : "#991b1b";
            svg.append("<rect x='170' y='").append(yy).append("' width='640' height='24' rx='5' fill='").append(color).append("' opacity='0.82'/>");
            svg.append("<text x='186' y='").append(yy + 17).append("' fill='#f8fafc' font-size='13'>第 ")
                    .append(ply.get("sequenceNo")).append(" 层</text>");
            svg.append("<text x='350' y='").append(yy + 17).append("' fill='#f8fafc' font-size='13'>设计 ")
                    .append(ply.get("designAngle")).append("° / 实际 ").append(ply.get("laidAngle")).append("°</text>");
            svg.append("<text x='570' y='").append(yy + 17).append("' fill='#dbeafe' font-size='13'>")
                    .append(escape(ply.get("materialCode"))).append(" · ").append(escape(ply.get("batchNo")))
                    .append("</text>");
            svg.append("<text x='790' y='").append(yy + 17).append("' fill='#dbeafe' font-size='13'>")
                    .append(escape(ply.get("operatorId"))).append("</text>");
        }
        svg.append("<text x='170' y='35' fill='#f8fafc' font-size='18' font-weight='700'>铺层堆叠与材料方向追溯</text>");
        svg.append("</svg>");
        return svg.toString();
    }

    private StringBuilder header() {
        return new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><svg xmlns='http://www.w3.org/2000/svg' width='")
                .append(WIDTH).append("' height='").append(HEIGHT).append("' viewBox='0 0 980 420'>");
    }

    private int x(OffsetDateTime time, OffsetDateTime start, long totalMinutes) {
        long value = Duration.between(start, time).toMinutes();
        return LEFT + (int) ((RIGHT - LEFT) * value * 1.0d / totalMinutes);
    }

    private int y(double temp) {
        return TOP + (int) ((220 - temp) / 190.0d * (BOTTOM - TOP));
    }

    private OffsetDateTime time(Object value) {
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private String escape(Object value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

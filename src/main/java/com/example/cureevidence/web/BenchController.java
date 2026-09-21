package com.example.cureevidence.web;

import com.example.cureevidence.service.BenchService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BenchController {
    private final BenchService benchService;
    private final SvgService svgService;

    public BenchController(BenchService benchService, SvgService svgService) {
        this.benchService = benchService;
        this.svgService = svgService;
    }

    @GetMapping("/bench")
    public Map<String, Object> bench() {
        return benchService.bench();
    }

    @PostMapping("/sensor-events/{eventId}/confirm")
    public Map<String, Object> confirmSensorEvent(@PathVariable String eventId,
                                                  @RequestBody(required = false) Map<String, String> request) {
        benchService.confirmSensorAnomaly(eventId, actor(request));
        return benchService.bench();
    }

    @PostMapping("/control-points/{pointId}/select")
    public Map<String, Object> selectControlPoint(@PathVariable String pointId,
                                                  @RequestBody(required = false) Map<String, String> request) {
        benchService.selectControlPoint(pointId, actor(request));
        return benchService.bench();
    }

    @PostMapping("/deviations")
    public Map<String, Object> createDeviation(@RequestBody Map<String, String> request) {
        benchService.createDeviation(request.getOrDefault("ruleId", ""),
                request.getOrDefault("title", "偏差处理分支"),
                request.getOrDefault("description", ""), actor(request));
        return benchService.bench();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        benchService.reset();
        return benchService.bench();
    }

    @GetMapping(value = "/chart.svg", produces = "image/svg+xml;charset=UTF-8")
    public String thermalSvg() {
        return svgService.thermalSvg(benchService.bench());
    }

    @GetMapping(value = "/stack.svg", produces = "image/svg+xml;charset=UTF-8")
    public String stackSvg() {
        return svgService.stackSvg(benchService.bench());
    }

    @GetMapping(value = "/export", produces = "application/json;charset=UTF-8")
    public ResponseEntity<byte[]> export() {
        byte[] body = benchService.exportJson().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=cure-run-record-" + OffsetDateTime.now() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String actor(Map<String, String> request) {
        if (request != null && request.get("actor") != null && !request.get("actor").isBlank()) {
            return request.get("actor");
        }
        return "本地复核员";
    }
}

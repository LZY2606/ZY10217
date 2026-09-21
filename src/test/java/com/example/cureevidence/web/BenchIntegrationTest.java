package com.example.cureevidence.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.db.path=target/test-data/integration-test.db",
        "server.port=0"
})
@AutoConfigureMockMvc
class BenchIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void pageShowsChineseTitleAndFixedFixtureReplaysNonconformingSoak() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        MvcResult page = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(page.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("层合固化证据台");

        mockMvc.perform(get("/api/bench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.specVersion").value("CURE-SPEC-7.3"))
                .andExpect(jsonPath("$.run.inputSummary").isNotEmpty())
                .andExpect(jsonPath("$.evaluation.overallStatus").value("NONCONFORMING"))
                .andExpect(jsonPath("$.evaluation.rules[?(@.ruleId=='SOAK_COMMON_HOLD')].status")
                        .value(org.hamcrest.Matchers.contains("FAIL")));

        mockMvc.perform(get("/api/chart.svg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("175–185°C")));
    }

    @Test
    void anomalyConfirmationControlPointAndDeviationAreAuditable() throws Exception {
        mockMvc.perform(post("/api/sensor-events/EV-TC3-FAULT/confirm")
                        .contentType("application/json").content("""
                                {"actor":"测试复核员"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorEvents[0].confirmed").value(true))
                .andExpect(jsonPath("$.evaluation.overallStatus").value("NONCONFORMING"));

        mockMvc.perform(post("/api/control-points/CP-B/select")
                        .contentType("application/json").content("""
                                {"actor":"测试复核员"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlPoints[?(@.id=='CP-B')].selected").value(org.hamcrest.Matchers.contains(true)));

        mockMvc.perform(post("/api/deviations")
                        .contentType("application/json").content("""
                                {"ruleId":"SOAK_COMMON_HOLD","title":"共同保温不足","description":"提交工程偏差评审","actor":"测试复核员"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviations", hasSize(1)))
                .andExpect(jsonPath("$.actions[0].actionType").value("CREATE_DEVIATION_BRANCH"));
    }

    @Test
    void resetReimportsFixedFixtureAndExportRemainsAvailable() throws Exception {
        mockMvc.perform(post("/api/reset")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviations", hasSize(0)))
                .andExpect(jsonPath("$.run.fixtureVersion").value("FIX-2026.1"));
        mockMvc.perform(get("/api/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CURE-SPEC-7.3")))
                .andExpect(content().string(containsString("failureRanges")));
    }
}

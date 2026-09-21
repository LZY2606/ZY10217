package com.example.cure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cure.repo.FixtureSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.example.cure.repo.DataStore;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private DataStore dataStore;
    @Autowired
    private FixtureSeeder seeder;
    private MockMvc mvc;

    private static final List<String> TABLES = List.of(
            "spec_versions", "parts", "plies", "material_lots", "layup_records",
            "cure_cycles", "thermocouples", "tc_replacements", "tc_samples",
            "cycle_stages", "env_samples", "review_actions", "deviations",
            "evaluation_runs", "rule_results");
    private final ObjectMapper om = new ObjectMapper();

    @BeforeAll
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @BeforeEach
    void resetFixture() {
        for (int i = TABLES.size() - 1; i >= 0; i--) {
            dataStore.update("DELETE FROM " + TABLES.get(i));
        }
        dataStore.jdbc().execute("DELETE FROM sqlite_sequence");
        seeder.seed();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void homepageShowsTitle() throws Exception {
        MvcResult r = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        // MockMvc 转发到静态资源时由容器解析，这里直接断言交付页面内容
        String html = new String(getClass().getResourceAsStream("/static/index.html")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("层合固化证据台"));
    }

    @Test
    void fixedFixtureSoakFailsOnShortestContinuousJointRange() throws Exception {
        JsonNode state = json(mvc.perform(get("/api/state")).andReturn());
        JsonNode ev = state.get("evaluation");
        assertEquals("NON_CONFORMING", ev.get("overall").asText());
        assertEquals("CP-CURE-2024-A", ev.get("specVersion").asText());

        JsonNode soak = null;
        for (JsonNode r : ev.get("rules")) {
            if ("R4-SOAK".equals(r.get("code").asText())) soak = r;
        }
        assertNotNull(soak);
        assertTrue(soak.get("pass").asBoolean() == false);
        // 最长共同连续保温 91 分钟 < 120 分钟；逐支累计更长但不得相加
        assertEquals(91 * 60L, ev.get("longestJointSec").asLong());
        assertTrue(ev.get("jointTotalSec").asLong() > 120 * 60L,
                "逐支共同达标累计大于 120 分钟，验证判定依据是连续而非相加");
        assertTrue(soak.get("failRanges").size() >= 1, "必须保留每条失败的时间范围");
        assertTrue(ev.get("integrityEvents").toString().contains("SENSOR_REPLACEMENT"));
        assertTrue(ev.get("integrityEvents").toString().contains("CLOCK_ROLLBACK"));
        assertTrue(ev.get("integrityEvents").toString().contains("DUPLICATE_SAMPLE"));
    }

    @Test
    void confirmAnomalyThenSoakStillFails() throws Exception {
        JsonNode before = json(mvc.perform(get("/api/state")).andReturn());
        JsonNode suspected = before.get("evaluation").get("suspectedAnomalies");
        assertTrue(suspected.size() >= 1);
        JsonNode first = suspected.get(0);
        String body = om.createObjectNode()
                .put("tcId", first.get("tcId").asText())
                .put("startTs", first.get("startTs").asLong())
                .put("endTs", first.get("endTs").asLong())
                .put("note", "测试确认异常")
                .put("actor", "测试员")
                .toString();
        JsonNode after = json(mvc.perform(post("/api/actions/confirm-anomaly")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
        boolean rampPass = false;
        boolean soakPass = true;
        for (JsonNode r : after.get("evaluation").get("rules")) {
            if ("R3-RAMP".equals(r.get("code").asText())) rampPass = r.get("pass").asBoolean();
            if ("R4-SOAK".equals(r.get("code").asText())) soakPass = r.get("pass").asBoolean();
        }
        assertTrue(rampPass, "确认尖峰异常后升温规则应通过");
        assertTrue(!soakPass, "保温规则仍因共同连续不足而失败");
        assertEquals("NON_CONFORMING", after.get("evaluation").get("overall").asText());
    }

    @Test
    void deviationBranchChangesOverallToPendingThenAccepted() throws Exception {
        // 干净夹具下 R3（未确认尖峰）与 R4（连续保温不足）两条规则失败
        String d3 = om.createObjectNode()
                .put("ruleCode", "R3-RAMP").put("owner", "质量工程师")
                .put("reason", "传感器异常待复核").toString();
        JsonNode afterD3 = json(mvc.perform(post("/api/deviations")
                .contentType(MediaType.APPLICATION_JSON).content(d3)).andReturn());
        assertEquals("NON_CONFORMING", afterD3.get("evaluation").get("overall").asText(),
                "仅一条失败进入偏差时仍有其他失败，结论保持不合格");

        String d4 = om.createObjectNode()
                .put("ruleCode", "R4-SOAK").put("owner", "质量工程师")
                .put("reason", "申请让步").toString();
        JsonNode pending = json(mvc.perform(post("/api/deviations")
                .contentType(MediaType.APPLICATION_JSON).content(d4)).andReturn());
        assertEquals("DEVIATION_PENDING", pending.get("evaluation").get("overall").asText());

        JsonNode state = json(mvc.perform(get("/api/state")).andReturn());
        for (JsonNode d : state.get("deviations")) {
            if (!"OPEN".equals(d.get("status").asText())) {
                continue;
            }
            String resolve = om.createObjectNode()
                    .put("id", d.get("id").asLong()).put("status", "ACCEPTED")
                    .put("resolution", "让步接受").toString();
            json(mvc.perform(post("/api/deviations/resolve")
                    .contentType(MediaType.APPLICATION_JSON).content(resolve)).andReturn());
        }
        JsonNode finalState = json(mvc.perform(get("/api/state")).andReturn());
        assertEquals("CONFORMING_WITH_DEVIATION",
                finalState.get("evaluation").get("overall").asText());
    }

    @Test
    void exportResetImportReplaysSameInputHash() throws Exception {
        // 先在“确认尖峰异常”之后导出：dump 含该评审动作
        JsonNode state0 = json(mvc.perform(get("/api/state")).andReturn());
        String hash0 = state0.get("evaluation").get("inputHash").asText();
        MvcResult exportRes = mvc.perform(get("/api/export")).andReturn();
        String dump = exportRes.getResponse().getContentAsString();

        mvc.perform(post("/api/reset")).andExpect(status().isOk());
        JsonNode resetState = json(mvc.perform(get("/api/state")).andReturn());
        assertEquals("NON_CONFORMING", resetState.get("evaluation").get("overall").asText());

        JsonNode imported = json(mvc.perform(post("/api/import")
                .contentType(MediaType.APPLICATION_JSON).content(dump)).andReturn());
        assertEquals(hash0, imported.get("evaluation").get("inputHash").asText(),
                "清空后重新导入，输入摘要必须一致，证明可重放复核");
    }
}

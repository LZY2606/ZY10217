package com.example.cure.repo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class DataStore {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public DataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int namedUpdate(String sql, Map<String, Object> params) {
        SqlParameterSource source = new MapSqlParameterSource(params);
        return named.update(sql, source);
    }

    /** 按列名导出整表（导出顺序即查询顺序）。 */
    public List<Map<String, Object>> exportTable(String table) {
        List<Map<String, Object>> rows = query("SELECT * FROM " + table + " ORDER BY 1");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public void importTable(String table, List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<String> cols = new ArrayList<>(
                ((Map<String, Object>) rows.get(0)).keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(",", cols)
                + ") VALUES (:" + String.join(",:", cols) + ")";
        Map<String, Object>[] batch = new Map[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            batch[i] = (Map<String, Object>) rows.get(i);
        }
        named.batchUpdate(sql, batch);
    }
}

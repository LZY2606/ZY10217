package com.example.cure;

import java.io.File;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

@Configuration
public class SqliteConfig {

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) {
        String path = url.replace("jdbc:sqlite:", "");
        if (!path.startsWith(":memory:")) {
            File f = new File(path);
            File parent = f.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("无法创建数据库目录: " + parent);
            }
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);
        return ds;
    }
}

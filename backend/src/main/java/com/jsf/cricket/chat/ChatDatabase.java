package com.jsf.cricket.chat;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Where LLM-generated SQL runs. When {@code app.chat.db.username} is set (the {@code cricket_readonly} user
 * from docker/postgres), queries use that separate login, which PostgreSQL only allows to SELECT.
 * Otherwise they use the main connection. Either way they run in a read-only transaction.
 * (Not a Spring bean DataSource on purpose: a second DataSource bean would switch off Boot's auto-configured one.)
 */
@Slf4j
@Component
public class ChatDatabase implements DisposableBean {

    static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTx;
    private final HikariDataSource ownPool;

    public ChatDatabase(DataSource mainDataSource, PlatformTransactionManager mainTxManager,
                        @Value("${app.chat.db.url}") String url,
                        @Value("${app.chat.db.username}") String username,
                        @Value("${app.chat.db.password}") String password) {
        DataSource dataSource = mainDataSource;
        PlatformTransactionManager txManager = mainTxManager;
        if (StringUtils.hasText(username)) {
            ownPool = DataSourceBuilder.create().type(HikariDataSource.class)
                    .url(url).username(username).password(password).build();
            ownPool.setPoolName("chat-readonly");
            ownPool.setMaximumPoolSize(3);
            ownPool.setReadOnly(true);
            dataSource = ownPool;
            txManager = new DataSourceTransactionManager(ownPool);
            log.info("Chat queries run as read-only database user '{}'", username);
        } else {
            ownPool = null;
            log.info("Chat queries run on the main connection in read-only transactions "
                    + "(set CHAT_DB_USERNAME to use a read-only database user)");
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setMaxRows(MAX_ROWS);
        jdbc.setQueryTimeout(10);
        readOnlyTx = new TransactionTemplate(txManager);
        readOnlyTx.setReadOnly(true);
        readOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs a SELECT in a read-only transaction (max {@value #MAX_ROWS} rows, 10 s timeout). */
    public List<Map<String, Object>> query(String sql) {
        return readOnlyTx.execute(status -> jdbc.queryForList(sql));
    }

    /** Runs a small helper query that returns one column. */
    public <T> List<T> queryForList(String sql, Class<T> type) {
        return readOnlyTx.execute(status -> jdbc.queryForList(sql, type));
    }

    @Override
    public void destroy() {
        if (ownPool != null) ownPool.close();
    }
}

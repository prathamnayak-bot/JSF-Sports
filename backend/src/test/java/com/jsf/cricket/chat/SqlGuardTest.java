package com.jsf.cricket.chat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    @Test
    void allowsSelectAndStripsTrailingSemicolon() {
        assertThat(guard.validate("  SELECT name FROM player;  ")).isEqualTo("SELECT name FROM player");
    }

    @Test
    void allowsCteAndKeywordsInsideStringLiterals() {
        String sql = "WITH p AS (SELECT * FROM player WHERE name ILIKE '%drop; delete%') SELECT * FROM p";
        assertThat(guard.validate(sql)).isEqualTo(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM player",
            "SELECT 1; DROP TABLE player",
            "UPDATE player SET name = 'x'",
            "SELECT * FROM player -- comment",
            "SELECT pg_sleep(10)",
            "WITH x AS (DELETE FROM player RETURNING *) SELECT * FROM x",
            ""
    })
    void rejectsUnsafeQueries(String sql) {
        assertThatThrownBy(() -> guard.validate(sql)).isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void extractsSqlFromMarkdownBlock() {
        assertThat(ChatService.extractSql("Here you go:\n```sql\nSELECT 1\n```")).isEqualTo("SELECT 1");
    }
}

package com.jsf.cricket.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlNormalizerTest {

    private static final String JDBC = "jdbc:postgresql://ep-x.aws.neon.tech/neondb?sslmode=require";

    @Test
    void keepsAJdbcUrl() {
        assertThat(DatabaseUrlNormalizer.normalize(JDBC).url()).isEqualTo(JDBC);
    }

    @Test
    void removesQuotesSpacesAndAPastedName() {
        assertThat(DatabaseUrlNormalizer.normalize("  \"" + JDBC + "\"\n").url()).isEqualTo(JDBC);
        assertThat(DatabaseUrlNormalizer.normalize("DB_URL=" + JDBC).url()).isEqualTo(JDBC);
        assertThat(DatabaseUrlNormalizer.normalize("DB_URL='" + JDBC + "'").url()).isEqualTo(JDBC);
    }

    @Test
    void convertsTheNeonConnectionString() {
        var n = DatabaseUrlNormalizer.normalize(
                "postgresql://neondb_owner:p%40ss@ep-x.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertThat(n.url()).isEqualTo(JDBC);
        assertThat(n.username()).isEqualTo("neondb_owner");
        assertThat(n.password()).isEqualTo("p@ss");
    }

    @Test
    void keepsThePort() {
        assertThat(DatabaseUrlNormalizer.normalize("postgres://u:p@localhost:5433/cricket").url())
                .isEqualTo("jdbc:postgresql://localhost:5433/cricket");
    }

    @Test
    void logDescriptionHasNoSecrets() {
        assertThat(DatabaseUrlNormalizer.describe(JDBC)).isEqualTo("ep-x.aws.neon.tech/neondb");
    }
}

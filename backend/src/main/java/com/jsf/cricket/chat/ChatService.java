package com.jsf.cricket.chat;

import com.jsf.cricket.llm.LlmClient;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-to-SQL pipeline: question -> LLM writes SQL -> guard -> read-only execution -> LLM explains the rows.
 */
@Service
public class ChatService {

    static final String CANNOT_ANSWER = "CANNOT_ANSWER";
    private static final int MAX_ROWS = 200;
    private static final int ROWS_SENT_TO_LLM = 50;
    private static final Pattern SQL_BLOCK =
            Pattern.compile("```(?:sql)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final String ANSWER_SYSTEM_PROMPT = """
            You are a friendly cricket analyst. Using ONLY the result rows provided, answer the user's question
            in a short paragraph (or a few bullet points for lists), like a TV scouting summary.
            Do not invent numbers that are not in the rows. Write player names exactly as they appear in the rows
            (e.g. 'RG Sharma') - never guess what initials stand for. If the rows are empty, say no matching data was found
            and suggest how the user could rephrase.
            """;

    private final LlmClient llm;
    private final SqlGuard guard;
    private final SchemaProvider schema;
    private final ObjectMapper mapper;
    private final JdbcTemplate readOnlyJdbc;
    private final TransactionTemplate readOnlyTx;

    public ChatService(LlmClient llm, SqlGuard guard, SchemaProvider schema, ObjectMapper mapper,
                       DataSource dataSource, PlatformTransactionManager txManager) {
        this.llm = llm;
        this.guard = guard;
        this.schema = schema;
        this.mapper = mapper;
        this.readOnlyJdbc = new JdbcTemplate(dataSource);
        this.readOnlyJdbc.setMaxRows(MAX_ROWS);
        this.readOnlyJdbc.setQueryTimeout(10);
        // read-only transaction: the database itself rejects any write that slips past SqlGuard
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.readOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record ChatAnswer(String question, String sql, List<Map<String, Object>> rows, String answer) {
    }

    public ChatAnswer ask(String question) {
        String prompt = sqlSystemPrompt();
        String sql = extractSql(llm.complete(prompt, question));
        if (sql.equalsIgnoreCase(CANNOT_ANSWER)) {
            return new ChatAnswer(question, null, List.of(),
                    "Sorry, I can't answer that from the cricket data we have. Try asking about players, teams, "
                            + "matches, venues or ball-by-ball stats.");
        }
        String safeSql = guard.validate(sql);
        List<Map<String, Object>> rows;
        try {
            rows = runReadOnly(safeSql);
        } catch (DataAccessException e) {
            // one self-correction round: show the model its query and the database error
            String error = String.valueOf(e.getMostSpecificCause().getMessage());
            String retry = """
                    %s

                    Your previous query:
                    %s
                    failed with this database error:
                    %s
                    Write a corrected query.""".formatted(question, safeSql, error.substring(0, Math.min(error.length(), 500)));
            safeSql = guard.validate(extractSql(llm.complete(prompt, retry)));
            rows = runReadOnly(safeSql);
        }
        List<Map<String, Object>> sample = rows.subList(0, Math.min(rows.size(), ROWS_SENT_TO_LLM));
        String answer = llm.complete(ANSWER_SYSTEM_PROMPT, """
                Question: %s
                SQL used: %s
                Result rows (JSON, %d of %d shown): %s"""
                .formatted(question, safeSql, sample.size(), rows.size(), mapper.writeValueAsString(sample)));
        return new ChatAnswer(question, safeSql, rows, answer);
    }

    private String sqlSystemPrompt() {
        return """
                You are an expert cricket statistician who writes PostgreSQL queries.
                Translate the user's question into ONE read-only PostgreSQL SELECT query over this schema:

                %s

                Rules:
                - Output only the SQL inside a ```sql code block, nothing else.
                - Use only the tables and columns above. Never modify data.
                - Match player/team/venue names with ILIKE '%%name%%' because users type partial names.
                - "Last N matches" means the N most recent by cricket_match.match_date for that player/team.
                - Strike rate = 100.0 * runs_batter / balls faced; economy = 6.0 * runs conceded / legal balls;
                  batting average = runs / dismissals. Guard against division by zero with NULLIF.
                - Round decimals to 2 places and give result columns readable aliases.
                - Return at most 50 rows unless the user asks for more.
                - Player names are stored as initials + surname (e.g. 'V Kohli', 'MS Dhoni', 'JJ Bumrah'),
                  so match players on the surname: p.name ILIKE '%%Kohli%%'.
                - delivery has no match_id: join delivery -> innings (innings_id) -> cricket_match (match_id).
                - If the question cannot be answered from this schema, output exactly: %s

                Values present in the data:
                %s

                Examples of correct queries:

                Question: Top 5 run scorers
                ```sql
                SELECT p.name AS player, SUM(d.runs_batter) AS runs
                FROM delivery d JOIN player p ON p.id = d.batter_id
                GROUP BY p.id, p.name ORDER BY runs DESC LIMIT 5
                ```

                Question: Most wickets in IPL 2024
                ```sql
                SELECT p.name AS bowler, COUNT(*) AS wickets
                FROM delivery d
                JOIN innings i ON i.id = d.innings_id
                JOIN cricket_match m ON m.id = i.match_id
                JOIN player p ON p.id = d.bowler_id
                WHERE m.event_name = 'Indian Premier League' AND m.season = '2024'
                  AND d.wicket_kind IS NOT NULL
                  AND d.wicket_kind NOT IN ('run out', 'retired hurt', 'retired out', 'retired not out', 'obstructing the field')
                GROUP BY p.id, p.name ORDER BY wickets DESC LIMIT 10
                ```

                Question: Kohli's strike rate in the death overs
                ```sql
                SELECT p.name AS batter, SUM(d.runs_batter) AS runs,
                       COUNT(*) FILTER (WHERE d.extra_type IS DISTINCT FROM 'wides') AS balls_faced,
                       ROUND(100.0 * SUM(d.runs_batter)
                             / NULLIF(COUNT(*) FILTER (WHERE d.extra_type IS DISTINCT FROM 'wides'), 0), 2) AS strike_rate
                FROM delivery d JOIN player p ON p.id = d.batter_id
                WHERE p.name ILIKE '%%Kohli%%' AND d.over_number BETWEEN 16 AND 19
                GROUP BY p.id, p.name
                ```
                """.formatted(schema.schema(), CANNOT_ANSWER, dataHints());
    }

    /** Real values from the database so the model doesn't guess names like 'IPL' or season formats. */
    private String dataHints() {
        List<String> events = readOnlyJdbc.queryForList("""
                SELECT event_name FROM cricket_match WHERE event_name IS NOT NULL
                GROUP BY event_name ORDER BY COUNT(*) DESC LIMIT 15""", String.class);
        List<String> seasons = readOnlyJdbc.queryForList(
                "SELECT DISTINCT season FROM cricket_match WHERE season IS NOT NULL ORDER BY season DESC LIMIT 25", String.class);
        List<String> formats = readOnlyJdbc.queryForList("SELECT DISTINCT format FROM cricket_match", String.class);
        if (events.isEmpty()) return "(no matches imported yet)";
        return "- event_name: " + quoted(events) + "\n- season: " + quoted(seasons) + "\n- format: " + quoted(formats);
    }

    private static String quoted(List<String> values) {
        return String.join(", ", values.stream().map(v -> "'" + v + "'").toList());
    }

    private List<Map<String, Object>> runReadOnly(String sql) {
        return readOnlyTx.execute(status -> readOnlyJdbc.queryForList(sql));
    }

    static String extractSql(String reply) {
        if (reply == null) return "";
        Matcher m = SQL_BLOCK.matcher(reply);
        return (m.find() ? m.group(1) : reply).strip();
    }
}

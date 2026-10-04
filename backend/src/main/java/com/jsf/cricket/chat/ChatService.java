package com.jsf.cricket.chat;

import com.jsf.cricket.llm.LlmClient;
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
            Do not invent numbers that are not in the rows. If the rows are empty, say no matching data was found
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
        String sql = extractSql(llm.complete(sqlSystemPrompt(), question));
        if (sql.equalsIgnoreCase(CANNOT_ANSWER)) {
            return new ChatAnswer(question, null, List.of(),
                    "Sorry, I can't answer that from the cricket data we have. Try asking about players, teams, "
                            + "matches, venues or ball-by-ball stats.");
        }
        String safeSql = guard.validate(sql);
        List<Map<String, Object>> rows = readOnlyTx.execute(status -> readOnlyJdbc.queryForList(safeSql));
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
                - If the question cannot be answered from this schema, output exactly: %s
                """.formatted(schema.schema(), CANNOT_ANSWER);
    }

    static String extractSql(String reply) {
        if (reply == null) return "";
        Matcher m = SQL_BLOCK.matcher(reply);
        return (m.find() ? m.group(1) : reply).strip();
    }
}

package com.jsf.cricket.chat;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First line of defence for LLM-generated SQL: only a single read-only SELECT is allowed.
 * (The query is also executed inside a read-only transaction, see {@link ChatService}.)
 */
@Component
public class SqlGuard {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|merge|upsert|drop|alter|create|truncate|grant|revoke|copy|call|execute|"
                    + "vacuum|analyze|reindex|cluster|lock|set|reset|listen|notify|comment|prepare|"
                    + "pg_\\w+|lo_\\w+|dblink\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Returns the cleaned query, or throws {@link UnsafeSqlException}. */
    public String validate(String sql) {
        if (sql == null || sql.isBlank()) throw new UnsafeSqlException("Empty query");
        String q = sql.strip();
        while (q.endsWith(";")) q = q.substring(0, q.length() - 1).stripTrailing();

        String code = stripStringLiterals(q);
        if (code.contains(";")) throw new UnsafeSqlException("Only a single statement is allowed");
        if (code.contains("--") || code.contains("/*")) throw new UnsafeSqlException("SQL comments are not allowed");

        String lower = code.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            throw new UnsafeSqlException("Only SELECT queries are allowed");
        }
        Matcher m = FORBIDDEN.matcher(code);
        if (m.find()) throw new UnsafeSqlException("Keyword not allowed: " + m.group());
        return q;
    }

    /** Blanks out '...' literals so a name like 'Set' or 'D''Arcy' doesn't trip the checks. */
    private static String stripStringLiterals(String sql) {
        return sql.replaceAll("'(?:[^']|'')*'", "''");
    }
}

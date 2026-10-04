package com.jsf.cricket.common;

import com.jsf.cricket.chat.UnsafeSqlException;
import com.jsf.cricket.llm.LlmException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns pipeline failures into RFC 7807 problem responses the frontend can show. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnsafeSqlException.class)
    public ProblemDetail unsafeSql(UnsafeSqlException e) {
        return problem(HttpStatus.BAD_REQUEST, "The generated query was blocked for safety: " + e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    public ProblemDetail llm(LlmException e) {
        log.warn("LLM call failed", e);
        return problem(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail dataAccess(DataAccessException e) {
        log.warn("Query failed", e);
        return problem(HttpStatus.BAD_REQUEST,
                "The query could not be run. Try rephrasing the question. (" + e.getMostSpecificCause().getMessage() + ")");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}

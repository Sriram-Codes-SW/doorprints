package com.househunt.ai.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiExceptionHandler.class);

    @ExceptionHandler(AiUnavailableException.class)
    public ProblemDetail aiUnavailable(AiUnavailableException e) {
        // Log the cause class and message only: provider errors can echo parts of the prompt.
        var cause = e.getCause();
        log.warn("{} ({}: {})", e.getMessage(), cause == null ? "-" : cause.getClass().getSimpleName(),
                cause == null ? "-" : abbreviate(cause.getMessage()));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                e.getMessage() + ". The AI provider is unavailable or its free quota is exhausted; try again later.");
        problem.setProperty("retryable", true);
        return problem;
    }

    private static String abbreviate(String s) {
        if (s == null) return "-";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}

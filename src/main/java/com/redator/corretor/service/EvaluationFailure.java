package com.redator.corretor.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Safe public error: never includes prompts, essay text, or runtime response bodies. */
public class EvaluationFailure extends ResponseStatusException {
    private final String code;
    private Long runId;

    public EvaluationFailure(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }
    public String code() { return code; }
    public Long runId() { return runId; }
    public EvaluationFailure withRun(Long id) { runId = id; return this; }
}

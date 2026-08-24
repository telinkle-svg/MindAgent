package com.kama.mindagent.agent.tools;

public final class SqlPolicyViolationException extends RuntimeException {

    public SqlPolicyViolationException(String message) {
        super(message);
    }

    public SqlPolicyViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}

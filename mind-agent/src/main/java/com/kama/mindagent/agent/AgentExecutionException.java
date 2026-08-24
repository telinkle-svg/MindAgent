package com.kama.mindagent.agent;

final class AgentExecutionException extends RuntimeException {

    private final AgentFailureCode errorCode;

    AgentExecutionException(AgentFailureCode errorCode) {
        this(errorCode, null);
    }

    AgentExecutionException(AgentFailureCode errorCode, Throwable cause) {
        super("Agent execution failed: " + errorCode, cause);
        this.errorCode = errorCode;
    }

    AgentFailureCode getErrorCode() {
        return errorCode;
    }
}

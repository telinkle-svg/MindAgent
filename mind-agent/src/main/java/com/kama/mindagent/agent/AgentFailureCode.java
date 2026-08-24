package com.kama.mindagent.agent;

enum AgentFailureCode {
    AGENT_PROTOCOL_ERROR,
    MODEL_CALL_FAILED,
    TOOL_EXECUTION_FAILED,
    FINAL_ANSWER_MISSING,
    MAX_STEPS_EXCEEDED
}

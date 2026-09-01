package com.kama.mindagent.agent.context;

/**
 * Bounds the amount of conversation data that is sent back to the model.
 *
 * <p>The policy deliberately uses character and turn limits instead of token
 * limits. Character limits are deterministic and can be enforced before a
 * model-specific tokenizer is available.</p>
 */
public final class ContextBudgetPolicy {

    public static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 12_000;
    public static final int DEFAULT_RECENT_TURNS = 10;

    private final int maxToolResultChars;
    private final int recentTurns;

    public ContextBudgetPolicy() {
        this(DEFAULT_MAX_TOOL_RESULT_CHARS, DEFAULT_RECENT_TURNS);
    }

    public ContextBudgetPolicy(int maxToolResultChars, int recentTurns) {
        if (maxToolResultChars < 1) {
            throw new IllegalArgumentException("maxToolResultChars must be positive");
        }
        if (recentTurns < 1) {
            throw new IllegalArgumentException("recentTurns must be positive");
        }
        this.maxToolResultChars = maxToolResultChars;
        this.recentTurns = recentTurns;
    }

    public static ContextBudgetPolicy defaults() {
        return new ContextBudgetPolicy();
    }

    public int maxToolResultChars() {
        return maxToolResultChars;
    }

    public int recentTurns() {
        return recentTurns;
    }
}

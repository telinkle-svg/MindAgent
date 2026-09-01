package com.kama.mindagent.agent.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Selects a bounded, turn-aware view of chat memory for a model request.
 *
 * <p>A turn starts with a user message and includes every following assistant
 * and tool message until the next user message. Consequently, an assistant
 * tool-call message and its tool response are never separated by the sliding
 * window.</p>
 */
public final class ConversationContextAssembler {

    private final ContextBudgetPolicy policy;
    private final ToolResultTruncator toolResultTruncator;
    private final ConversationSummary summary;

    public ConversationContextAssembler() {
        this(ContextBudgetPolicy.defaults());
    }

    public ConversationContextAssembler(ContextBudgetPolicy policy) {
        this(policy, null);
    }

    public ConversationContextAssembler(ContextBudgetPolicy policy, ConversationSummary summary) {
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        this.toolResultTruncator = new ToolResultTruncator(policy.maxToolResultChars());
        this.summary = summary;
    }

    public List<Message> assemble(List<Message> messages) {
        return assembleWithStats(messages).messages();
    }

    public ConversationContextAssembler withSummary(ConversationSummary summary) {
        return new ConversationContextAssembler(policy, summary);
    }

    public ConversationSummary summary() {
        return summary;
    }

    public List<Message> omittedMessages(List<Message> messages) {
        Partition partition = partition(messages);
        int firstTurn = Math.max(0, partition.turns().size() - policy.recentTurns());
        List<Message> omitted = new ArrayList<>();
        for (int turnIndex = 0; turnIndex < firstTurn; turnIndex++) {
            omitted.addAll(partition.turns().get(turnIndex));
        }
        return List.copyOf(omitted);
    }

    public AssemblyResult assembleWithStats(List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");

        Partition partition = partition(messages);
        List<List<Message>> turns = partition.turns();
        List<Message> systemMessages = partition.systemMessages();
        int omittedTurns = Math.max(0, turns.size() - policy.recentTurns());
        int firstTurn = Math.max(0, turns.size() - policy.recentTurns());
        List<Message> assembled = new ArrayList<>(systemMessages);
        if (summary != null) {
            assembled.add(new SystemMessage("【会话摘要】\n" + summary.text()));
        }
        int truncatedToolResults = 0;
        for (int turnIndex = firstTurn; turnIndex < turns.size(); turnIndex++) {
            for (Message message : turns.get(turnIndex)) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    ToolResponseMessage bounded = toolResultTruncator.truncate(toolResponseMessage);
                    if (bounded != toolResponseMessage) {
                        truncatedToolResults++;
                    }
                    assembled.add(bounded);
                } else {
                    assembled.add(message);
                }
            }
        }
        return new AssemblyResult(
                Collections.unmodifiableList(assembled),
                omittedTurns,
                truncatedToolResults
        );
    }

    private Partition partition(List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        List<Message> systemMessages = new ArrayList<>();
        List<List<Message>> turns = new ArrayList<>();
        List<Message> currentTurn = null;
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            if (message instanceof SystemMessage) {
                systemMessages.add(message);
                continue;
            }
            if (currentTurn == null || message instanceof UserMessage) {
                if (currentTurn != null && !currentTurn.isEmpty()) {
                    turns.add(currentTurn);
                }
                currentTurn = new ArrayList<>();
            }
            currentTurn.add(message);
        }
        if (currentTurn != null && !currentTurn.isEmpty()) {
            turns.add(currentTurn);
        }
        return new Partition(List.copyOf(systemMessages), List.copyOf(turns));
    }

    private record Partition(List<Message> systemMessages, List<List<Message>> turns) {
    }

    public record AssemblyResult(
            List<Message> messages,
            int omittedTurns,
            int truncatedToolResults
    ) {
        public AssemblyResult {
            messages = List.copyOf(messages);
        }
    }
}

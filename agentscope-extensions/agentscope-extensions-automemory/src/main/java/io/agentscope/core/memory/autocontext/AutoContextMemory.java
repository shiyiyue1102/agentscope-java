package io.agentscope.core.memory.autocontext;

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.StateModuleBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * auto context memory.
 * storage for auto context.
 */
public class AutoContextMemory extends StateModuleBase implements Memory {

    /**
     * context storage,compressed message storage.
     */
    MessageStorage contextStorage;

    /**
     * history storage,uncompressed message storage.
     */
    MessageStorage historyStorage;

    /**
     * context off loader.
     */
    ContextOffLoader contextOffLoader;

    /**
     * auto context config.
     */
    AutoContextConfig autoContextConfig;

    Model model;

    public AutoContextMemory(AutoContextConfig autoContextConfig, String sessionId, Model model) {
        this.model = model;
        this.autoContextConfig = autoContextConfig;
        contextOffLoader =
                (autoContextConfig.contextOffLoader == null)
                        ? new InMemoryContextOffLoader()
                        : autoContextConfig.contextOffLoader;
        contextStorage =
                (autoContextConfig.contextStorage == null)
                        ? new InMemoryStorage(sessionId)
                        : autoContextConfig.contextStorage;
        historyStorage =
                (autoContextConfig.historyStorage == null)
                        ? new InMemoryStorage(sessionId)
                        : autoContextConfig.historyStorage;
    }

    @Override
    public void addMessage(Msg message) {
        contextStorage.addMessage(message);
        historyStorage.addMessage(message);
    }

    @Override
    public List<Msg> getMessages() {

        List<Msg> currentMessages = contextStorage.getMessages();
        // 1.check msg count
        if (currentMessages.size() < autoContextConfig.msgThreshold) {
            return new ArrayList<>(currentMessages);
        }

        // 2.compress tools invocation
        Pair<Integer, Integer> toolMsgIndices = extractToolMsgsForCompress(currentMessages);
        if (toolMsgIndices != null) {
            int startIndex = toolMsgIndices.getFirst();
            int endIndex = toolMsgIndices.getSecond();
            List<Msg> toolsMsg = new ArrayList<>();
            for (int i = startIndex; i <= endIndex; i++) {
                toolsMsg.add(currentMessages.get(i));
            }
            String uuid = null;
            if (contextOffLoader != null) {
                uuid = UUID.randomUUID().toString();
                contextOffLoader.offload(uuid, toolsMsg);
            }
            Msg toolsSummary = compressToolsInvocation(toolsMsg, uuid);

            replaceMsg(currentMessages, startIndex, endIndex, toolsSummary);
            contextStorage.clear();
            for (Msg msg : currentMessages) {
                contextStorage.addMessage(msg);
            }
        }

        return new ArrayList<>(contextStorage.getMessages());
    }

    /**
     * Replace messages in rawMessages from startIndex to endIndex (inclusive) with newMsg.
     *
     * @param rawMessages the list of messages to modify
     * @param startIndex  the start index (inclusive)
     * @param endIndex    the end index (inclusive)
     * @param newMsg      the new message to replace the range with
     */
    private void replaceMsg(List<Msg> rawMessages, int startIndex, int endIndex, Msg newMsg) {
        if (rawMessages == null || newMsg == null) {
            return;
        }

        int size = rawMessages.size();

        // Validate indices
        if (startIndex < 0 || endIndex < startIndex || startIndex >= size) {
            return;
        }

        // Ensure endIndex doesn't exceed list size
        int actualEndIndex = Math.min(endIndex, size - 1);

        // Remove messages from startIndex to endIndex (inclusive)
        // Remove from end to start to avoid index shifting issues
        for (int i = actualEndIndex; i >= startIndex; i--) {
            rawMessages.remove(i);
        }

        // Insert newMsg at startIndex position
        rawMessages.add(startIndex, newMsg);
    }

    @Override
    public void deleteMessage(int index) {
        contextStorage.deleteMessage(index);
    }

    /**
     * Extract tool messages from raw messages for compression.
     * Strategy:
     * 1. If rawMessages has less than 20 messages, return null
     * 2. Find the latest assistant message and protect it and all messages after it
     * 3. Find the oldest consecutive tool messages (more than 4 consecutive) that can be compressed
     *
     * @param rawMessages all raw messages
     * @return Pair containing startIndex and endIndex (inclusive) of compressible tool messages, or null if none found
     */
    private Pair<Integer, Integer> extractToolMsgsForCompress(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return null;
        }

        int totalSize = rawMessages.size();

        // Strategy 1: If rawMessages has less than 20 messages, return null
        if (totalSize < 20) {
            return null;
        }

        // Strategy 2: Find the latest assistant message and protect it and all messages after it
        int latestAssistantIndex = -1;
        for (int i = totalSize - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                latestAssistantIndex = i;
                break;
            }
        }

        // Determine the search boundary: we can only search messages before the latest assistant
        int searchEndIndex = (latestAssistantIndex >= 0) ? latestAssistantIndex : totalSize;

        // Strategy 3: Find the oldest consecutive tool messages (more than 4 consecutive)
        // Search from the beginning (oldest messages first) until we find a sequence
        int consecutiveCount = 0;
        int startIndex = -1;
        int endIndex = -1;

        for (int i = 0; i < searchEndIndex; i++) {
            Msg msg = rawMessages.get(i);
            if (isToolMessage(msg)) {
                if (consecutiveCount == 0) {
                    startIndex = i;
                }
                consecutiveCount++;
            } else {
                // If we found more than 4 consecutive tool messages, return their indices
                if (consecutiveCount > 4) {
                    endIndex = i - 1; // endIndex is inclusive
                    return new Pair<>(startIndex, endIndex);
                }
                // Reset counter if sequence is broken
                consecutiveCount = 0;
                startIndex = -1;
            }
        }

        // Check if there's a sequence at the end of the search range
        if (consecutiveCount > 4) {
            endIndex = searchEndIndex - 1; // endIndex is inclusive
            return new Pair<>(startIndex, endIndex);
        }

        return null;
    }

    /**
     * Check if a message is a tool-related message (tool use or tool result).
     *
     * @param msg the message to check
     * @return true if the message contains tool use or tool result blocks, or has TOOL role
     */
    private boolean isToolMessage(Msg msg) {
        if (msg == null) {
            return false;
        }
        // Check if message has TOOL role
        if (msg.getRole() == MsgRole.TOOL) {
            return true;
        }
        // Check if message contains ToolUseBlock or ToolResultBlock
        return msg.hasContentBlocks(ToolUseBlock.class)
                || msg.hasContentBlocks(ToolResultBlock.class);
    }

    /**
     * compress tools invocation.
     *
     * @param messages tools msg to compress.
     * @return
     */
    private Msg compressToolsInvocation(List<Msg> messages, String offloadUUid) {

        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("tool_compress");
        String compressContentFormat =
                "<compressed_history>%s</compressed_history>\n"
                        + "<hint> You can use this information as historical context for future"
                        + " reference in carrying out your tasks\n"
                        + ((offloadUUid != null)
                                ? "<hint> the original tools invocation is stored in the offload"
                                        + " with uuid: "
                                        + offloadUUid
                                        + ",if you need to retrieve it, please use the context"
                                        + " offload tool to get it\n"
                                : "");
        List<Msg> newMessages = new ArrayList<>();
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.DEFAULT_TOOL_COMPRESS_PROMPT_START)
                                        .build())
                        .build());
        newMessages.addAll(messages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.DEFAULT_TOOL_COMPRESS_PROMPT_END)
                                        .build())
                        .build());
        Msg block =
                model.stream(newMessages, null, options)
                        .concatMap(chunk -> processChunk(chunk, context))
                        .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                        .onErrorResume(InterruptedException.class, Mono::error)
                        .block();

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        TextBlock.builder()
                                .text(String.format(compressContentFormat, block.getTextContent()))
                                .build())
                .build();
    }

    private Mono<Msg> processChunk(ChatResponse chunk, ReasoningContext context) {
        return Mono.just(chunk).doOnNext(context::processChunk).then(Mono.empty());
    }

    @Override
    public void clear() {
        contextStorage.clear();
        historyStorage.clear();
    }
}

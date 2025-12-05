package io.agentscope.core.memory.autocontext;

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.StateModuleBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * auto context memory.
 * storage for auto context.
 */
public class AutoContextMemory extends StateModuleBase implements Memory {

    private static final Logger log = LoggerFactory.getLogger(AutoContextMemory.class);

    /**
     * working context storage[compressed,offloaded].
     */
    MemoryStorage workingMemoryStorage;

    /**
     * original storage[not compressed, not offloaded,append only].
     */
    MemoryStorage originalMemoryStorage;

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
        workingMemoryStorage =
                (autoContextConfig.contextStorage == null)
                        ? new InMemoryStorage(sessionId)
                        : autoContextConfig.contextStorage;
        originalMemoryStorage =
                (autoContextConfig.historyStorage == null)
                        ? new InMemoryStorage(sessionId)
                        : autoContextConfig.historyStorage;
    }

    @Override
    public void addMessage(Msg message) {
        workingMemoryStorage.addMessage(message);
        originalMemoryStorage.addMessage(message);
    }

    @Override
    public List<Msg> getMessages() {

        List<Msg> currentContextMessages = workingMemoryStorage.getMessages();

        boolean msgCountReached = currentContextMessages.size() >= autoContextConfig.msgThreshold;

        // 0.check msg count or token reach compress threshold
        int calculateToken = TokenCounterUtil.calculateToken(currentContextMessages);
        int thresholdToken = (int) (autoContextConfig.maxToken * autoContextConfig.tokenRatio);
        boolean tokenCounterReached =
                TokenCounterUtil.calculateToken(currentContextMessages)
                        >= autoContextConfig.maxToken * autoContextConfig.tokenRatio;
        if (!msgCountReached && !tokenCounterReached) {
            return new ArrayList<>(workingMemoryStorage.getMessages());
        }

        log.info(
                "msg count reached threshold {}, msg count {}, token reached threshold {},token"
                        + " count {},max token limit {},threshold count {}",
                msgCountReached,
                currentContextMessages.size(),
                tokenCounterReached,
                calculateToken,
                autoContextConfig.maxToken,
                thresholdToken);

        // Strategy 1.previous round-compress tools invocation
        Pair<Integer, Integer> toolMsgIndices =
                extractPrevToolMsgsForCompress(
                        currentContextMessages, autoContextConfig.getLastKeep());
        if (toolMsgIndices != null) {
            summaryToolsMessages(currentContextMessages, toolMsgIndices);
            return replaceWorkingMessage(currentContextMessages);
        }

        // Strategy 2.previous round-offloading large user/assistant(lastKeep: true)
        boolean hasOffloadedLastKeep = offloadingLargePayload(currentContextMessages, true);
        if (hasOffloadedLastKeep) {
            log.info(
                    "Offloaded large payload messages, lastKeep: true, updating working memory"
                            + " storage");
            return replaceWorkingMessage(currentContextMessages);
        }

        // Strategy 3. previous round-offloading large user/assistant(lastKeep: false)
        boolean hasOffloaded = offloadingLargePayload(currentContextMessages, false);
        if (hasOffloaded) {
            log.info(
                    "Offloaded large payload messages, lastKeep: false,  updating working memory"
                            + " storage");
            return replaceWorkingMessage(currentContextMessages);
        }

        // Strategy 4. previous round-summary all prev last assistant messages
        boolean hasSummarized = summaryPreviousRoundMessages(currentContextMessages);
        if (hasSummarized) {
            log.info("Summarized previous round messages, updating working memory storage");
            return replaceWorkingMessage(currentContextMessages);
        }

        // Strategy 5. current round summary
        boolean currentRoundSummarized = summaryCurrentRoundMessages(currentContextMessages);
        if (currentRoundSummarized) {
            log.info("Current Round Summarized, updating working memory storage");
            return replaceWorkingMessage(currentContextMessages);
        }
        return new ArrayList<>(workingMemoryStorage.getMessages());
    }

    private List<Msg> replaceWorkingMessage(List<Msg> newMessages) {
        workingMemoryStorage.clear();
        for (Msg msg : newMessages) {
            workingMemoryStorage.addMessage(msg);
        }
        return new ArrayList<>(workingMemoryStorage.getMessages());
    }

    private boolean summaryCurrentRoundMessages(List<Msg> rawMessages) {
        return true;
    }

    /**
     * Summarize current round of conversation messages.
     *
     * @param rawMessages the list of messages to process
     * @return true if summary was actually performed, false otherwise
     */
    private void summaryToolsMessages(
            List<Msg> rawMessages, Pair<Integer, Integer> toolMsgIndices) {
        int startIndex = toolMsgIndices.getFirst();
        int endIndex = toolMsgIndices.getSecond();
        log.info(
                "compress tools invocation，start index {}, end index {}, tool msg count {}",
                startIndex,
                endIndex,
                endIndex - startIndex);

        List<Msg> toolsMsg = new ArrayList<>();
        for (int i = startIndex; i <= endIndex; i++) {
            toolsMsg.add(rawMessages.get(i));
        }
        String uuid = null;
        if (contextOffLoader != null) {
            uuid = UUID.randomUUID().toString();
            contextOffLoader.offload(uuid, toolsMsg);
        }
        Msg toolsSummary = compressToolsInvocation(toolsMsg, uuid);

        MsgUtils.replaceMsg(rawMessages, startIndex, endIndex, toolsSummary);
    }

    /**
     * Summarize all previous rounds of conversation messages before the latest assistant.
     *
     * <p>This method finds the latest assistant message and summarizes all conversation rounds
     * before it. Each round consists of messages between a user message and its corresponding
     * assistant message (typically including tool calls/results and the assistant message itself).
     *
     * <p>Example transformation:
     * Before: "user1-tools-assistant1, user2-tools-assistant2, user3-tools-assistant3, user4"
     * After:  "user1-summary, user2-summary, user3-summary, user4"
     * Where each summary contains the compressed information from tools and assistant of that round.
     *
     * <p>Strategy:
     * 1. Find the latest assistant message (this is the current round, not to be summarized)
     * 2. From the beginning, find all user-assistant pairs before the latest assistant
     * 3. For each pair, summarize messages between user and assistant (including assistant message)
     * 4. Replace those messages (including assistant) with summary (process from back to front to avoid index shifting)
     *
     * @param rawMessages the list of messages to process
     * @return true if summary was actually performed, false otherwise
     */
    private boolean summaryPreviousRoundMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return false;
        }

        // Step 1: Find the latest assistant message
        int latestAssistantIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                latestAssistantIndex = i;
                break;
            }
        }

        // If no assistant message found, nothing to summarize
        if (latestAssistantIndex < 0) {
            log.info("No assistant message found, skipping summary");
            return false;
        }

        // Step 2: Find all user-assistant pairs before the latest assistant
        // We'll collect them as pairs: (userIndex, assistantIndex)
        List<Pair<Integer, Integer>> userAssistantPairs = new ArrayList<>();
        int currentUserIndex = -1;

        for (int i = 0; i < latestAssistantIndex; i++) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                currentUserIndex = i;
            } else if (msg.getRole() == MsgRole.ASSISTANT && currentUserIndex >= 0) {
                // Found a user-assistant pair
                if (i - currentUserIndex != 1) {
                    userAssistantPairs.add(new Pair<>(currentUserIndex, i));
                }

                currentUserIndex = -1; // Reset to find next pair
            }
        }

        // If no pairs found, nothing to summarize
        if (userAssistantPairs.isEmpty()) {
            log.info("No user-assistant pairs found before latest assistant, skipping summary");
            return false;
        }

        log.info(
                "Found {} user-assistant pairs to summarize before latest assistant at index {}",
                userAssistantPairs.size(),
                latestAssistantIndex);

        // Step 3: Process pairs from back to front to avoid index shifting issues
        boolean hasSummarized = false;
        for (int pairIdx = userAssistantPairs.size() - 1; pairIdx >= 0; pairIdx--) {
            Pair<Integer, Integer> pair = userAssistantPairs.get(pairIdx);
            int userIndex = pair.getFirst();
            int assistantIndex = pair.getSecond();

            // Messages to summarize: between user and assistant (inclusive of assistant)
            // This includes all messages after user (tools, etc.) and the assistant message itself
            int startIndex = userIndex + 1;
            int endIndex = assistantIndex; // Include assistant message in summary

            // If no messages between user and assistant (including assistant), skip
            if (startIndex > endIndex) {
                log.info(
                        "No messages to summarize between user at index {} and assistant at index"
                                + " {}",
                        userIndex,
                        assistantIndex);
                continue;
            }

            List<Msg> messagesToSummarize = new ArrayList<>();
            for (int i = startIndex; i <= endIndex; i++) {
                messagesToSummarize.add(rawMessages.get(i));
            }

            log.info(
                    "Summarizing round {}, start index {}, end index {} (including assistant),"
                            + " message count {}",
                    pairIdx + 1,
                    startIndex,
                    endIndex,
                    messagesToSummarize.size());

            // Step 4: Offload original messages if contextOffLoader is available
            String uuid = null;
            if (contextOffLoader != null) {
                uuid = UUID.randomUUID().toString();
                contextOffLoader.offload(uuid, messagesToSummarize);
                log.info("Offloaded messages to be summarized with uuid: {}", uuid);
            }

            // Step 5: Generate summary
            Msg summaryMsg = generateConversationSummary(messagesToSummarize, uuid);

            // Step 6: Remove the messages between user and assistant (including assistant), then
            // replace with summary
            // Since we're processing from back to front, the indices are still accurate
            // for the current pair (indices of pairs after this one have already been adjusted)

            // Remove messages from startIndex to endIndex (including assistant, from back to front
            // to avoid index shifting)
            int removedCount = endIndex - startIndex + 1;
            rawMessages.subList(startIndex, endIndex + 1).clear();

            // After removal, the position where assistant was is now: assistantIndex - removedCount
            // + 1
            // But since we removed everything including assistant, we insert summary at the
            // position after user
            int insertIndex = userIndex + 1;

            // Insert summary after user (replacing the removed messages including assistant)
            rawMessages.add(insertIndex, summaryMsg);

            log.info(
                    "Removed "
                            + removedCount
                            + " messages from index "
                            + startIndex
                            + " to "
                            + endIndex
                            + " (including assistant), inserted summary at index "
                            + insertIndex
                            + " (after user at index "
                            + userIndex
                            + ")");

            hasSummarized = true;
        }

        return hasSummarized;
    }

    /**
     * Generate a summary of conversation messages using the model.
     *
     * @param messages the messages to summarize
     * @param offloadUuid the UUID of offloaded messages (if any), null otherwise
     * @return a summary message
     */
    private Msg generateConversationSummary(List<Msg> messages, String offloadUuid) {
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("conversation_summary");

        String summaryContentFormat =
                "<conversation_summary>%s</conversation_summary>\n"
                    + "<hint> This is a summary of previous conversation rounds. You can use this"
                    + " information as historical context for future reference.\n"
                        + ((offloadUuid != null)
                                ? "<hint> The original conversation is stored in the offload "
                                        + "with working_context_offload_uuid: "
                                        + offloadUuid
                                        + ". If you need to retrieve the full conversation, please"
                                        + " use the context_reload tool with this UUID.</hint>"
                                : "");

        List<Msg> newMessages = new ArrayList<>();
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.DEFAULT_CONVERSATION_SUMMARY_PROMPT_START)
                                        .build())
                        .build());
        newMessages.addAll(messages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.DEFAULT_CONVERSATION_SUMMARY_PROMPT_END)
                                        .build())
                        .build());

        Msg block =
                model.stream(newMessages, null, options)
                        .concatMap(chunk -> processChunk(chunk, context))
                        .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                        .onErrorResume(InterruptedException.class, Mono::error)
                        .block();

        if (block != null && block.getChatUsage() != null) {
            log.info(
                    "Conversation summary completed, input tokens: {}, output tokens: {}",
                    block.getChatUsage().getInputTokens(),
                    block.getChatUsage().getOutputTokens());
        }

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        TextBlock.builder()
                                .text(String.format(summaryContentFormat, block.getTextContent()))
                                .build())
                .build();
    }

    /**
     * Offload large payload messages that exceed the threshold.
     *
     * <p>This method finds messages before the latest assistant response that exceed
     * the largePayloadThreshold, offloads them to storage, and replaces them with
     * a summary containing the first 100 characters and a hint to reload if needed.
     *
     * @param rawMessages the list of messages to process
     * @param lastKeep whether to keep the last N messages (unused in current implementation)
     * @return true if any messages were offloaded, false otherwise
     */
    private boolean offloadingLargePayload(List<Msg> rawMessages, boolean lastKeep) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return false;
        }

        // Strategy 1: If rawMessages has less than lastKeep messages, skip
        if (rawMessages.size() < autoContextConfig.getLastKeep()) {
            return false;
        }

        // Strategy 2: Find the latest assistant message and protect it and all messages after it
        int latestAssistantIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                latestAssistantIndex = i;
                break;
            }
        }

        // If no assistant message found, protect the last N messages
        int searchEndIndex =
                (latestAssistantIndex >= 0)
                        ? latestAssistantIndex
                        : Math.max(0, rawMessages.size() - autoContextConfig.getLastKeep());

        boolean hasOffloaded = false;
        long threshold = autoContextConfig.largePayloadThreshold;

        // Process messages from the beginning up to the search end index
        // Process in reverse order to avoid index shifting issues when replacing
        for (int i = searchEndIndex - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            String textContent = msg.getTextContent();

            // Check if message content exceeds threshold
            if (textContent != null && textContent.length() > threshold) {
                // Offload the original message
                String uuid = UUID.randomUUID().toString();
                if (contextOffLoader != null) {
                    List<Msg> offloadMsg = new ArrayList<>();
                    offloadMsg.add(msg);
                    contextOffLoader.offload(uuid, offloadMsg);
                    log.info(
                            "Offloaded large payload message at index "
                                    + i
                                    + ", size: "
                                    + textContent.length()
                                    + " chars, uuid: "
                                    + uuid);
                }

                // Create replacement message with first 100 characters and offload hint
                String preview =
                        textContent.length() > 100
                                ? textContent.substring(0, autoContextConfig.offloadSignlePreview)
                                        + "..."
                                : textContent;

                String offloadHint =
                        String.format(
                                "%s\n"
                                    + "<hint> This message content has been offloaded due to large"
                                    + " size. The original content is stored with"
                                    + " working_context_offload_uuid: %s. If you need to retrieve"
                                    + " the full content, please use the context_reload tool with"
                                    + " this UUID.</hint>",
                                preview, uuid);

                // Create replacement message preserving original role and name
                Msg replacementMsg =
                        Msg.builder()
                                .role(msg.getRole())
                                .name(msg.getName())
                                .content(TextBlock.builder().text(offloadHint).build())
                                .build();

                // Replace the original message
                rawMessages.set(i, replacementMsg);
                hasOffloaded = true;
            }
        }

        return hasOffloaded;
    }

    @Override
    public void deleteMessage(int index) {
        workingMemoryStorage.deleteMessage(index);
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
    private Pair<Integer, Integer> extractPrevToolMsgsForCompress(
            List<Msg> rawMessages, int lastKeep) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return null;
        }

        int totalSize = rawMessages.size();

        // Step 1: If rawMessages has less than 20 messages, return null
        if (totalSize < lastKeep) {
            return null;
        }

        // Step 2: Find the latest assistant message and protect it and all messages after it
        int latestAssistantIndex = -1;
        for (int i = totalSize - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                latestAssistantIndex = i;
                break;
            }
        }

        // Determine the search boundary: we can only search messages before the latest assistant
        int searchEndIndex =
                (latestAssistantIndex >= 0) ? latestAssistantIndex : (totalSize - lastKeep);

        // Step 3: Find the oldest consecutive tool messages (more than 4 consecutive)
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
                                        + " with working_context_offload_uuid: "
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
        if (block != null && block.getChatUsage() != null) {
            System.out.printf(
                    "compress tools invocation completed,input token %s,output token %s%n",
                    block.getChatUsage().getInputTokens(), block.getChatUsage().getOutputTokens());
        }
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
        workingMemoryStorage.clear();
        originalMemoryStorage.clear();
    }
}

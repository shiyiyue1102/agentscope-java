package io.agentscope.core.memory.autocontext;

public class Prompts {

    public static final String DEFAULT_TOOL_COMPRESS_PROMPT_START =
            "Please intelligently compress and summarize the following tool invocation history";
    public static final String DEFAULT_TOOL_COMPRESS_PROMPT_END =
            "Above is a history of tool invocations. \n"
                + "Please intelligently compress and summarize the following tool invocation"
                + " history:\n"
                + "    Summarize the tool responses while preserving key invocation details,"
                + " including the tool name, its purpose, and its output.\n"
                + "    For repeated calls to the same tool, consolidate the different parameters"
                + " and results, highlighting essential variations and outcomes.";

    public static final String DEFAULT_CONVERSATION_SUMMARY_PROMPT_START =
            "Please intelligently summarize the following conversation history. Preserve key"
                    + " information, decisions, and context that would be important for future"
                    + " reference.";

    public static final String DEFAULT_CONVERSATION_SUMMARY_PROMPT_END =
            "Above is a conversation history. \n"
                    + "Please provide a concise summary that:\n"
                    + "    - Preserves important decisions, conclusions, and key information\n"
                    + "    - Maintains context that would be needed for future interactions\n"
                    + "    - Consolidates repeated or similar information\n"
                    + "    - Highlights any important outcomes or results";
}

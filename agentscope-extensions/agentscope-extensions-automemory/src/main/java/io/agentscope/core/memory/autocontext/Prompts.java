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
}

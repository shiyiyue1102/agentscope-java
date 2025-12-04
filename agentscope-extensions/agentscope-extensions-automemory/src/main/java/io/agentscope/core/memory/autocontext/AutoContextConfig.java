package io.agentscope.core.memory.autocontext;

public class AutoContextConfig {

    MessageStorage contextStorage;

    MessageStorage historyStorage;

    ContextOffLoader contextOffLoader;

    double maxToken = 128 * 1024;

    double tokenRatio = 0.75;

    int msgThreshold = 100;

    int sessionId;
}

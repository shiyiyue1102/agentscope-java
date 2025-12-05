package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.memory.autocontext.LocalFileContextOffLoader;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import java.util.Scanner;
import java.util.UUID;

/**
 * auto memory example
 */
public class AutoMemoryExample {

    public static void main(String[] args) {

        StudioManager.init()
                .studioUrl("http://localhost:3001")
                .project("JavaExamples")
                .runName("studio_demo_" + System.currentTimeMillis())
                .initialize()
                .block();
        System.out.println("✓ Connected to Studio\n");
        String sessionId = UUID.randomUUID().toString();
        String baseDir = "/Users/nov11/github/aiagent";
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder()
                        .apiKey("sk-adf484b62c264980b2688f7b37324582")
                        .modelName("qwen-plus")
                        .stream(true)
                        .enableThinking(true)
                        .formatter(new DashScopeChatFormatter())
                        .defaultOptions(GenerateOptions.builder().thinkingBudget(1024).build())
                        .build();

        Mem0LongTermMemory.Builder builder =
                Mem0LongTermMemory.builder()
                        .apiKey("m0-N8hNa84HS1A3iOXfymOM9L6lYt3d4ErjlGHrhYto")
                        .userId("shiyiyue1102")
                        .apiBaseUrl("https://api.mem0.ai");
        Mem0LongTermMemory longTermMemory = builder.build();
        AutoContextConfig autoContextConfig = new AutoContextConfig();
        autoContextConfig.setContextOffLoader(new LocalFileContextOffLoader(baseDir));
        autoContextConfig.setMsgThreshold(10);
        autoContextConfig.setLastKeep(10);
        Memory memory = new AutoContextMemory(autoContextConfig, sessionId, chatModel);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());
        toolkit.registerTool(new ContextOffloadTool(autoContextConfig.getContextOffLoader()));
        // Create Agent with minimal configuration
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful AI assistant. Be friendly and concise.Response"
                                        + " to user using the language that user asks.")
                        .model(chatModel)
                        .memory(memory)
                        .longTermMemory(longTermMemory)
                        .enablePlan()
                        .toolkit(toolkit)
                        .build();

        Scanner scanner = new Scanner(System.in);
        System.out.println("🚀 Auto Memory Example Started!");
        System.out.println("Enter your query (type 'exit' to quit):\n");

        while (true) {
            System.out.print("You: ");
            String query = scanner.nextLine().trim();

            // Check if user wants to exit
            if ("exit".equalsIgnoreCase(query)) {
                System.out.println("👋 Goodbye!");
                break;
            }

            // Skip empty input
            if (query.isEmpty()) {
                System.out.println("Please enter a valid query.\n");
                continue;
            }

            // Create user message
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(query).build())
                            .build();

            // Call agent and get response
            System.out.println("\n🤔 Processing...\n");
            Msg response = agent.call(userMsg).block();

            // Output response
            System.out.println("Assistant: " + response.getTextContent() + "\n");
        }

        scanner.close();
    }
}

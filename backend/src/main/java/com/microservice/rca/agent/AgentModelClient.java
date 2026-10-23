package com.microservice.rca.agent;

/**
 * 模型客户端抽象。生产实现调用 DeepSeek，测试使用可编排的 Mock 实现。
 * 该接口不接收故障事件预设字段，调用方只能传入白名单提示词。
 */
public interface AgentModelClient {

    String modelName();

    boolean isConfigured();

    ModelResponse complete(ModelRequest request);

    record ModelRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
    }

    record ModelResponse(
            String content,
            String model,
            int promptTokens,
            int completionTokens,
            boolean usageAvailable) {

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}

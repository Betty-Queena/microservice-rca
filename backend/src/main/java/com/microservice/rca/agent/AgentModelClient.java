package com.microservice.rca.agent;

import java.time.Duration;

/**
 * 模型客户端抽象。生产实现调用 DeepSeek，测试使用可编排的 Mock 实现。
 * 该接口不接收故障事件预设字段，调用方只能传入白名单提示词。
 */
public interface AgentModelClient {

    String modelName();

    boolean isConfigured();

    /**
     * 不可用原因。实现方可以区分“未配置 Key”和“总开关关闭”，
     * 调用方直接展示该原因，避免把禁用误报成 Key 缺失。
     */
    default String unavailabilityReason() {
        return "未配置 DEEPSEEK_API_KEY，Agent 未调用模型，也未生成任何诊断结论";
    }

    ModelResponse complete(ModelRequest request);

    /**
     * timeout 是本轮调用允许消耗的最大时间，不得超过 Agent 整体剩余预算。
     * 为 null 时使用客户端默认超时配置。
     */
    record ModelRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens,
                        Duration timeout) {

        public ModelRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            this(systemPrompt, userPrompt, temperature, maxTokens, null);
        }
    }

    record ModelResponse(
            String content,
            String model,
            int promptTokens,
            int completionTokens,
            boolean usageAvailable,
            String finishReason) {

        public ModelResponse(String content,
                             String model,
                             int promptTokens,
                             int completionTokens,
                             boolean usageAvailable) {
            this(content, model, promptTokens, completionTokens, usageAvailable, null);
        }

        public int totalTokens() {
            return promptTokens + completionTokens;
        }

        public boolean lengthTruncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }
}

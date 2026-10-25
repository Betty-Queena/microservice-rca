package com.microservice.rca.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行参数。API Key 只从环境变量注入，不写入日志或数据库。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(boolean llmEnabled, DeepSeek deepseek, Limits limits) {

    public AgentProperties {
        if (deepseek == null) {
            deepseek = new DeepSeek("", "https://api.deepseek.com", "deepseek-flash",
                    0.1, 1600, 5000, 60000);
        }
        if (limits == null) {
            limits = new Limits(6, 6, 12000, 60000, 6000, 180);
        }
    }

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            int maxTokens,
            int connectTimeoutMs,
            int readTimeoutMs) {

        public DeepSeek {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl;
            model = model == null || model.isBlank() ? "deepseek-flash" : model;
            maxTokens = maxTokens <= 0 ? 1600 : maxTokens;
            connectTimeoutMs = connectTimeoutMs <= 0 ? 5000 : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs <= 0 ? 60000 : readTimeoutMs;
        }

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Limits(
            int maxModelCalls,
            int maxToolCalls,
            int maxOutputChars,
            int maxPromptChars,
            int maxToolResultChars,
            int timeoutSeconds) {

        public Limits {
            maxModelCalls = maxModelCalls <= 0 ? 6 : Math.min(maxModelCalls, 12);
            maxToolCalls = maxToolCalls <= 0 ? 6 : Math.min(maxToolCalls, 12);
            maxOutputChars = maxOutputChars <= 0 ? 12000 : Math.min(maxOutputChars, 50000);
            maxPromptChars = maxPromptChars <= 0 ? 60000 : Math.min(maxPromptChars, 120000);
            maxToolResultChars = maxToolResultChars <= 0 ? 6000 : Math.min(maxToolResultChars, 30000);
            timeoutSeconds = timeoutSeconds <= 0 ? 180 : Math.min(timeoutSeconds, 600);
        }
    }
}

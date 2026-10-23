package com.microservice.rca.agent;

/**
 * 模型输出、工具参数或最终报告未通过严格校验。
 * 该异常会作为反馈进入下一轮模型决策，不会被当作工具成功或诊断结论。
 */
public class AgentValidationException extends RuntimeException {

    public AgentValidationException(String message) {
        super(message);
    }
}

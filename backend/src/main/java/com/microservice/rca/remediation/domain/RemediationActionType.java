package com.microservice.rca.remediation.domain;

/**
 * 白名单处置动作。当前版本只允许关闭本次实验对应的故障注入，
 * 不接受自由文本动作、Shell、SQL 或任意 HTTP 地址。
 */
public enum RemediationActionType {

    DISABLE_EXPERIMENT_FAULT;

    /**
     * 动作目标服务由服务端定义，不从请求参数读取。
     */
    public String targetService() {
        return "inventory-service";
    }
}

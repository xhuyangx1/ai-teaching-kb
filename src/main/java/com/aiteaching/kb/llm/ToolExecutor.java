package com.aiteaching.kb.llm;

/**
 * 工具执行接缝：plugin 包的 PluginExecutor 实现本接口（含调用轮次上限与超时）。
 *
 * <p>t4 阶段插件模块尚未落地，ChatService 用 {@code ObjectProvider<ToolExecutor>} 可选注入，
 * 没有实现时跳过工具轮次，RAG 链路仍然完整可跑。</p>
 */
public interface ToolExecutor {

    /** 执行工具，返回文本结果（失败时返回错误文本交给模型自愈）。 */
    String execute(String toolName, String argumentsJson);
}

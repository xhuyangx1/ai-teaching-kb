package com.aiteaching.kb.llm;

import java.util.List;

/**
 * 工具目录接缝：plugin 包的 ToolRegistry 实现本接口，向对话模型暴露可用工具。
 */
public interface ToolCatalog {

    List<ToolSpec> list();

    default boolean isEmpty() {
        List<ToolSpec> all = list();
        return all == null || all.isEmpty();
    }
}

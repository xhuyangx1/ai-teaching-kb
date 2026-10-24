package com.aiteaching.kb.llm;

/**
 * 工具描述（与具体插件实现解耦的中立 DTO）。
 *
 * <p>plugin 包的 ToolRegistry 把自己的 ToolMeta 转换成 ToolSpec 传给 KbChatModel；
 * llm 包因此不依赖 plugin 包，impl-glm 可以独立实现插件模块。</p>
 */
public class ToolSpec {

    private String name;
    private String description;
    /** 简化版 JSON Schema（参数名→类型/说明），OpenAI Function Calling 与 mock 规则共用。 */
    private String parameterSchema;

    public ToolSpec() {
    }

    public ToolSpec(String name, String description, String parameterSchema) {
        this.name = name;
        this.description = description;
        this.parameterSchema = parameterSchema;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getParameterSchema() { return parameterSchema; }
    public void setParameterSchema(String parameterSchema) { this.parameterSchema = parameterSchema; }
}

package com.aiteaching.kb.llm;

/** 模型发起的一次工具调用请求。 */
public class ToolCall {

    private String id;
    private String name;
    /** 参数 JSON 字符串 */
    private String arguments;

    public ToolCall() {
    }

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
}

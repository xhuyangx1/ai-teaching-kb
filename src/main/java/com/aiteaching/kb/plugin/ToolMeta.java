package com.aiteaching.kb.plugin;

import com.aiteaching.kb.llm.ToolSpec;

import java.lang.reflect.Method;

/**
 * 注册表内部条目：工具元信息 + 反射句柄（实例与方法），对外只暴露 {@link ToolSpec}。
 */
public class ToolMeta {

    private final String name;
    private final String description;
    private final String parameterSchema;
    private final Object instance;
    private final Method method;
    private final boolean takesArgument;

    public ToolMeta(String name, String description, String parameterSchema,
                    Object instance, Method method, boolean takesArgument) {
        this.name = name;
        this.description = description;
        this.parameterSchema = parameterSchema;
        this.instance = instance;
        this.method = method;
        this.takesArgument = takesArgument;
    }

    /** 反射调用插件方法。 */
    public String invoke(String argumentsJson) throws Exception {
        Object result = takesArgument ? method.invoke(instance, argumentsJson) : method.invoke(instance);
        return result == null ? "" : result.toString();
    }

    /** 转成模型可见的中立 ToolSpec（llm 包不依赖 plugin 包）。 */
    public ToolSpec toSpec() {
        return new ToolSpec(name, description, parameterSchema);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getParameterSchema() { return parameterSchema; }
}

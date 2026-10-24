package com.aiteaching.kb.plugin;

import com.aiteaching.kb.llm.ToolCatalog;
import com.aiteaching.kb.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（Map 结构 + 反射调用）：启动时扫描所有 {@link ToolPlugin} 组件上
 * 标注了 {@link Tool} 的 public 方法，注册为 {@link ToolMeta}。
 *
 * <p>同时实现 {@code llm.ToolCatalog}，向对话模型暴露可用工具——llm 包依赖本接口
 * 而非本类，依赖方向保持 plugin → llm 单向。</p>
 */
@Component
public class ToolRegistry implements ToolCatalog, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext context;
    private final Map<String, ToolMeta> tools = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterPropertiesSet() {
        for (Object bean : context.getBeansWithAnnotation(ToolPlugin.class).values()) {
            register(bean);
        }
        log.info("插件注册完成，共 {} 个工具: {}", tools.size(), tools.keySet());
    }

    /** 扫描一个插件 Bean 的 public @Tool 方法（重复 name 后注册者覆盖并 WARN）。 */
    public void register(Object bean) {
        // 用 AopUtils.getTargetClass 而不是 bean.getClass()：插件 Bean 一旦被 AOP 代理
        // （加上 @Transactional/@Async/@Validated 等都会产生 CGLIB 子类），子类方法上不携带
        // 原注解，会导致插件静默消失（注册数为 0 却没有任何报错）。
        Class<?> type = AopUtils.getTargetClass(bean);
        for (Method m : type.getMethods()) {
            Tool t = m.getAnnotation(Tool.class);
            if (t == null) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            boolean takesArgument;
            if (params.length == 0) {
                takesArgument = false;
            } else if (params.length == 1 && params[0] == String.class) {
                takesArgument = true;
            } else {
                log.warn("插件方法 {}.{} 参数不合法（轻量版只支持 0/1 个 String 参数），跳过注册",
                        type.getSimpleName(), m.getName());
                continue;
            }
            if (tools.put(t.name(), new ToolMeta(t.name(), t.description(), t.parameterSchema(), bean, m, takesArgument)) != null) {
                log.warn("工具名重复: {}（后者覆盖前者）", t.name());
            }
        }
    }

    @Override
    public List<ToolSpec> list() {
        List<ToolSpec> out = new ArrayList<>(tools.size());
        for (ToolMeta meta : tools.values()) {
            out.add(meta.toSpec());
        }
        return out;
    }

    public ToolMeta get(String name) {
        return name == null ? null : tools.get(name);
    }

    public int size() {
        return tools.size();
    }

    public Collection<ToolMeta> all() {
        return Collections.unmodifiableCollection(tools.values());
    }
}

package com.aiteaching.kb.plugin;

import com.aiteaching.kb.llm.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 插件执行器：把 ToolRegistry 里的条目真正跑起来。
 *
 * <p>两条防线（追问链 #62/#63）：</p>
 * <ul>
 *   <li><b>单次超时 2s</b>：工具在独立线程池执行，{@code Future.get(2s)} 到点取消，
 *       超时/异常都把错误文本当工具结果回传给模型自愈，而不是把异常抛进主链路；</li>
 *   <li><b>轮次上限 3</b>：由 ChatService 的调用循环兜住（MAX_TOOL_ROUNDS），
 *       即使模型反复请求工具也不会死循环。</li>
 * </ul>
 */
@Component
public class PluginExecutorImpl implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PluginExecutorImpl.class);
    /** 单次工具执行超时（毫秒）。 */
    static final long TIMEOUT_MS = 2000;

    private final ToolRegistry registry;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kb-plugin-exec");
        t.setDaemon(true);
        return t;
    });

    public PluginExecutorImpl(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String execute(String toolName, String argumentsJson) {
        ToolMeta meta = registry.get(toolName);
        if (meta == null) {
            return "未知工具: " + toolName;
        }
        Future<String> future = pool.submit(() -> meta.invoke(argumentsJson));
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时(>{}ms) tool={}", TIMEOUT_MS, toolName);
            return "工具执行超时（>" + TIMEOUT_MS + "ms），请换一种问法";
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("工具执行失败 tool={}: {}", toolName, cause.toString());
            return "工具执行失败: " + cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "工具执行被中断";
        }
    }
}

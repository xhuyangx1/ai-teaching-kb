package com.aiteaching.kb.plugin;

import com.aiteaching.kb.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件清单接口：{@code GET /api/plugins} 返回注册表中全部工具元信息（验收 #8）。
 */
@RestController
@RequestMapping("/api")
public class PluginController {

    private final ToolRegistry registry;

    public PluginController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/plugins")
    public ApiResponse<Map<String, Object>> list() {
        List<Map<String, String>> plugins = new ArrayList<>();
        for (ToolMeta meta : registry.all()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", meta.getName());
            item.put("description", meta.getDescription());
            item.put("parameterSchema", meta.getParameterSchema());
            plugins.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", plugins.size());
        data.put("plugins", plugins);
        return ApiResponse.ok(data);
    }
}

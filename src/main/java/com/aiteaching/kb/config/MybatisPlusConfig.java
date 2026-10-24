package com.aiteaching.kb.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus：仅做 Mapper 扫描（无分页/多租户需求，保持最小依赖）。
 */
@Configuration
@MapperScan("com.aiteaching.kb.mapper")
public class MybatisPlusConfig {
}

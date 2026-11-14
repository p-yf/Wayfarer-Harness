package com.yf.wayfarer.change;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 变更记录的装配。
 *
 * @author yyf
 * @description 按配置创建影子仓库缓存、工具调用记录器与变更查询/回退服务
 */
@Configuration
@EnableConfigurationProperties(ChangeProperties.class)
public class ChangeConfiguration {

    @Bean
    public ShadowRepositoryStore shadowRepositoryStore(ChangeProperties properties) {
        return new ShadowRepositoryStore(Path.of(properties.getRoot()), properties.getExclude(),
                properties.isEnabled());
    }

    @Bean
    public ChangeRecorder changeRecorder(ShadowRepositoryStore store, ChangeService changeService) {
        // 记录器在轮次结束时用查询服务汇总本轮变更，供 SSE 推送
        return new ChangeRecorder(store, changeService);
    }

    @Bean
    public ChangeService changeService(ShadowRepositoryStore store) {
        return new ChangeService(store);
    }
}

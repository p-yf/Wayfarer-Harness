package com.yf.wayfarer.web;

import com.yf.wayfarer.session.JsonlSession;
import com.yf.wayfarer.session.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * web 层装配。
 *
 * @author yyf
 * @description 按配置创建会话存储
 */
@Configuration
public class SessionConfiguration {

    @Bean
    public Session session(@Value("${wayfarer.session.root:./sessions}") String root,
                           @Value("${wayfarer.session.compact-threshold:0}") int compactThreshold) {
        return new JsonlSession(Path.of(root), compactThreshold);
    }
}

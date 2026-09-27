package cn.xiaofuge.dsh.log.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LogLensProperties.class)
public class ApplicationConfiguration {
}

package cn.xiaofuge.dsh.log.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.log")
public record LogLensProperties(
        String dataDirectory,
        int commandTimeoutSeconds,
        int maxCommits,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}

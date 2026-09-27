package cn.xiaofuge.dsh.log.model;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeRequest(
        @NotBlank String path
) {
}

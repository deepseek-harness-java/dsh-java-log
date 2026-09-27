package cn.xiaofuge.dsh.log.controller;

import cn.xiaofuge.dsh.log.config.LogLensProperties;
import cn.xiaofuge.dsh.log.model.AnalyzeRequest;
import cn.xiaofuge.dsh.log.model.ApiException;
import cn.xiaofuge.dsh.log.service.HarnessAiStreamService;
import cn.xiaofuge.dsh.log.service.LogAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
public class LogLensController {
    private final LogAnalysisService analysis;
    private final HarnessAiStreamService harnessAiStreamService;
    private final LogLensProperties properties;

    public LogLensController(LogAnalysisService analysis,
                             HarnessAiStreamService harnessAiStreamService,
                             LogLensProperties properties) {
        this.analysis = analysis;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    @PostMapping("/api/log/stats")
    public Map<String, Object> stats(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.stats(analysis.resolveFiles(request.path()));
    }

    @PostMapping("/api/log/errors")
    public Map<String, Object> errors(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.scanErrors(analysis.resolveFiles(request.path()), 30);
    }

    @PostMapping("/api/log/stacks")
    public Map<String, Object> stacks(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.clusterStacks(analysis.resolveFiles(request.path()));
    }

    @PostMapping("/api/log/timeline")
    public Map<String, Object> timeline(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.timeline(analysis.resolveFiles(request.path()));
    }

    @PostMapping("/api/log/tail")
    public Map<String, Object> tail(@Valid @RequestBody AnalyzeRequest request) {
        return analysis.tail(analysis.resolveFiles(request.path()), 80);
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        String logPath = request.getOrDefault("logPath", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(logPath, message));
    }

    private String buildPrompt(String logPath, String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Log Lens（日志文件猎人，只读分析工具）\n");
        if (!logPath.isBlank()) {
            prompt.append("- 当前日志路径：").append(logPath).append("\n");
        }
        prompt.append("- 你可以使用以下只读日志工具获取真实数据（回答涉及错误、堆栈、时间线、级别统计的问题时，必须先调用工具查证，禁止凭空猜测）：\n");
        prompt.append("  - plugin__dsh-java-log-plugin__log_scan_errors(logPath)：错误行扫描 + 按指纹聚类的 Top 错误模式\n");
        prompt.append("  - plugin__dsh-java-log-plugin__log_cluster_stack(logPath)：堆栈异常类聚类 + 业务热帧\n");
        prompt.append("  - plugin__dsh-java-log-plugin__log_timeline(logPath)：第一个 ERROR 前后事件时间线（根因定位入口）\n");
        prompt.append("  - plugin__dsh-java-log-plugin__log_stats_by_level(logPath)：级别统计（TRACE~FATAL 行数、时间范围）\n");
        prompt.append("  - plugin__dsh-java-log-plugin__log_tail_watch(logPath, lines)：日志尾部最新内容\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 根因分析先给时间线证据链（哪些事件先后发生），再给结论，避免跳跃。\n");
        prompt.append("- 引用工具结果时给出关键数字与结论，不要原样转储大段 JSON。\n");
        prompt.append("- 本产品为只读工具：不修改日志文件、不建议把日志内容外发到第三方系统。\n");
        return prompt.toString();
    }
}

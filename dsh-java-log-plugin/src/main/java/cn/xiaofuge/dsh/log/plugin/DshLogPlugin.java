package cn.xiaofuge.dsh.log.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshLogPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-log-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshLogPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ScanErrorsTool(),
                new ClusterStackTool(),
                new TimelineTool(),
                new StatsByLevelTool(),
                new TailWatchTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("log-lens-capabilities", 20, """
                ## DSH Log Lens Plugin
                - 所有日志工具均为只读分析，不修改日志文件、不外发日志内容。
                - logPath 一般由对话上下文直接给出（本地文件或目录绝对路径）；未知时提示用户在页面先填写日志路径。
                - 回答错误分布、堆栈根因、级别统计、最新日志等问题前，必须先调用对应工具查证真实结果，禁止编造。
                - 定位根因时推荐顺序：log_timeline（第一个 ERROR 前后事件）→ log_cluster_stack（异常类聚类）→ log_scan_errors（Top 错误模式）。
                - 汇报现状或"现在还好吗"类问题用 log_stats_by_level + log_tail_watch。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__log_")) {
                return PluginHookResult.context("DSH Log Lens audit: read-only analysis tool call.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_LOG_ADMIN_URL", "http://127.0.0.1:8093")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String pathJson(Map<String, Object> args, String key) {
        return "{\"path\":\"" + json(str(args, key)) + "\"}";
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private class ScanErrorsTool extends AbstractTool {
        @Override public String name() { return "log_scan_errors"; }
        @Override public String description() { return "扫描日志文件中的 ERROR/FATAL 行，按消息模板+堆栈顶层指纹聚类，返回 Top 错误模式与样本。回答'出了什么错/错误分布'时必须先调用本工具。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("logPath", stringSchema("日志文件或目录的本地绝对路径（目录会递归找 .log/.out/.txt）"))
                    .prop("adminBaseUrl", stringSchema("可选，Log Lens 后台地址，默认 http://127.0.0.1:8093"))
                    .required("logPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/log/errors", pathJson(args, "logPath"), args));
        }
    }

    private class ClusterStackTool extends AbstractTool {
        @Override public String name() { return "log_cluster_stack"; }
        @Override public String description() { return "聚类日志中的异常堆栈：Caused by 异常类计数 + 业务代码热帧 Top 10。分析根因归属（哪个模块/类在抛异常）时调用本工具。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("logPath", stringSchema("日志文件或目录的本地绝对路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8093"))
                    .required("logPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/log/stacks", pathJson(args, "logPath"), args));
        }
    }

    private class TimelineTool extends AbstractTool {
        @Override public String name() { return "log_timeline"; }
        @Override public String description() { return "定位第一个 ERROR/FATAL 出现的文件与行号，返回前后各 5 行上下文事件。根因分析的时间线入口，先调用本工具看错误发生前发生了什么。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("logPath", stringSchema("日志文件或目录的本地绝对路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8093"))
                    .required("logPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/log/timeline", pathJson(args, "logPath"), args));
        }
    }

    private class StatsByLevelTool extends AbstractTool {
        @Override public String name() { return "log_stats_by_level"; }
        @Override public String description() { return "统计日志级别分布：TRACE/DEBUG/INFO/WARN/ERROR/FATAL 行数、总行数、时间范围。回答'日志整体健康度/错误占比'时调用本工具。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("logPath", stringSchema("日志文件或目录的本地绝对路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8093"))
                    .required("logPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/log/stats", pathJson(args, "logPath"), args));
        }
    }

    private class TailWatchTool extends AbstractTool {
        @Override public String name() { return "log_tail_watch"; }
        @Override public String description() { return "读取日志文件最后 N 行（默认 80，最大 500）。回答'最新日志在说什么/刚刚发生了什么'时调用本工具。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("logPath", stringSchema("日志文件的本地绝对路径（取第一个匹配文件）"))
                    .prop("lines", stringSchema("可选，读取行数，默认 80"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8093"))
                    .required("logPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/log/tail", pathJson(args, "logPath"), args));
        }
    }
}

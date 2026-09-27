package cn.xiaofuge.dsh.log.service;

import cn.xiaofuge.dsh.log.config.LogLensProperties;
import cn.xiaofuge.dsh.log.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 日志文件猎人：只读解析本地日志文件/目录，做错误扫描、堆栈聚类、时间线、级别统计。
 * 绝不修改日志文件，绝不外发内容。
 */
@Service
public class LogAnalysisService {
    private static final Pattern LEVEL = Pattern.compile(
            "\\b(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\b|\\[(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]\\d{3,})");
    private static final Pattern STACK_AT = Pattern.compile(
            "\\s+(?:at\\s+)([\\w.$]+)\\.([\\w$<>]+)\\(([\\w.$]+)(?::(\\d+))?\\)");
    private static final Pattern CAUSED_BY = Pattern.compile("Caused by:\\s*(.+)");
    private static final int READ_LIMIT = 8_000_000; // 单次最多读 8MB

    private final LogLensProperties properties;

    public LogAnalysisService(LogLensProperties properties) {
        this.properties = properties;
    }

    /** 解析 path 参数：支持单文件或目录（目录取 *.log/*.out/*.txt 拼接视图）。 */
    public List<Path> resolveFiles(String raw) {
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "路径不存在：" + path);
        }
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            files.add(path);
        } else {
            try (Stream<Path> walk = Files.walk(path, 3)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".log") || name.endsWith(".out") || name.endsWith(".txt");
                        })
                        .sorted()
                        .forEach(files::add);
            } catch (IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "无法遍历目录：" + e.getMessage());
            }
        }
        if (files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目录中未找到 .log/.out/.txt 文件");
        }
        return files;
    }

    /** 级别统计：各级别行数、总行数、时间范围。 */
    public Map<String, Object> stats(List<Path> files) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String level : List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")) levels.put(level, 0);
        long total = 0;
        String firstTs = null, lastTs = null;
        for (Path file : files) {
            for (String line : readLines(file, -1)) {
                total++;
                Matcher m = LEVEL.matcher(line);
                if (m.find()) {
                    String level = m.group(1) != null ? m.group(1) : m.group(2);
                    levels.merge(level, 1, Integer::sum);
                }
                Matcher ts = TIMESTAMP.matcher(line);
                if (ts.find()) {
                    if (firstTs == null) firstTs = ts.group();
                    lastTs = ts.group();
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files.stream().map(Path::toString).toList());
        result.put("totalLines", total);
        result.put("levels", levels);
        result.put("firstTimestamp", firstTs);
        result.put("lastTimestamp", lastTs);
        return result;
    }

    /** 错误扫描：ERROR/FATAL 行 + 紧随堆栈首帧，返回 Top 错误（按消息模板聚类）。 */
    public Map<String, Object> scanErrors(List<Path> files, int limit) {
        int cap = limit <= 0 ? 30 : Math.min(limit, 200);
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Integer> clusters = new HashMap<>();
        for (Path file : files) {
            List<String> lines = readLines(file, -1);
            for (int i = 0; i < lines.size() && errors.size() < cap * 3; i++) {
                String line = lines.get(i);
                Matcher m = LEVEL.matcher(line);
                String level = null;
                if (m.find()) level = m.group(1) != null ? m.group(1) : m.group(2);
                if (!"ERROR".equals(level) && !"FATAL".equals(level)) continue;
                String stackTop = firstStackFrame(lines, i);
                String fingerprint = fingerprint(line, stackTop);
                clusters.merge(fingerprint, 1, Integer::sum);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", file.getFileName().toString());
                item.put("lineNo", i + 1);
                item.put("level", level);
                item.put("message", truncate(line.trim(), 400));
                item.put("stackTop", stackTop);
                item.put("fingerprint", fingerprint);
                errors.add(item);
            }
        }
        List<Map<String, Object>> top = clusters.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("pattern", e.getKey());
                    item.put("count", e.getValue());
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scannedFiles", files.size());
        result.put("errorLines", errors.size());
        result.put("topPatterns", top);
        result.put("samples", errors.subList(0, Math.min(cap, errors.size())));
        return result;
    }

    /** 堆栈聚类：抽取所有 Caused by / at 帧并按顶层异常类聚类计数。 */
    public Map<String, Object> clusterStacks(List<Path> files) {
        Map<String, Integer> exceptionTypes = new HashMap<>();
        Map<String, Integer> hotFrames = new HashMap<>();
        for (Path file : files) {
            for (String line : readLines(file, -1)) {
                Matcher caused = CAUSED_BY.matcher(line);
                if (caused.find()) {
                    String cls = caused.group(1).trim().split("[:\\s]")[0];
                    exceptionTypes.merge(cls, 1, Integer::sum);
                }
                Matcher at = STACK_AT.matcher(line);
                if (at.find()) {
                    String cls = at.group(1);
                    if (!cls.startsWith("java.") && !cls.startsWith("sun.") && !cls.startsWith("jdk.")) {
                        hotFrames.merge(cls + "." + at.group(2), 1, Integer::sum);
                    }
                }
            }
        }
        List<Map<String, Object>> exceptions = topN(exceptionTypes, 10, "exception", "count");
        List<Map<String, Object>> frames = topN(hotFrames, 10, "frame", "count");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exceptionTypes", exceptions);
        result.put("hotFrames", frames);
        return result;
    }

    /** 时间线根因定位：找出第一个 ERROR 前后的关键事件（前后各 5 行 WARN/INFO）。 */
    public Map<String, Object> timeline(List<Path> files) {
        for (Path file : files) {
            List<String> lines = readLines(file, -1);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LEVEL.matcher(lines.get(i));
                String level = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
                if ("ERROR".equals(level) || "FATAL".equals(level)) {
                    int from = Math.max(0, i - 5);
                    List<Map<String, Object>> context = new ArrayList<>();
                    for (int j = from; j <= Math.min(lines.size() - 1, i + 5); j++) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("lineNo", j + 1);
                        row.put("text", truncate(lines.get(j), 300));
                        context.add(row);
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("file", file.toString());
                    result.put("firstErrorLine", i + 1);
                    result.put("context", context);
                    return result;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", null);
        result.put("message", "未找到 ERROR/FATAL 行");
        return result;
    }

    /** 尾部观察：读最后 N 行。 */
    public Map<String, Object> tail(List<Path> files, int n) {
        int count = n <= 0 ? 50 : Math.min(n, 500);
        Path file = files.get(0);
        List<String> lines = readLines(file, count);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.toString());
        result.put("lines", lines);
        return result;
    }

    // ---------- helpers ----------

    /** 读文件行；tailLines>0 时只读尾部。超过 READ_LIMIT 截断。 */
    private List<String> readLines(Path file, int tailLines) {
        try {
            long size = Files.size(file);
            if (tailLines > 0 && size > READ_LIMIT) {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    long start = Math.max(0, size - READ_LIMIT);
                    raf.seek(start);
                    byte[] buf = new byte[(int) (size - start)];
                    raf.readFully(buf);
                    List<String> all = new ArrayList<>(new String(buf, StandardCharsets.UTF_8).lines().toList());
                    return all.subList(Math.max(0, all.size() - tailLines), all.size());
                }
            }
            if (size > READ_LIMIT * 4) {
                // 大文件只读前 32MB
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    List<String> lines = new ArrayList<>();
                    long chars = 0;
                    String line;
                    while ((line = reader.readLine()) != null && chars < READ_LIMIT * 4L) {
                        lines.add(line);
                        chars += line.length();
                    }
                    return lines;
                }
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取日志失败：" + file + " - " + e.getMessage());
        }
    }

    private String firstStackFrame(List<String> lines, int errorIndex) {
        for (int j = errorIndex + 1; j < Math.min(errorIndex + 30, lines.size()); j++) {
            Matcher at = STACK_AT.matcher(lines.get(j));
            if (at.find()) return at.group(1) + "." + at.group(2) + (at.group(4) != null ? ":" + at.group(4) : "");
            Matcher m = LEVEL.matcher(lines.get(j));
            if (m.find()) break; // 到下一条日志行还没堆栈就停
        }
        return "";
    }

    /** 错误指纹：去数字/十六进制后的消息模板 + 堆栈顶层类。 */
    private String fingerprint(String message, String stackTop) {
        String tpl = message.replaceAll("0x[0-9a-fA-F]+", "")
                .replaceAll("\\d+", "#")
                .replaceAll("\\s+", " ").trim();
        String cls = stackTop.isEmpty() ? "" : stackTop.split("\\.\\w+\\(")[0];
        String base = cls.isEmpty() ? tpl : tpl + " @ " + lastSegment(cls);
        return truncate(base, 160);
    }

    private String lastSegment(String cls) {
        String[] parts = cls.split("\\.");
        return parts[parts.length - 1];
    }

    private List<Map<String, Object>> topN(Map<String, Integer> map, int n, String k, String v) {
        return map.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(n)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put(k, e.getKey());
                    item.put(v, e.getValue());
                    return item;
                })
                .toList();
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}

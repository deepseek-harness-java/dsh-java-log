# DSH Log Lens

> 日志文件猎人 —— 指向本地日志文件/目录，AI 帮你扫错误模式、聚类异常堆栈、沿时间线定位根因。

属于 [deepseek-harness-java](https://github.com/deepseek-harness-java) 独立 AI 产品矩阵（参照 `dsh-java-mysql` 形态）。

## 形态

- `dsh-java-log-app`：独立后台（Spring Boot，端口 **8093**），只读解析本地日志
- `dsh-java-log-plugin`：DSH Java Native 插件，向 Harness Agent 暴露 5 个只读日志工具

```
浏览器 → dsh-java-log-app(:8093) → Harness Agent(:8090) → dsh-java-log-plugin → HTTP 回调 app 只读分析接口 → 本地日志文件
```

## AI 工具（插件）

| 工具 | 说明 |
|------|------|
| `log_scan_errors` | 错误行扫描 + 按消息模板/堆栈指纹聚类的 Top 错误模式 |
| `log_cluster_stack` | Caused by 异常类聚类 + 业务代码热帧 Top 10 |
| `log_timeline` | 第一个 ERROR/FATAL 前后各 5 行事件时间线（根因入口） |
| `log_stats_by_level` | TRACE~FATAL 级别行数、总行数、时间范围 |
| `log_tail_watch` | 日志尾部最新 N 行 |

工具名暴露为 `plugin__dsh-java-log-plugin__<name>`。

## 安全红线（AI 绝不做）

- **不修改日志文件**（无任何写接口）
- **不外发日志内容**（数据只在本机流转）
- app 端只读解析，无执行入口

## 运行

```bash
java -jar dsh-java-log-app/target/dsh-java-log-app-0.1.0-SNAPSHOT.jar --server.port=8093
# 插件安装（同 dsh-java-git 流程）
cp dsh-java-log-plugin/target/dsh-java-log-plugin-0.1.0-SNAPSHOT.jar ~/.dsh/standalone/plugins/dsh-java-log-plugin.jar
# 然后 REST install + activate（见根 README）
```

打开 http://127.0.0.1:8093 → 填入日志路径（文件或目录）→ 右侧即时分析，左侧与 AI 对话定位根因。

演示数据：`~/.dsh-java-log/demo/app.log`（含支付网关超时 + NPE 两类错误）。

## E2E

```bash
python3 log_lens_e2e.py
```

覆盖 5 个用例：级别统计、错误扫描聚类、堆栈聚类、时间线根因、AI 对话调用工具。

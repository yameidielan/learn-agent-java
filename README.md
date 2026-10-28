# agent4j

用 Java 从零实现的 coding agent。一个 Java 后端开发者学习 agent 开发的完整练习：把 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 的 17 节课（Python）逐节移植到 **Java 17 + Maven + Fastjson2**，用 JDK 自带的 `HttpClient` 调用 DeepSeek 的 OpenAI 兼容 Responses API。不依赖任何 agent 框架，每一层机制都看得见、改得动。

> 整门课只有一个主角，从第一节到最后一节都没变过：
>
> ```java
> while (true) {
>     response = 调模型(系统提示词, 工具列表, 完整历史);
>     if (没有工具调用) return 回答;
>     for (每个工具调用) 历史.add(执行(调用));
> }
> ```
>
> **agent = 模型 + 循环 + 工具。** 模型决定“下一步做什么”，其余都是 harness：在这个循环的不同位置挂上工具、权限、上下文管理、记忆、任务、团队……

## 能力一览

| 课程 | 机制 | 实现 |
|---|---|---|
| s01 | Agent 循环：无状态 API，每轮重发完整历史，`call_id` 配对调用和结果 | `Main` |
| s02 | 工具 = 给模型看的声明 + 本地 handler；读 / 写 / 改 / glob / grep，路径沙箱 | `Tool`、`FileTools` |
| s03 | 三道权限闸门：拒绝列表 → 规则匹配 → 用户审批 | `Permission`、`BashTool` |
| s04 | Hooks：提问、工具前、工具后、结束四个事件点 | `Hooks` |
| s05 | 待办清单 `todo_write`，连续 3 轮没更新时提醒 | `TodoManager` |
| s06 | 子 agent：全新历史跑嵌套循环，只交回最终回答 | `Main#runSubagent` |
| s07 | 技能：提示词里只放目录，正文按需 `load_skill` | `SkillLoader` |
| s08 | 上下文压缩四步：大结果转存 → 归档 → 旧结果换成路径 → 摘要 | `ContextCompactor` |
| s09 | 跨会话记忆：召回 → 回答 → 提取 → 整理 | `MemoryStore` |
| s10 | 持久任务图：依赖、认领、完成后解锁下游 | `TaskStore` |
| s11 | 后台任务：慢命令放后台，结果以通知的形式在后续轮次送达 | `BackgroundManager` |
| s12 | 定时任务：cron 表达式 + 常驻模式 | `CronScheduler` |
| s13 | 团队：Lead + 常驻队友，收件箱通信，原子认领，关机与计划审批协议 | `TeamRuntime`、`MessageBus` |
| s14 | MCP：真实的 stdio JSON-RPC 传输，动态发现工具 | `McpClient`、`McpManager`、`DemoMcpServer` |
| s15 | 错误恢复：退避重试、截断后升级和续写、超长后被动压缩 | `Main#send`、`Main#loop` |
| s16 | Workflow：编排写在代码里，并行子 agent，journal 断点续跑 | `WorkflowRuntime`、`Workflows` |
| s17 | 目标循环：模型想停时由独立判断器决定是否真的完成 | `GoalController` |

每个类做什么、为什么这样设计，见 [代码导读](docs/code-guide.md)。

## 快速开始

需要 JDK 17+、Maven 3.8+ 和一个 DeepSeek API Key。

```powershell
$env:DEEPSEEK_API_KEY = "你的 DeepSeek API Key"
mvn compile exec:java "-Dexec.args=读取 README.md，介绍这个项目"
```

程序回答完一个问题就退出。agent 能读写**运行目录**下的文件、执行命令，**建议把运行目录设成一个单独的测试文件夹**，不要直接在本项目或重要的代码仓库里运行。

在 IntelliJ IDEA 里：把 `pom.xml` 导入为 Maven 项目，运行 `Main.main()`；运行配置里设置环境变量 `DEEPSEEK_API_KEY`、把 Working directory 设成测试文件夹，Program arguments 填问题。

### 几种运行方式

| 写法 | 作用 |
|---|---|
| `问题` | 普通提问，回答后退出 |
| `/goal 完成条件` | 目标模式：模型想结束时由独立判断器检查条件是否真的满足，没满足就带着原因继续 |
| `--serve=10 问题` | 常驻模式：回答完不退出，最多等 10 分钟，执行到点的定时任务 |

### 环境变量

| 变量 | 说明 |
|---|---|
| `DEEPSEEK_API_KEY` | 必填。只从环境变量读取，不写进源码 |
| `DEEPSEEK_MODEL` | 模型名，默认 `deepseek-flash` |
| `AGENT4J_TRACE_DIR` | 设置后把每次请求和响应的完整 JSON 存到这个目录（不含请求头和 key），学习协议最有用 |
| `AGENT4J_MAX_OUTPUT_TOKENS` | 单次输出上限，默认 4096；调小可以观察截断恢复 |
| `NO_COLOR` | 设置后控制台输出不带颜色 |

### 运行目录里可以放的东西

| 文件 / 目录 | 作用 |
|---|---|
| `skills/<名字>/SKILL.md` | 技能，启动时扫描，目录放进系统提示词 |
| `.mcp.json` | MCP server 配置：启动命令和宿主的放行名单 |
| `.memory/`、`.tasks/`、`.workflows/`、`.mailboxes/`… | agent 运行时自己生成的状态，删掉就回到初始状态 |

## 控制台输出

每一轮按“请求 → 响应 → 回复 → 工具调用 → 结果”分块显示：上下文占压缩阈值的比例、耗时、token 用量（含缓存命中）、每个工具的参数和结果预览。压缩、后台任务、定时任务、团队事件、目标判断等都有单独的标记，子 agent 和队友的输出带缩进前缀。读这些日志是理解 agent 在做什么的最好方式。

```
── 第 2/50 轮 · 父 agent ───────────────────────────────────
→ 请求  历史 6 项 · 工具 24 个 · 上下文 █░░░░░░░░░ 5%（2,534 / 50,000 字符）
← 响应  1.1 s · 输入 2,886 tokens（缓存 2,432）· 输出 37 tokens · 工具调用 2 个
◆ 回复 我会同时启动后台构建并读取日志行。
● [1/2] bash  command=ping -n 8 127.0.0.1 >nul & echo BUILD OK  run_in_background=true
  ✔ 完成  1 ms · 63 字符 · 1 行
● [2/2] read_file  path=logs/app.log  offset=1495  limit=10
  ✔ 完成  12 ms · 761 字符 · 12 行
```

## ⚠ 安全说明

这是学习项目，**不是安全的执行环境**：

- `bash` 工具能访问整台机器。文件工具有路径沙箱，`bash` 没有。
- 权限闸门靠字符串匹配，会拦下删除、修改系统环境（`setx`、`reg add` 等）、写到项目外的命令并询问用户，但挡不住刻意绕过，比如先写一个脚本再执行它。
- 真实发生过的事故：在目标模式下给了一个不可能完成的目标（“让 `java -version` 显示 99”），模型没有承认做不到，而是编译了一个假的 `java.exe`，并用 `setx` 改写了用户 PATH。这次事故之后才补上了上面的规则。**目标越坚定、自主性越高，边界就越要牢靠。**

想放心地让它自主工作，请在低权限账户、容器或虚拟机里运行。

## 本地验证

不需要 API Key，也不访问真实 API：本地启动模拟 HTTP 服务，覆盖循环、全部工具、权限、hook、压缩、记忆、任务图、后台任务、定时任务、团队、MCP（真实子进程）、workflow、目标循环和错误恢复。

```powershell
mvn test-compile exec:exec "-Dexec.executable=java" "-Dexec.args=-cp %classpath AgentLoopCheck" "-Dexec.classpathScope=test"
```

看到 `Agent Loop checks passed (local HTTP only, no real API calls)` 即通过。

## 和课程的差异

- **MCP 用真实传输**：课程用进程内的模拟对象，这里把 server 作为子进程启动，通过 stdin/stdout 逐行交换 JSON-RPC 消息，附带一个 Java 写的演示 server。
- **一次运行回答一个问题**：课程是交互式 REPL。所以定时任务需要 `--serve` 常驻模式；团队启动前由宿主问用户 y/N，而不是在对话里确认。
- **没有做的**：git worktree（s13）、过载时切换备用模型（s15）、系统级的 bash 隔离。
- **额外加的**：`grep` 工具（按 UTF-8 搜索，避开 Windows 控制台的编码问题）、`read_file` 的分页读取、每次判断的完整日志等。

## 真实运行中学到的

每一节都用真实 API 跑过，其中有些现象只有跑起来才看得到：

- **压缩会打断提示词缓存**；短小但关键的结果（比如文件列表）被压掉后，模型会猜错文件名。写在回复里的“笔记”能挺过压缩。
- **同一次响应里并行发出的工具调用彼此看不到结果**，所以任务图必须先建节点、拿到 ID，再加依赖。
- **记忆提取很难做对**：模型会把自己的推测（“用户没反对”）当成用户偏好存下来。
- **Workflow 续跑要求输入逐字节相同**：模型“重抄”的参数多了 2 个字符，15 个子 agent 就全部重跑了。
- **判断器只核对“有没有证据”，不核对“证据是不是正当得来的”**，伪造的输出也会被判为达成。
- **模型会预判长度**：要它列出 2500 个数，它直接拒绝并提出分段，而不是写到被截断。
- **大文件靠“先定位、少量读”**：分页读加 grep，模型会自己把章节切成小块读，配合压缩把内容装进有限的上下文。

## 参考

- [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)：本项目移植的课程
- [DeepSeek 创建响应](https://api-docs.deepseek.com/zh-cn/api/create-response)、[Responses API 指南](https://api-docs.deepseek.com/zh-cn/guides/responses_api/)
- [Model Context Protocol](https://modelcontextprotocol.io/)

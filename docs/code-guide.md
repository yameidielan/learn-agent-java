# agent4j 代码导读

这份文档是边学边写的逐类说明，按阅读顺序介绍每个类做什么、为什么这样设计。项目概况见仓库首页的 [README](../README.md)。

Java 17 + Maven + Fastjson2 的 Agent Loop 学习项目，使用 JDK HttpClient 调用
DeepSeek 的 OpenAI Responses API。先把模型和工具的完整循环跑通，再学习框架。

## 运行

在 `manual/agent4j` 目录打开 PowerShell，配置环境变量后运行一个问题：

```powershell
$env:DEEPSEEK_API_KEY = "你的 DeepSeek API Key"
mvn compile exec:java "-Dexec.args=读取 README.md，介绍这个项目"
```

默认模型是 `deepseek-flash`。可以用 `$env:DEEPSEEK_MODEL = "deepseek-v4-pro"`
切换模型。环境变量只对当前终端及其启动的进程生效，API Key 不写入源码。

不带参数时自动发送默认问题“读取 README.md，介绍这个项目”，回答后退出，不等待输入：

```powershell
mvn compile exec:java
```

想查看每次和模型通信的原始 JSON，设置 `$env:AGENT4J_TRACE_DIR = "trace"` 再运行，
请求体和响应体会保存为 `trace/01-request.json`、`trace/01-response.json` 等文件（不含请求头和 API Key）。
子 agent 的请求文件名带 `sub-`，例如 `trace/02-sub-request.json`；生成摘要的请求带 `compact-`；
记忆的筛选、提取和整理请求分别带 `memory-select-`、`memory-extract-`、`memory-merge-`；目标判断请求带 `goal-`；
workflow 子 agent 的请求带 `wf-`（并行发出，编号交错）。编号按时间顺序交错。
和 MCP server 之间的每条 JSON-RPC 消息按顺序追加到 `trace/mcp-<server>.jsonl`，控制台里用 `⇢`（发给 server）和 `⇠`（server 发回）显示。

父 agent 每次运行最多调用模型 50 次，每个子 agent 最多 30 次，关闭流式输出和思考模式。

终端在每个循环（父 agent、每个子 agent）开始时完整显示一次系统提示词（`instructions`）和可用工具，
生成摘要和记忆的筛选、提取、整理这类一次性请求也会显示各自的系统提示词。之后按轮次分块显示：每轮先打印请求概况（历史项数、工具数、上下文占压缩阈值的比例），再打印响应耗时和
token 用量（含缓存命中）和工具调用个数、模型调用工具前顺带说的话（“◆ 回复”，区别于结束时的“■ 最终回答”）、
每个工具调用的参数、耗时、结果大小和前 6 行预览；一轮有多个工具调用时按执行顺序标出 `[1/3]` 这样的序号，
每个调用的结果紧跟在它下面；
待办清单完整显示，子 agent 的输出整体缩进并用 `┃` 标出，压缩、提醒等事件用 `◇` 标出，
结束时打印本次运行的耗时、调用次数和 token 汇总。默认带颜色，设置环境变量 `NO_COLOR=1` 输出纯文本。

在 IntelliJ IDEA 中将 `pom.xml` 添加为 Maven 项目，运行 `Main.main()`。
在运行配置的 Environment variables 中设置 `DEEPSEEK_API_KEY`，并将 Working
directory 设为本项目 `agent4j` 目录；Program arguments 可填写一个问题，留空则发送默认问题。

## 从哪里开始读代码

当前进度对应 learn-claude-code 的 s16 和 s17（s11–s13、s15 跳过）。按下面的顺序阅读：

1. `Tool.java`：一个工具 = 给模型看的声明（名字、描述、参数 Schema）+ 本地执行的 handler。
2. `FileTools.java`：四个文件工具 `read_file`、`write_file`、`edit_file`、`glob`，以及它们共用的路径校验。
   `read_file` 不带参数时整读（最多 64 KiB）；带 `offset` / `limit` 时按行流式分页，单页最多 64 KiB，
   结果开头写明“第几行到第几行、共几行”，末尾提示下一页的 offset，所以 20 MiB 以内的大文件也能一页页读完。
   `grep` 按内容搜索：Java 里用严格 UTF-8 读文件，不经过控制台，没有 cmd/GBK 的乱码问题（之前模型借 bash 搜中文小说，
   输出全是乱码，只能靠行号凑合）。默认字面匹配（中文和符号不用转义），`regex=true` 用 Java 正则；可以限定 `path`（文件或目录）
   和 `glob`，`context` 带前后最多 5 行，`max_results` 默认 50、最多 200，`count_only` 只返回行数、次数和按文件分布。
   结果格式同 `grep -n`：匹配行 `路径:行号: 内容`，上下文行 `路径-行号- 内容`。跳过 `.git`、密钥文件、非 UTF-8 和超过 20 MiB 的文件，
   并说明跳过了几个。在 9 MB 的小说上统计一个人名大约 0.1–0.5 秒。
3. `BashTool.java`：`bash` 工具，Windows 上通过 `cmd.exe /c` 执行，按系统编码读取输出，超时 120 秒。
   s11 加了 `run_in_background` 参数，交给 `BackgroundManager.java`：命令放进后台线程（超时 600 秒），工具立刻返回
   `[后台任务 bg_0001 已启动]` 这样的占位结果。完成的结果不补回原来那次调用（那个 call_id 已经有结果了），
   而是在之后某一轮请求模型前，作为一条 `<task_notification>` 用户消息送达；完整输出存到 `.task_outputs/background/`，
   通知里只放前 2,000 字符。模型想结束但还有后台任务没送达时，先等结果再给它一轮（也排在 s17 目标判断之前）。
   只有父 agent 能启动后台任务；程序退出时结束仍在运行的后台进程及其子进程。
   `CronScheduler.java`（s12）：`schedule_cron` / `list_crons` / `cancel_cron` 三个工具，保存“5 段 cron 表达式 + prompt”。
   支持 `*`、`*/N`、`N`、`N-M`、`N,M`，日和星期都限定时满足其一即可（标准 cron 规则）。调度线程每秒看一次本机时间，
   到点且这一分钟没触发过的任务先标记再入队；常驻模式的主线程空闲时取出，作为 `[Scheduled] prompt` 追加到同一段对话再跑一轮，
   确认后一次性任务删除、周期任务清除标记。`durable=true` 的任务用临时文件加原子替换写进 `.scheduled_tasks.json`，
   重启后重新加载，没确认交付的会再交付一次（至少一次）；文件损坏时启动报错。**只有常驻模式才会触发**：
   第一个参数写 `--serve=N`，回答完不退出，最多等 N 分钟，没有定时任务了就提前结束。进程关闭时不触发、也不补跑错过的时间点。
4. `Permission.java`：三道权限闸门。拒绝列表直接拒绝；三类 bash 命令询问用户：删除类（rm、del、rd 等）、
   修改系统环境的（setx、reg add、SetEnvironmentVariable、计划任务、服务、icacls 等）、以及写入操作和项目外路径
   （绝对路径、`%USERPROFILE%` 等环境变量、`~\`、`..\`）同时出现的；都没命中直接放行。后两类是 s17 事故之后补的。
5. `Hooks.java`：四个事件的 hook 注册表。`UserPromptSubmit`（提问进入模型前）、`PreToolUse`（工具执行前，
   返回非 null 即阻止）、`PostToolUse`（工具执行后）、`Stop`（准备结束时，返回非 null 即让模型继续）。
6. `TodoManager.java`：`todo_write` 工具。模型每次传入完整待办清单，校验后整体替换并渲染成
   `[ ]` / `[>]` / `[x]` 列表返回；最多 20 项，同一时间最多一项 `in_progress`。它不增加执行能力，只保存计划。
7. `SkillLoader.java`：启动时扫描运行目录下的 `skills/<目录>/SKILL.md`，从 frontmatter 读取 `name` 和
   `description`（缺省时用目录名和正文第一行）。`catalog()` 生成每个技能一行的目录放进指令；
   `load_skill` 工具按名称返回完整 `SKILL.md`。名称只查注册表，不当作路径；指向目录外、超过 64 KiB 或
   不是 UTF-8 的文件会被跳过。frontmatter 只支持单行值和 `|` / `>` 多行块这一小部分 YAML。
8. `ContextCompactor.java`：每次调用模型前整理上下文，按成本从低到高四步：最新一批结果超过 20 万字符时，
   把超过 3 万字符的大结果存到 `.task_outputs/tool-results/`，只留 2,000 字符预览；历史超过 50 项时，
   中间部分写入 `.transcripts/`，留下归档标记，切点不拆开调用和结果；上下文超过 5 万字符时，
   把模型已经看过的旧结果（保留最近 3 条）换成磁盘路径，仍超限再把最大的结果缩成预览；
   最后仍超限才请模型生成摘要，用一条“当前用户请求 + 摘要 + transcript 路径”的消息替换整个历史。
   上下文大小用 JSON 字符数估算。
9. `MemoryStore.java`：跨会话记忆。每条记忆是运行目录下 `.memory/<name>.md` 一个文件（frontmatter 写 `name`、
   `description`、`type`，type 只有 user / feedback / project / reference 四种），`MEMORY.md` 是每条一行的索引，
   每次写入后根据文件重新生成。召回：提问前把目录发给模型，让它挑出最多 5 条相关的编号，再读这几条正文
   （总共不超过 2 万字符）；模型调用失败时退回关键词匹配（中文按相邻两字一组）。提取：回答后把这次对话
   （只含用户和模型说的话）发给模型，要它返回带 `scope` 的候选；代码只保存 `persistent`、字段完整、
   不含“本次会话”“暂时”这类临时说法、也不和已有记忆重名或同内容的候选，并在日志里列出每条被跳过的原因。
   整理：记忆达到 10 条时让模型合并去重，结果为空、重名或超过 30 条就放弃；替换文件中途出错时按快照恢复。
10. `TaskStore.java`：持久任务图。每个任务是运行目录下 `.tasks/task_xxxxxxxx.json` 一个文件，字段有 `subject`、`description`、`status`、`owner`、`blockedBy`。
   六个工具：`create_task` 用 CREATE_NEW 写新文件、返回随机 ID；`update_task` 加前置任务，拒绝自依赖、不存在的任务、
   循环依赖和已开始的任务；`claim_task` 只认领前置任务全部 completed 的 pending 任务；`complete_task` 只能完成自己认领的任务，
   并报告这次刚被解锁的下游任务；`list_tasks` 显示每个 pending 任务能否开始或被谁阻塞；`get_task` 返回完整 JSON。
   不合规的操作都作为工具错误返回。`todo_write` 仍然保留，是这次会话自己的步骤清单；用任务工具推进任务图也算更新计划，不触发待办提醒。
   s13 之后：认领、完成、加依赖都在同一把锁里做（进程内 `synchronized` + `.tasks/.lock` 文件锁），保存时先写临时文件再原子替换；
   一个负责人手上有进行中的任务时不能再认领新的。
   `MessageBus.java` / `TeamRuntime.java`（s13）：Lead（父 agent）加常驻队友。`spawn_teammate` 先征得用户同意、先替队友认领初始任务，
   再在新线程里用队友自己的 Profile（独立历史、只有工作区工具 + 任务的列举/查看/认领/完成 + `send_message` + `submit_plan`）
   跑同一个循环。队友在 WORK 和 IDLE 之间切换：WORK 结束发 `result` 和 `idle_notification` 两个事件；IDLE 先看收件箱
   （关机、审批、消息优先），没有消息再去任务板原子认领 ready 任务。通信走 `.mailboxes/<名字>.jsonl`，读即消费；
   Lead 每轮请求前由运行时把收件箱整理成一条 `[团队事件]` 投递，Lead 想结束但队友还在干活时先等事件。
   关机和计划审批用带 `request_id` 的结构化消息；`require_plan` 的队友计划获批前，`bash` / `write_file` / `edit_file` 被挡住；
   没有认领任务的队友不能用工作区工具；队友线程里需要用户批准的操作直接拒绝，不和主线程抢控制台。程序结束时向所有队友发关机请求。
   为了让多个循环同时跑，`Main` 里“当前在跑哪个循环”和日志缩进都改成了 `ThreadLocal`。课程里的 git worktree 没有做。
11. `McpClient.java` / `McpManager.java` / `DemoMcpServer.java`：MCP。和课程用进程内模拟对象不同，这里走真实的 stdio 传输：
   `McpClient` 把 server 作为子进程启动，向它的 stdin 一行写一条 JSON-RPC 消息、从 stdout 一行读一条，
   依次 `initialize`（协商协议版本 2025-06-18）→ `notifications/initialized` 通知 → `tools/list`（支持分页）→ `tools/call`；
   server 的 stderr 写到 `.mcp-logs/<server>.log`，不和协议消息混在一起；等响应最多 30 秒。
   `McpManager` 读运行目录的 `.mcp.json`（`mcpServers`：每个 server 的 `command`、`args` 和宿主自己写的 `allow` 放行名单），
   有配置时才给父 agent 加 `connect_mcp` 工具。模型调用它时才启动 server，把发现的工具改名为 `mcp__{server}__{tool}`
   （不合法字符换成下划线，检查撞名和 64 字符上限，全部通过才一起加入）放进父 agent 的工具表，下一轮请求就带上了。
   外部工具的描述限制 1,000 字符、结果限制 5 万字符。权限只看宿主的 `allow`，server 在 `annotations` 里自称只读也不算数，
   不在名单里的每次问用户。`DemoMcpServer docs` 在运行目录的 `docs/` 里真实搜索 Markdown，`deploy` 是只返回文字的假部署平台。
12. `WorkflowRuntime.java` / `Workflows.java`：s16 的 workflow 运行时。父 agent 多了一个 `workflow` 工具，模型只能选宿主注册好的
   workflow 名称、传 `args`、给续跑的 `resume_from_run_id`，不能提交脚本。一次工具调用在背后跑完整个编排：
   `agent(label, prompt, schema)` 派一个**没有工具**、只看 prompt 的子 agent，要求返回符合 schema 的 JSON，不合格提醒一次重试；
   `parallel` 并行跑完再按原顺序返回；`pipeline` 让每个 item 各自走完几个阶段、互不等待；同时在途的模型请求最多 4 个。
   每个 `agent()` 的结果追加到 `.workflows/<runId>.journal.jsonl`，key 是“类型 + 标签 + prompt + schema”的 SHA-256，
   不按完成顺序编号。带 runId 续跑时内容没变的调用直接用 journal，不请求模型；续跑沿用快照里的参数，模型给的非空值才覆盖
   （输入差一个字符 key 就变，真实运行里模型“重抄”的参数多了 2 个字符，导致全部重跑）。运行期间持有 `<runId>.lock`。
   示例 `review-changes`：正确性、边界条件、可读性三个维度用 pipeline 各自“审计 → 逐条并行验证”，只返回验证为真的问题。
   中间结果都在脚本变量里，不进入主 agent 的对话历史。
13. `GoalController.java`：s17 的目标循环。问题以 `/goal 完成条件` 开头时保存条件，并把条件直接作为任务交给模型。
   模型不再调用工具时不立刻结束，而是用一次独立的模型调用（没有工具，只读对话记录）判断条件是否满足：
   满足就结束；不满足就把判断器给的原因作为用户消息追加，回到同一个循环继续；判断器认为不可能完成、
   连续 5 次判定未完成、或判断器返回格式不对时都停下，后两种情况目标保持有效，不会伪装成完成。
   有目标时指令会要求模型把验证命令、输出和退出码写进回答，因为判断器看不到文件。
   **注意**：目标会推着模型想尽办法去“完成”。真实运行中，一个做不到的目标（让 `java -version` 显示 99）
   让模型用 `bash` 编译了一个假 java，并用 `setx` 改写了用户 PATH。`bash` 不受路径沙箱约束，权限闸门也没拦环境变量修改，
   不要拿可能诱导改系统环境的目标做实验。事故之后补了两处：权限闸门对修改系统环境和写到项目外的 bash 命令询问用户；
   有目标时的指令要求做不到就如实说明，判断器把靠伪造结果“满足”的情况判为 impossible。
   这些仍是字符串匹配和提示词，挡不住刻意绕过（比如先在项目里写一个脚本再执行它），不是真正的沙箱。
14. `ConsoleLog.java`：控制台输出，按轮次分块显示请求、响应、回复、工具调用和结果预览。
15. `Main` 构造器：建两套工具表和两个 `Profile`。父 agent 有基础工具（含 `load_skill`）、`todo_write`、`task`、`compact` 和六个任务工具；
   子 agent 只有六个基础工具，不能再委派，也不碰父 agent 的待办和任务图。注意 `task`（委派子 agent）和
   `create_task` 等（持久任务图）是两回事，名字沿用课程。父子指令末尾都附上压缩说明和技能目录。
   再用 `registerDefaultHooks` 挂上默认 hook：显示提问、权限检查、大输出提醒、结束时统计工具结果数。父子循环共用这些 hook。
16. `taskTool` / `runSubagent`：`task` 工具。用只含任务描述的全新历史跑一个嵌套循环，
   只把最终回答作为工具结果交回父 agent；子 agent 的工具调用和结果不进入父历史。子 agent 出错或超过 30 轮时，
    把原因作为工具结果返回，父 agent 不会崩溃。
17. `compactTool`：`compact` 工具。模型主动要求压缩时，等同一批工具全部执行、结果都追加后再生成摘要。
18. `buildRequest`：按当前循环（`current`）取指令和工具声明，加上完整历史。父指令要求多步骤任务先列计划，
    调研类子任务交给 `task`，涉及技能时先 `load_skill`，需要跟踪依赖或跨会话的任务用任务工具。
19. `callModel` / `ask` / `send`：循环里的请求和一次性请求（摘要、记忆筛选 / 提取 / 整理）都 POST 到
    `https://api.deepseek.com/responses`；一次性请求用 `ask`：专用指令、不带工具，输入只有一条用户消息。
    s15 的错误恢复：`send` 遇到 429、500、502、503、504、529 和网络异常时指数退避加随机抖动重试（1s、2s、4s…最多 32s，
    有 `Retry-After` 就按它等），最多 4 次；401、400 等直接报错。循环里，输出因 `max_output_tokens` 被截断时，
    先把上限调到 2 倍、丢掉截断的输出重试这一轮；还截断就保留已写出的文字、追加“从断开处接着写”，最多续写 2 次，
    最终回答把各段拼起来；截断的工具调用参数不完整，丢掉不执行。HTTP 400 且正文提到上下文长度时，做一次被动压缩再重试这一轮。
    默认输出上限 4096，可以用环境变量 `AGENT4J_MAX_OUTPUT_TOKENS` 调整（调小可以观察截断恢复）。
20. `runTurn`：一次提问的完整流程。先 `memory.recall` 把相关记忆追加到父 agent 指令末尾（只对这次提问生效，
    子 agent 不带记忆），再跑父循环，回答后 `memory.extract` 提取记忆，有新记忆时再 `consolidate`。
21. `agentLoop` / `runLoop`：父子共用的循环。每次请求模型前先压缩上下文，保存模型输出，每个工具调用前后触发 hook，
    追加工具结果，再请求模型；没有工具调用时输出回答并触发 Stop hook。拥有 `todo_write` 的循环连续 3 轮没用它时，
    在工具结果后追加一条 `<reminder>` 用户消息。子 agent 运行期间 `current` 切换为子 agent，结束后恢复。
22. `askUser`：权限闸门 3，在控制台询问 `允许执行吗？[y/N]`，读不到输入时按拒绝处理。
23. `executeTool`：按工具名查当前循环的工具表分发，把参数错误和文件错误统一转成错误文本。

新增扩展逻辑（审计日志、自动备份、通知等）只需在 `registerDefaultHooks` 里注册一个 hook，循环不用改。
同一事件的 hook 按注册顺序执行，第一个阻止执行的 `PreToolUse` 生效后，后面的不再执行。

新增工具只需在 `FileTools.all()`（或新的工具类）里加一个 `Tool`，循环和请求构造都不用改。

Agent Loop 保存完整 `output` 到历史，再查找所有 `function_call`。其中
`arguments` 是 JSON 字符串，需要解析；执行结果以独立的
`function_call_output` item 追加，用 `call_id` 配对。DeepSeek 不保存会话，
每次请求的 `input` 都要包含完整历史。

所有文件工具只接受相对于运行目录的路径，只处理项目内不超过 64 KiB 的 UTF-8 文件。
越界路径、指向项目外的链接、`.env` / `.env.*` 和常见密钥文件扩展名会被拒绝，
`glob` 也不会列出密钥文件。`edit_file` 要求 `old_text` 在文件中恰好出现一次，
找不到或出现多次都会返回错误，让模型补充上下文后重试。

子 agent 隔离的只是对话，不是进程或文件系统：它和父 agent 在同一个进程、同一个运行目录里工作，
写文件和执行命令同样受路径沙箱和权限闸门约束，也同样会影响工作区。

注意：有了 `write_file` 和 `edit_file`，agent 可以修改运行目录下的文件，包括本项目的源码。
练习时建议把运行目录设为单独的测试文件夹。

`bash` 不受上面的路径沙箱约束，可以访问整台机器。权限闸门用字符串匹配实现，只用于学习权限管线的位置，
挡不住刻意绕过（比如用 PowerShell 或脚本间接删除），不能当作真正的安全边界。被拒绝的操作会把原因
作为工具结果回传给模型。

工具执行失败或参数错误会作为工具结果反馈给模型，让它继续处理；暂时性的 HTTP 错误先重试，
重试用完的 HTTP 错误、恢复不了的未完成响应和达到轮数上限则终止运行。

## 本地验证

下面的检查启动本地模拟 HTTP 服务，不需要 API Key，也不访问真实 DeepSeek API。
它覆盖实际 HTTP 请求、读取文件后回传结果的两轮闭环、多个工具调用、reasoning
保留、`call_id` 配对、工具错误回传、路径及文件限制、截断响应和轮数上限，
以及四个文件工具的读写、替换、通配符查找和越界拒绝，bash 的输出、中文编码和退出码，
三道权限闸门，权限拒绝后回传原因并继续循环，hook 的执行顺序、阻止、PostToolUse 和 Stop 续跑，待办清单的校验、渲染和连续 3 轮未更新时的提醒，以及子 agent 的独立历史、受限工具集、
只回传最终回答和共享 hook，技能目录的扫描、frontmatter 解析和按名称加载，以及上下文压缩四步
（大结果转存、归档时不拆开调用和结果、旧结果换成可恢复的路径、超限时摘要）和 `compact` 工具，
以及记忆的写入和索引、候选过滤（scope、临时说法、重复）、跨会话召回进指令、关键词降级和整理失败时保持原文件，
任务图的两阶段建图、重复依赖去重、自依赖 / 循环 / 不存在的依赖被拒绝、依赖未完成不能认领、完成后报告解锁和跨进程读回任务，
以及 MCP：用真实子进程启动 DemoMcpServer 完成握手、发现、调用，isError 结果、未知 server、重复连接、改名规则、
放行名单内直接执行和名单外询问用户，
以及目标循环：判断未通过时带着原因自动续跑、通过后结束、连续上限和判断器出错时停下并保留目标、判断结果格式校验、
过长对话只保留头尾，以及没有目标时行为不变，
workflow：pipeline 加 parallel 的调用次数、只保留验证为真的发现、journal 记录、原样续跑全部命中缓存、续跑时空参数沿用原值、
中途失败后续跑只补跑失败的那一步、schema 不合格时重试一次、锁住的运行不能续跑、未知 workflow 和不安全的名称被拒绝。

上下文超长后的“被动压缩”（s15）已经实现，但 DeepSeek 超长时的确切错误格式没有核实，目前按 HTTP 400 且正文提到上下文长度来识别；
而 5 万字符的主动压缩阈值远低于模型的上下文长度，正常情况下用不到。课程里过载时切换到备用模型的做法没有实现。

```shell
mvn test-compile exec:exec "-Dexec.executable=java" "-Dexec.args=-cp %classpath AgentLoopCheck" "-Dexec.classpathScope=test"
```

看到 `Agent Loop checks passed (local HTTP only, no real API calls)` 表示本地检查通过。
真实 API 的连通性、密钥和模型可用性需要配置自己的 API Key 后运行上面的示例验证。

协议文档：[创建响应](https://api-docs.deepseek.com/zh-cn/api/create-response)、
[Responses API 指南](https://api-docs.deepseek.com/zh-cn/guides/responses_api/)。

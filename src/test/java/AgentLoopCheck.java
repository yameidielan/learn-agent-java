import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AgentLoopCheck {
    private static final String TEST_KEY = "local-test-key";
    private static final URI UNUSED_ENDPOINT = URI.create("http://127.0.0.1:1/responses");
    private static final String FILE_CONTENT = "# agent4j\n一个使用 Java 学习 Agent Loop 的项目。\n";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("agent4j-check-");
        Path outside = Files.createTempFile(root.getParent(), "agent4j-outside-", ".txt");
        try {
            Files.writeString(root.resolve("README.md"), FILE_CONTENT, StandardCharsets.UTF_8);
            Files.writeString(root.resolve(".env"), "TEST_SECRET=do-not-read");
            Files.writeString(root.resolve(".env.local"), "TEST_SECRET=do-not-read");
            Files.write(root.resolve("boundary.txt"), new byte[65_536]);
            Files.write(root.resolve("large.txt"), new byte[65_537]);
            Files.write(root.resolve("invalid-utf8.txt"), new byte[]{(byte) 0xc3, 0x28});
            Files.writeString(outside, "outside project");
            checkRequestShape(root);
            checkMultipleCalls(root);
            checkIncompleteAndRoundLimit(root);
            checkTools(root, outside);
            checkFileTools(root, outside);
            checkPermission();
            checkBash(root);
            checkPermissionInLoop(root);
            checkHooks(root);
            checkTodoWrite();
            checkTodoReminder(root);
            checkSubagent(root);
            checkSkills();
            checkCompactor();
            checkCompactTool(root);
            checkMemory();
            checkTasks();
            checkMcp();
            checkGoal();
            checkWorkflow();
            checkBackground();
            checkCron();
            checkTeam();
            checkRecovery(root);
            checkGrep();
            checkHttpRoundTrip(root);
            System.out.println("Agent Loop checks passed (local HTTP only, no real API calls)");
        } finally {
            Files.deleteIfExists(outside);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkRequestShape(Path root) throws Exception {
        JSONArray history = userInput();
        JSONObject request = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root).buildRequest(history);
        check(request.getJSONArray("input") == history, "请求应使用完整历史");
        check(!request.containsKey("messages"), "请求不能沿用 messages 字段");
        JSONArray tools = request.getJSONArray("tools");
        List<String> names = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            JSONObject tool = tools.getJSONObject(index);
            check("function".equals(tool.getString("type")), "工具声明应使用 function");
            check(tool.getJSONObject("parameters") != null, "工具声明应包含 parameters");
            names.add(tool.getString("name"));
        }
        check(names.equals(List.of("read_file", "write_file", "edit_file", "glob", "grep", "bash", "load_skill", "todo_write", "task", "compact",
                        "create_task", "update_task", "list_tasks", "get_task", "claim_task", "complete_task",
                        "schedule_cron", "list_crons", "cancel_cron", "workflow",
                        "spawn_teammate", "send_message", "list_teammates", "shutdown_teammate", "review_plan")),
                "请求应声明全部已注册工具: " + names);
    }

    private static void checkSubagent(Path root) throws Exception {
        String subPrompt = "读取 README.md，用一句话总结。";
        List<String> parentInputs = new ArrayList<>();
        int[] subRounds = {0};
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                boolean isSub = subPrompt.equals(input.getJSONObject(0).getString("content"));
                if (isSub) {
                    subRounds[0]++;
                    JSONObject request = buildRequest(input);
                    List<String> names = new ArrayList<>();
                    request.getJSONArray("tools").forEach(tool -> names.add(((JSONObject) tool).getString("name")));
                    check(names.equals(List.of("read_file", "write_file", "edit_file", "glob", "grep", "bash", "load_skill")),
                            "子 agent 只有基础工具，没有 todo_write 和 task: " + names);
                    check(request.getString("instructions").contains("子 agent"), "子 agent 应使用自己的指令");
                    if (subRounds[0] == 1) {
                        // 子 agent 同时读文件并尝试再委派一次。
                        JSONObject response = toolResponse("README.md", "sub_read");
                        JSONObject task = JSON.parseObject("""
                                {"type":"function_call","id":"fc_task","call_id":"sub_task","name":"task",
                                 "arguments":"{\\"prompt\\":\\"再委派\\"}"}
                                """);
                        response.getJSONArray("output").add(task);
                        return response;
                    }
                    check(input.size() == 5, "子 agent 的历史应包含提示、两个调用和两个结果");
                    check(FILE_CONTENT.equals(input.getJSONObject(3).getString("output")), "子 agent 应能读文件");
                    String nested = input.getJSONObject(4).getString("output");
                    check(nested.startsWith("工具错误: 未知工具 task"), "子 agent 不能再委派: " + nested);
                    return finalResponse("子结论：这是学习项目。");
                }
                parentInputs.add(JSON.toJSONString(input));
                check(buildRequest(input).getString("instructions").contains("task"), "父 agent 应使用父指令");
                if (parentInputs.size() == 1) {
                    JSONObject response = toolResponse("unused", "call_task");
                    JSONObject call = response.getJSONArray("output").getJSONObject(0);
                    call.put("name", "task");
                    JSONObject arguments = new JSONObject();
                    arguments.put("prompt", subPrompt);
                    call.put("arguments", JSON.toJSONString(arguments));
                    return response;
                }
                check(input.size() == 3, "子 agent 的中间对话不应进入父历史: " + input.size());
                JSONObject result = input.getJSONObject(2);
                check("call_task".equals(result.getString("call_id")), "task 结果应与父调用配对");
                check("子结论：这是学习项目。".equals(result.getString("output")), "父 agent 只应收到子 agent 的最终回答");
                return finalResponse();
            }
        };
        List<String> afterTool = new ArrayList<>();
        agent.hooks.onPostToolUse((name, arguments, output) -> afterTool.add(name));
        int[] stops = {0};
        agent.hooks.onStop(history -> {
            stops[0]++;
            return null;
        });
        agent.agentLoop(userInput());
        check(subRounds[0] == 2 && parentInputs.size() == 2, "父子各应请求两轮模型");
        check(afterTool.equals(List.of("read_file", "task", "task")), "子 agent 的工具调用也应经过共享 hook: " + afterTool);
        check(stops[0] == 2, "父子循环结束时都应触发 Stop hook");
    }

    private static void checkMultipleCalls(Path root) throws Exception {
        JSONArray history = userInput();
        JSONObject firstResponse = JSON.parseObject("""
                {
                  "status":"completed",
                  "output":[
                    {"type":"reasoning","id":"rs_1",
                     "content":[{"type":"reasoning_text","text":"先读取文件。"}]},
                    {"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_file"},
                    {"type":"function_call","id":"fc_2","call_id":"call_2","name":"read_file"}
                  ]
                }
                """);
        firstResponse.getJSONArray("output").getJSONObject(1)
                .put("arguments", "{\"path\":\"README.md\"}");
        firstResponse.getJSONArray("output").getJSONObject(2)
                .put("arguments", "{\"path\":\"pom.xml\"}");
        List<String> executed = new ArrayList<>();
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            int rounds;

            @Override
            JSONObject callModel(JSONArray input) {
                if (rounds++ == 0) {
                    return firstResponse;
                }
                check(input.size() == 6, "下一轮应保留历史、reasoning、两个调用及两个结果");
                check(firstResponse.getJSONArray("output").getJSONObject(0).equals(input.get(1)),
                        "reasoning 应完整保留在原始位置");
                for (int index = 0; index < 2; index++) {
                    JSONObject result = input.getJSONObject(4 + index);
                    check("function_call_output".equals(result.getString("type")),
                            "工具结果必须是平铺的 item");
                    check(("call_" + (index + 1)).equals(result.getString("call_id")),
                            "结果必须使用 call_id，而不是 item id");
                    check(!result.containsKey("role"), "工具结果不应包装成 user 消息");
                    check(("read:" + executed.get(index)).equals(result.getString("output")),
                            "工具执行结果应回传给下一轮");
                }
                return finalResponse();
            }

            @Override
            String executeTool(String name, JSONObject arguments) {
                check("read_file".equals(name), "应按模型指定的工具名分发");
                String path = arguments.getString("path");
                executed.add(path);
                return "read:" + path;
            }
        };
        agent.agentLoop(history);
        check(executed.equals(List.of("README.md", "pom.xml")), "应解析参数并执行所有调用");
        check(history.size() == 7, "最终回答也应保存到历史");
    }

    private static void checkIncompleteAndRoundLimit(Path root) throws Exception {
        Main incompleteAgent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                return JSON.parseObject("""
                        {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                         "output":[]}
                        """);
            }
        };
        JSONArray incompleteHistory = new JSONArray();
        try {
            incompleteAgent.agentLoop(incompleteHistory);
            throw new AssertionError("截断响应不能当作成功完成");
        } catch (IllegalStateException expected) {
            check(incompleteHistory.isEmpty(), "截断响应不应作为完整输出追加");
        }

        int[] rounds = {0};
        Main loopingAgent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                rounds[0]++;
                return toolResponse("README.md", "call_" + rounds[0]);
            }
        };
        try {
            loopingAgent.agentLoop(userInput());
            throw new AssertionError("持续调用工具时应在轮数上限停止");
        } catch (IllegalStateException expected) {
            check(rounds[0] == 50, "模型调用应有五十轮上限: " + rounds[0]);
        }
    }

    private static void checkTools(Path root, Path outside) throws Exception {
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root);
        check(FILE_CONTENT.equals(agent.executeTool("read_file", pathArguments("README.md"))),
                "read_file 应正确读取 UTF-8 文件");
        check(agent.executeTool("read_file", pathArguments("boundary.txt")).length() == 65_536,
                "恰好 64 KiB 的文件应允许读取");
        List<String> denied = List.of(
                "missing.txt", "../" + outside.getFileName(),
                root.resolve("README.md").toString(), ".env", ".env.local", "large.txt", "invalid-utf8.txt");
        for (String path : denied) {
            checkToolError(agent.executeTool("read_file", pathArguments(path)), path);
        }
        checkToolError(agent.executeTool("read_file", new JSONObject()), "缺少 path");
        checkToolError(agent.executeTool("unknown_tool", new JSONObject()), "未知工具");

        // 文件不存在和模型生成的 JSON 损坏，都应反馈给模型，让它有机会修正。
        for (boolean malformed : List.of(false, true)) {
            int[] rounds = {0};
            Main recoveringAgent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
                @Override
                JSONObject callModel(JSONArray input) {
                    if (rounds[0]++ == 0) {
                        JSONObject response = toolResponse("missing.txt", "call_missing");
                        if (malformed) {
                            response.getJSONArray("output").getJSONObject(0).put("arguments", "{bad");
                        }
                        return response;
                    }
                    checkToolError(input.getJSONObject(2).getString("output"), "工具失败的回传结果");
                    return finalResponse();
                }
            };
            recoveringAgent.agentLoop(userInput());
            check(rounds[0] == 2, "工具失败后应回传错误并继续请求模型");
        }
    }

    private static void checkFileTools(Path root, Path outside) throws Exception {
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root);

        JSONObject limited = pathArguments("README.md");
        limited.put("limit", 1);
        check(agent.executeTool("read_file", limited).equals("（第 1–1 行，共 2 行）\n# agent4j\n...（还有 1 行，下一页用 offset=2）"),
                "read_file 的 limit 应只返回前几行并提示下一页: " + agent.executeTool("read_file", limited));
        limited.put("limit", 0);
        checkToolError(agent.executeTool("read_file", limited), "limit 为 0");

        // 分页读大文件：超过 64 KiB 的文件不能整读，但能按 offset 一页页读完，拼起来和原文一致。
        StringBuilder big = new StringBuilder();
        for (int line = 1; line <= 3_000; line++) {
            big.append("第 ").append(line).append(" 行 ").append("x".repeat(30)).append('\n');
        }
        Files.writeString(root.resolve("big.log"), big, StandardCharsets.UTF_8);
        checkToolError(agent.executeTool("read_file", pathArguments("big.log")), "超过 64 KiB 不分页");
        List<String> collected = new ArrayList<>();
        int offset = 1;
        int pages = 0;
        while (true) {
            JSONObject paged = pathArguments("big.log");
            paged.put("offset", offset);
            String page = agent.executeTool("read_file", paged);
            check(page.length() <= FileTools.MAX_FILE_BYTES + 200, "单页不应超过 64 KiB");
            List<String> lines = new ArrayList<>(page.lines().toList());
            lines.remove(0);
            pages++;
            java.util.regex.Matcher next = java.util.regex.Pattern.compile("下一页用 offset=(\\d+)").matcher(page);
            if (next.find()) {
                lines.remove(lines.size() - 1);
                collected.addAll(lines);
                offset = Integer.parseInt(next.group(1));
            } else {
                collected.addAll(lines);
                break;
            }
        }
        check(pages >= 2 && String.join("\n", collected).equals(big.toString().strip()), "分页读完应和原文一致，共 " + pages + " 页");
        JSONObject beyond = pathArguments("big.log");
        beyond.put("offset", 5_000);
        check(agent.executeTool("read_file", beyond).contains("已超出末尾"), "offset 超出末尾应说明");
        JSONObject badUtf8 = pathArguments("invalid-utf8.txt");
        badUtf8.put("offset", 1);
        checkToolError(agent.executeTool("read_file", badUtf8), "分页读非 UTF-8 文件");

        JSONObject write = pathArguments("notes/new.md");
        write.put("content", "第一行\n第二行\n");
        check(agent.executeTool("write_file", write).startsWith("已写入"), "write_file 应自动创建父目录");
        check("第一行\n第二行\n".equals(agent.executeTool("read_file", pathArguments("notes/new.md"))),
                "写入后应能读回相同内容");
        for (String path : List.of("../" + outside.getFileName(), ".env", "notes/.env.local",
                root.resolve("x.md").toString(), "notes")) {
            JSONObject denied = pathArguments(path);
            denied.put("content", "x");
            checkToolError(agent.executeTool("write_file", denied), "写入 " + path);
        }
        check("outside project".equals(Files.readString(outside)), "项目外文件不能被写入");
        checkToolError(agent.executeTool("write_file", pathArguments("no-content.md")), "缺少 content");

        JSONObject edit = pathArguments("notes/new.md");
        edit.put("old_text", "第二行");
        edit.put("new_text", "改过的第二行");
        check(agent.executeTool("edit_file", edit).startsWith("已修改"), "edit_file 应替换唯一匹配");
        check("第一行\n改过的第二行\n".equals(Files.readString(root.resolve("notes/new.md"))),
                "edit_file 应只改动匹配的文本");
        edit.put("old_text", "不存在的文本");
        checkToolError(agent.executeTool("edit_file", edit), "old_text 不存在");
        edit.put("old_text", "行");
        checkToolError(agent.executeTool("edit_file", edit), "old_text 出现多次");
        JSONObject editSecret = pathArguments(".env");
        editSecret.put("old_text", "TEST");
        editSecret.put("new_text", "X");
        checkToolError(agent.executeTool("edit_file", editSecret), "修改密钥文件");

        String markdown = agent.executeTool("glob", globArguments("**/*.md"));
        check(markdown.equals("README.md\nnotes/new.md"), "**/*.md 应同时匹配根目录和子目录: " + markdown);
        check(agent.executeTool("glob", globArguments("notes/*")).equals("notes/new.md"), "glob 应支持子目录模式");
        check(agent.executeTool("glob", globArguments("notes/**/*")).equals("notes/new.md"),
                "中间的 **/ 也应能匹配零层目录");
        check(!agent.executeTool("glob", globArguments("**")).contains(".env"), "glob 不应列出密钥文件");
        check(agent.executeTool("glob", globArguments("*.java")).startsWith("（没有匹配"), "无匹配时应明确说明");
        checkToolError(agent.executeTool("glob", globArguments("[")), "非法通配符");
    }

    private static void checkPermission() {
        List<String> asked = new ArrayList<>();
        Permission allowAll = new Permission((tool, arguments, reason) -> asked.add(reason));
        Permission denyAll = new Permission((tool, arguments, reason) -> !asked.add(reason));

        // 闸门 1：拒绝列表优先，忽略大小写，不会询问用户。
        for (String command : List.of("sudo ls", "SHUTDOWN /s /t 0", "Format C: /q")) {
            check(allowAll.check("bash", commandArguments(command)).startsWith("权限拒绝"), "拒绝列表应拦截 " + command);
        }
        check(asked.isEmpty(), "拒绝列表命中时不应询问用户");

        // 闸门 2 + 3：删除类命令询问用户，由用户决定。
        for (String command : List.of("del test.txt", "DEL test.txt", "rm a.txt", "dir & del x", "rmdir /s build")) {
            check(allowAll.check("bash", commandArguments(command)) == null, "用户同意后应放行 " + command);
            check(denyAll.check("bash", commandArguments(command)).startsWith("权限拒绝"), "用户拒绝后应拦截 " + command);
        }
        check(asked.size() == 10, "每次规则命中都应询问用户");

        // 都没命中：直接放行，不打扰用户。
        asked.clear();
        for (String command : List.of("dir", "echo del test.txt", "type model.txt", "git status")) {
            check(denyAll.check("bash", commandArguments(command)) == null, "普通命令应直接放行 " + command);
        }
        check(denyAll.check("write_file", pathArguments("a.md")) == null, "文件工具由路径沙箱负责，不需要审批");
        check(denyAll.check("bash", new JSONObject()) == null, "缺少 command 时交给工具自己报参数错误");
        check(asked.isEmpty(), "未命中规则时不应询问用户");

        // s17 事故之后补的规则。这些都是那次真实运行里模型执行过的命令。
        Path root = Path.of("D:\\dev\\project\\deep in llm\\sandbox");
        Permission guarded = new Permission((tool, arguments, reason) -> !asked.add(reason), root);
        for (String command : List.of(
                "set \"NEWPATH=%USERPROFILE%\\javashim;%PATH%\" & setx PATH \"%NEWPATH%\" >nul",
                "powershell -NoProfile -Command \"[Environment]::SetEnvironmentVariable('Path','x','User')\"",
                "reg add HKCU\\Environment /v Path /d x /f",
                "cd /d \"%USERPROFILE%\" & mkdir javashim 2>nul",
                "copy /y \"javashim\\JavaShim.cs\" \"%USERPROFILE%\\javashim\\JavaShim.cs\" >nul",
                "cd /d \"C:\\Program Files\\Java\\jdk-17\" & copy /y release release.bak >nul",
                "echo test > \"C:\\Program Files\\Java\\jdk-17\\bin\\_wtest.tmp\" 2>&1",
                "echo x > ..\\outside.txt")) {
            check(guarded.check("bash", commandArguments(command)) != null, "应询问用户: " + command);
        }
        asked.clear();
        for (String command : List.of(
                "javac -d out example\\Calc.java example\\CalcTest.java 2>&1",
                "dir /b example 2>nul || echo NO_EXAMPLE_DIR",
                "where java & java -version 2>&1",
                "reg query \"HKCU\\Environment\" /v PATH",
                "type \"C:\\Program Files\\Java\\jdk-17\\release\" 2>nul",
                "echo hi > \"D:\\dev\\project\\deep in llm\\sandbox\\out\\a.txt\"",
                "javac -d build example\\Calc.java && echo COMPILE_OK > build\\ok.txt")) {
            check(guarded.check("bash", commandArguments(command)) == null, "项目内的普通命令不应打扰用户: " + command);
        }
        check(asked.isEmpty(), "项目内命令不应询问用户: " + asked);
    }

    private static void checkBash(Path root) throws Exception {
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root);
        check(agent.executeTool("bash", commandArguments("echo hello")).equals("hello"), "bash 应返回命令输出");
        check(agent.executeTool("bash", commandArguments("echo 你好")).equals("你好"), "bash 应按系统编码读取中文输出");
        check(agent.executeTool("bash", commandArguments("exit 3")).endsWith("（退出码 3）"), "失败命令应带上退出码");
        check(agent.executeTool("bash", commandArguments("exit 0")).equals("（无输出）"), "没有输出时应明确说明");
        checkToolError(agent.executeTool("bash", new JSONObject()), "缺少 command");
    }

    private static void checkPermissionInLoop(Path root) throws Exception {
        Path victim = root.resolve("keep-me.txt");
        Files.writeString(victim, "keep");
        String command = (System.getProperty("os.name").startsWith("Windows") ? "del " : "rm ") + "keep-me.txt";
        int[] rounds = {0};
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root, (tool, arguments, reason) -> false) {
            @Override
            JSONObject callModel(JSONArray input) {
                if (rounds[0]++ == 0) {
                    JSONObject response = toolResponse("unused", "call_bash");
                    JSONObject call = response.getJSONArray("output").getJSONObject(0);
                    call.put("name", "bash");
                    call.put("arguments", JSON.toJSONString(commandArguments(command)));
                    return response;
                }
                check(input.getJSONObject(2).getString("output").startsWith("权限拒绝"), "拒绝原因应回传给模型");
                return finalResponse();
            }
        };
        List<String> afterTool = new ArrayList<>();
        agent.hooks.onPostToolUse((name, arguments, output) -> afterTool.add(name));
        agent.agentLoop(userInput());
        check(rounds[0] == 2, "权限拒绝后循环应继续请求模型");
        check(Files.exists(victim), "被拒绝的命令不能执行");
        check(afterTool.isEmpty(), "被阻止的调用不应触发 PostToolUse");
    }

    private static void checkHooks(Path root) throws Exception {
        // 同一事件按注册顺序执行；PreToolUse 第一个返回非 null 的 hook 生效，后面的不再执行。
        Hooks hooks = new Hooks();
        List<String> calls = new ArrayList<>();
        hooks.onPreToolUse((name, arguments) -> { calls.add("log"); return null; });
        hooks.onPreToolUse((name, arguments) -> { calls.add("block"); return "被 hook 阻止"; });
        hooks.onPreToolUse((name, arguments) -> { calls.add("never"); return null; });
        check("被 hook 阻止".equals(hooks.preToolUse("bash", new JSONObject())), "PreToolUse 应返回阻止原因");
        check(calls.equals(List.of("log", "block")), "阻止后不应继续执行后面的 hook: " + calls);
        check(new Hooks().preToolUse("bash", new JSONObject()) == null, "没有 hook 时应放行");

        // 在循环里：PostToolUse 拿到真实输出；Stop 返回非 null 时追加用户消息并继续请求模型。
        int[] rounds = {0};
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                rounds[0]++;
                if (rounds[0] == 1) {
                    return toolResponse("README.md", "call_read");
                }
                if (rounds[0] == 3) {
                    JSONObject last = input.getJSONObject(input.size() - 1);
                    check("user".equals(last.getString("role")) && "请再确认一遍".equals(last.getString("content")),
                            "Stop hook 的要求应作为用户消息发给模型");
                }
                return finalResponse();
            }
        };
        List<String> outputs = new ArrayList<>();
        agent.hooks.onPostToolUse((name, arguments, output) -> outputs.add(name + ":" + output));
        int[] stops = {0};
        agent.hooks.onStop(history -> stops[0]++ == 0 ? "请再确认一遍" : null);
        agent.agentLoop(userInput());
        check(outputs.equals(List.of("read_file:" + FILE_CONTENT)), "PostToolUse 应收到工具名和真实输出");
        check(stops[0] == 2 && rounds[0] == 3, "Stop hook 拒绝一次后应多请求一轮模型");
    }

    private static void checkTodoWrite() {
        TodoManager manager = new TodoManager();
        check(manager.render().equals("（没有待办）"), "初始应没有待办");
        String rendered = manager.update(todoArguments("""
                [{"content":"读文件","status":"completed"},
                 {"content":"改代码","status":"in_progress"},
                 {"content":"跑测试","status":"pending"}]
                """));
        check(rendered.equals("[x] 读文件\n[>] 改代码\n[ ] 跑测试\n\n（已完成 1/3）"), "待办应按状态渲染: " + rendered);

        // 模型把数组当作字符串传过来也能处理。
        JSONObject asString = new JSONObject();
        asString.put("todos", "[{\"content\":\"只有一项\",\"status\":\"pending\"}]");
        check(manager.update(asString).startsWith("[ ] 只有一项"), "todos 为 JSON 字符串时也应接受");

        List<String> invalid = List.of(
                "[{\"content\":\"a\",\"status\":\"in_progress\"},{\"content\":\"b\",\"status\":\"in_progress\"}]",
                "[{\"content\":\" \",\"status\":\"pending\"}]",
                "[{\"content\":\"a\",\"status\":\"done\"}]",
                "[1]",
                "[" + "{\"content\":\"x\",\"status\":\"pending\"},".repeat(20) + "{\"content\":\"x\",\"status\":\"pending\"}]");
        for (String todos : invalid) {
            try {
                manager.update(todoArguments(todos));
                throw new AssertionError("不合法的待办应被拒绝: " + todos);
            } catch (IllegalArgumentException expected) {
                // 预期
            }
        }
        check(manager.render().startsWith("[ ] 只有一项"), "校验失败时不应改动现有清单");
        try {
            manager.update(new JSONObject());
            throw new AssertionError("缺少 todos 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    private static void checkTodoReminder(Path root) throws Exception {
        // 第 1 轮更新待办，第 2-4 轮只读文件：第 4 轮的工具结果后面应追加一次提醒。
        int[] rounds = {0};
        int[] reminderRound = {0};
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                rounds[0]++;
                JSONObject last = input.getJSONObject(input.size() - 1);
                if ("message".equals(last.getString("type")) && "user".equals(last.getString("role"))
                        && last.getString("content").contains("<reminder>")) {
                    check(reminderRound[0] == 0, "提醒后应重新计数，不应每轮都提醒");
                    reminderRound[0] = rounds[0];
                }
                if (rounds[0] == 1) {
                    JSONObject response = toolResponse("unused", "call_todo");
                    JSONObject call = response.getJSONArray("output").getJSONObject(0);
                    call.put("name", "todo_write");
                    call.put("arguments", JSON.toJSONString(todoArguments(
                            "[{\"content\":\"读 README\",\"status\":\"in_progress\"}]")));
                    return response;
                }
                return rounds[0] <= 4 ? toolResponse("README.md", "call_" + rounds[0]) : finalResponse();
            }
        };
        agent.agentLoop(userInput());
        check(reminderRound[0] == 5, "连续 3 轮没有更新待办后，下一次请求应带上提醒: " + reminderRound[0]);
        check(agent.todos.render().startsWith("[>] 读 README"), "todo_write 应更新 agent 的待办状态");
    }

    private static void checkSkills() throws Exception {
        Path project = Files.createTempDirectory("agent4j-skills-");
        try {
            Path skillsDir = project.resolve("skills");
            String review = """
                    ---
                    name: code-review
                    description: |
                      审查代码质量。
                      用户要求审查代码时使用。
                    ---
                    # 审查步骤
                    先看安全，再看可读性。
                    """;
            Files.createDirectories(skillsDir.resolve("review"));
            Files.writeString(skillsDir.resolve("review/SKILL.md"), review, StandardCharsets.UTF_8);
            // 没有 frontmatter：名称取目录名，描述取正文第一行。
            Files.createDirectories(skillsDir.resolve("sql"));
            Files.writeString(skillsDir.resolve("sql/SKILL.md"), "# SQL 风格指南\n关键字大写。\n", StandardCharsets.UTF_8);
            // 同名技能只保留先扫描到的那个；没有 SKILL.md 的目录和非 UTF-8 文件被跳过。
            Files.createDirectories(skillsDir.resolve("z-dup"));
            Files.writeString(skillsDir.resolve("z-dup/SKILL.md"), "---\nname: sql\n---\n重复\n", StandardCharsets.UTF_8);
            Files.createDirectories(skillsDir.resolve("empty"));
            Files.createDirectories(skillsDir.resolve("bad"));
            Files.write(skillsDir.resolve("bad/SKILL.md"), new byte[]{(byte) 0xc3, 0x28});

            SkillLoader loader = new SkillLoader(skillsDir);
            String catalog = loader.catalog();
            check(catalog.equals("- code-review: 审查代码质量。 用户要求审查代码时使用。\n- sql: SQL 风格指南"),
                    "技能目录应只有名称和一行描述: " + catalog);
            check(loader.load("code-review").equals(review), "load_skill 应返回完整 SKILL.md");
            try {
                loader.load("../README.md");
                throw new AssertionError("未知技能应被拒绝");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("code-review, sql"), "错误应列出可用技能");
            }
            check(new SkillLoader(project.resolve("missing")).catalog().equals("（没有可用技能）"), "没有 skills 目录时应为空");

            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project);
            String instructions = agent.buildRequest(userInput()).getString("instructions");
            check(instructions.contains(catalog), "指令应包含技能目录");
            check(!instructions.contains("先看安全"), "指令不应包含技能正文");
            check(agent.executeTool("load_skill", JSON.parseObject("{\"name\":\"code-review\"}")).equals(review),
                    "load_skill 工具应返回技能全文");
            checkToolError(agent.executeTool("load_skill", JSON.parseObject("{\"name\":\"pdf\"}")), "未知技能");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkCompactor() throws Exception {
        Path project = Files.createTempDirectory("agent4j-compact-");
        try {
            List<String> summarized = new ArrayList<>();
            List<String> events = new ArrayList<>();
            ContextCompactor compactor = new ContextCompactor(project,
                    conversation -> { summarized.add(conversation); return "摘要：读过 README。"; },
                    (step, detail) -> events.add(step));

            // 第 1 步：最新一批结果超过 20 万字符，只把最大的转存，直到回到预算内。
            JSONArray batch = userInput();
            for (int index = 0; index < 5; index++) {
                batch.add(call("big_" + index));
            }
            for (int index = 0; index < 5; index++) {
                batch.add(output("big_" + index, "x".repeat(45_000 + index)));
            }
            compactor.toolResultBudget(batch);
            long persisted = batch.stream().filter(item -> ((JSONObject) item).getString("output") != null
                    && ((JSONObject) item).getString("output").startsWith("<persisted-output>")).count();
            check(persisted == 1, "超出预算时应只转存最大的结果: " + persisted);
            check(batch.getJSONObject(10).getString("output").contains(".task_outputs/tool-results/big_4.txt"),
                    "应转存最大的那条并给出相对路径");
            check(Files.readString(project.resolve(".task_outputs/tool-results/big_4.txt")).length() == 45_004,
                    "磁盘上应保存完整结果");

            // 第 2 步：超过 50 项时归档中间部分，切点不能拆开调用和结果。
            JSONArray longHistory = userInput();
            for (int round = 0; round < 12; round++) {
                longHistory.add(JSON.parseObject("""
                        {"type":"message","role":"assistant","content":[{"type":"output_text","text":"继续"}]}
                        """));
                longHistory.add(call("a" + round));
                longHistory.add(call("b" + round));
                longHistory.add(output("a" + round, "结果"));
                longHistory.add(output("b" + round, "结果"));
            }
            compactor.snip(longHistory);
            check(longHistory.size() <= ContextCompactor.MAX_ITEMS, "归档后不应超过 50 项: " + longHistory.size());
            check(longHistory.getJSONObject(0).getString("content").startsWith("读取 README.md"), "应保留最初的用户请求");
            check(longHistory.getJSONObject(1).getString("content").contains("已归档到 .transcripts/"), "应插入归档标记");
            checkPairs(longHistory);

            // 第 3 步：超过阈值时，模型看过的旧结果换成路径，最近 3 条和未读结果保留。
            JSONArray readHistory = userInput();
            for (int round = 0; round < 12; round++) {
                readHistory.add(call("r" + round));
                readHistory.add(output("r" + round, ("第 " + round + " 个文件").repeat(800)));
            }
            int before = ContextCompactor.estimateChars(readHistory);
            check(before > ContextCompactor.CONTEXT_CHAR_LIMIT, "测试数据应超过阈值");
            compactor.prepare(readHistory, "读取文件");
            check(ContextCompactor.estimateChars(readHistory) <= ContextCompactor.CONTEXT_CHAR_LIMIT, "应降到阈值以下");
            check(readHistory.getJSONObject(2).getString("output").startsWith("[较早的工具结果已保存到 .task_outputs/"),
                    "最早的结果应换成路径");
            check(Files.readString(project.resolve(".task_outputs/tool-results/r0.txt")).startsWith("第 0 个文件"),
                    "被替换的结果应能从磁盘恢复");
            for (int round = 8; round < 12; round++) {
                check(readHistory.getJSONObject(2 + round * 2).getString("output").startsWith("第 " + round),
                        "最近 3 条已读结果和未读结果应保持完整: " + round);
            }
            check(summarized.isEmpty(), "前三步够用时不应调用模型生成摘要");

            // 第 4 步：结构操作压不下来时（比如超长的用户消息），才生成摘要并替换整个历史。
            JSONArray hugeHistory = new JSONArray();
            hugeHistory.add(JSON.parseObject("{\"type\":\"message\",\"role\":\"user\"}"));
            hugeHistory.getJSONObject(0).put("content", "很长的需求".repeat(12_000));
            compactor.prepare(hugeHistory, "原始请求");
            check(summarized.size() == 1, "仍然超限时应调用一次摘要");
            check(hugeHistory.size() == 1, "摘要后历史应只剩一条消息");
            String compacted = hugeHistory.getJSONObject(0).getString("content");
            check(compacted.contains("当前用户请求：\n原始请求") && compacted.contains("摘要：读过 README。")
                    && compacted.contains("完整记录：.transcripts/"), "压缩消息应包含原请求、摘要和 transcript 路径");
            check(events.contains("自动压缩"), "应通知压缩事件: " + events);
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkCompactTool(Path root) throws Exception {
        // 模型同一批里先读文件再要求压缩：先执行完整批次，再用摘要替换历史。
        int[] rounds = {0};
        List<String> summarized = new ArrayList<>();
        Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                if (rounds[0]++ == 0) {
                    JSONObject response = toolResponse("README.md", "call_read");
                    JSONObject compact = call("call_compact");
                    compact.put("name", "compact");
                    compact.put("arguments", "{}");
                    response.getJSONArray("output").add(compact);
                    return response;
                }
                check(input.size() == 1, "压缩后历史应只剩一条消息: " + input.size());
                String content = input.getJSONObject(0).getString("content");
                check(content.startsWith("[模型要求压缩]") && content.contains("读取 README.md，告诉我这个项目做什么。"),
                        "压缩消息应保留当前用户请求");
                return finalResponse();
            }

            @Override
            String summarize(String conversation) {
                summarized.add(conversation);
                return "已读取 README。";
            }
        };
        agent.agentLoop(userInput());
        check(rounds[0] == 2 && summarized.size() == 1, "compact 应在这一批之后触发一次摘要");
        check(summarized.get(0).contains("call_compact") && summarized.get(0).contains(FILE_CONTENT.strip().split("\n")[1]),
                "摘要输入应包含整批调用和结果");
    }

    private static void checkMemory() throws Exception {
        Path project = Files.createTempDirectory("agent4j-memory-");
        try {
            // 规则过滤：只有 persistent、字段完整、没有临时说法、不重复的候选才保存。
            JSONObject tabs = JSON.parseObject("""
                    {"name":"indent-style","type":"user","scope":"persistent",
                     "description":"用户的缩进偏好","body":"用户喜欢用 tab 缩进，不用空格。"}
                    """);
            check(MemoryStore.rejectReason(tabs, List.of()) == null, "合格的候选应被接受");
            JSONObject once = JSON.parseObject("""
                    {"name":"no-files","type":"feedback","scope":"current_task","description":"不要创建文件","body":"不要创建文件。"}
                    """);
            check(MemoryStore.rejectReason(once, List.of()).contains("scope"), "current_task 不应保存");
            JSONObject temporary = JSON.parseObject("""
                    {"name":"no-files","type":"feedback","scope":"persistent","description":"不要创建文件","body":"本次会话不要创建文件。"}
                    """);
            check(MemoryStore.rejectReason(temporary, List.of()).contains("本次会话"), "带临时说法的候选不应保存");
            MemoryStore.Memory old = new MemoryStore.Memory("indent-style.md", "Indent Style", "user", "别的描述", "别的内容");
            check(MemoryStore.rejectReason(tabs, List.of(old)).contains("重复"), "同名记忆不应重复保存");
            check(MemoryStore.slug("Indent Style!") .equals("indent-style") && MemoryStore.slug("../x").equals("x"),
                    "文件名只能由 name 生成安全的 slug");
            check(MemoryStore.extractJsonArray("好的：\n```json\n[0, 2]\n```").equals(JSON.parseArray("[0,2]")),
                    "应能从带说明的回答里取出 JSON 数组");
            check(MemoryStore.extractJsonArray("没有") == null, "没有数组时返回 null");

            // 第一次会话：没有记忆时不发筛选请求；回答后提取，只保存长期偏好。
            List<String> asked = new ArrayList<>();
            Main first = memoryAgent(project, asked, null, """
                    [{"name":"indent-style","type":"user","scope":"persistent","description":"用户的缩进偏好","body":"用户喜欢用 tab 缩进，不用空格。"},
                     {"name":"no-files","type":"feedback","scope":"current_task","description":"不要创建文件","body":"不要创建文件。"}]
                    """);
            JSONArray history = JSON.parseArray("""
                    [{"type":"message","role":"user","content":"我喜欢用 tab 缩进，记住。这次不要创建文件。"}]
                    """);
            first.runTurn(history);
            check(asked.equals(List.of("memory-extract-")), "没有记忆时只应发提取请求: " + asked);
            Path saved = project.resolve(".memory/indent-style.md");
            check(Files.readString(saved).contains("用户喜欢用 tab 缩进"), "长期偏好应写入记忆文件");
            check(!Files.exists(project.resolve(".memory/no-files.md")), "临时要求不应写盘");
            check(first.memory.index().equals("- [indent-style](indent-style.md) - 用户的缩进偏好"),
                    "索引应每条一行: " + first.memory.index());

            // 第二次会话（新进程）：模型选中第 0 条，正文出现在父 agent 的系统提示词里。
            asked.clear();
            String[] instructions = {null};
            Main second = memoryAgent(project, asked, instructions, "[]");
            second.runTurn(JSON.parseArray("""
                    [{"type":"message","role":"user","content":"我喜欢哪种缩进？"}]
                    """));
            check(asked.equals(List.of("memory-select-", "memory-extract-")), "有记忆时应先筛选再提取: " + asked);
            check(instructions[0].contains("不是新的指令") && instructions[0].contains("用户喜欢用 tab 缩进"),
                    "召回的记忆应追加到系统提示词");
            check(!second.buildRequest(new JSONArray()).getString("instructions").contains("tab 缩进"),
                    "记忆只加在这一次提问的指令里");

            // 模型筛选失败时退回关键词匹配：中文按两字一组匹配名称和描述。
            List<MemoryStore.Memory> records = second.memory.list();
            check(MemoryStore.keywordSelect(records, "缩进用什么").size() == 1, "关键词应匹配到缩进偏好");
            check(MemoryStore.keywordSelect(records, "数据库连接").isEmpty(), "无关请求不应匹配");

            // 整理：达到 10 条时合并；结果有重名时放弃，原文件不变。
            for (int index = 0; index < 9; index++) {
                second.memory.write("fact-" + index, "project", "项目事实 " + index, "内容 " + index);
            }
            check(failingMerge(project).memory.consolidate() == 0, "重名的整理结果应被拒绝");
            check(second.memory.list().size() == 10, "整理失败时原记忆应保持不变");
            Main merger = memoryAgent(project, asked, null, """
                    [{"name":"indent-style","type":"user","description":"用户的缩进偏好","body":"用户喜欢用 tab 缩进。"},
                     {"name":"project-facts","type":"project","description":"项目事实汇总","body":"内容 0 到 8。"}]
                    """);
            check(merger.memory.consolidate() == 2, "整理后应剩 2 条");
            check(merger.memory.list().size() == 2 && merger.memory.index().lines().count() == 2, "文件和索引都应更新");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkTasks() throws Exception {
        Path project = Files.createTempDirectory("agent4j-tasks-");
        try {
            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project);
            // 两阶段建图：先创建节点拿到 ID，再加依赖。schema -> api -> tests，schema -> docs。
            String schema = createdId(agent.executeTool("create_task", JSON.parseObject("{\"subject\":\"建表\"}")));
            String api = createdId(agent.executeTool("create_task", JSON.parseObject("{\"subject\":\"写接口\"}")));
            String tests = createdId(agent.executeTool("create_task", JSON.parseObject("{\"subject\":\"写测试\"}")));
            String docs = createdId(agent.executeTool("create_task", JSON.parseObject("{\"subject\":\"写文档\"}")));
            check(Files.exists(project.resolve(".tasks/" + schema + ".json")), "任务应保存为 JSON 文件");
            agent.executeTool("update_task", dependency(api, schema));
            agent.executeTool("update_task", dependency(tests, api));
            agent.executeTool("update_task", dependency(docs, schema));
            check(agent.executeTool("update_task", dependency(docs, schema)).endsWith(schema), "重复添加依赖不应产生重复边");

            checkToolError(agent.executeTool("update_task", dependency(schema, tests)), "循环依赖");
            checkToolError(agent.executeTool("update_task", dependency(api, api)), "依赖自己");
            checkToolError(agent.executeTool("update_task", dependency(api, "task_00000000")), "依赖不存在的任务");
            checkToolError(agent.executeTool("get_task", JSON.parseObject("{\"task_id\":\"../README\"}")), "非法 ID");

            // 依赖没完成不能认领；完成 schema 后，api 和 docs 被解锁，tests 还卡着。
            checkToolError(agent.executeTool("claim_task", taskId(api)), "依赖未完成时认领");
            checkToolError(agent.executeTool("complete_task", taskId(schema)), "未认领就完成");
            check(agent.executeTool("claim_task", taskId(schema)).contains("in_progress"), "无依赖的任务应能认领");
            checkToolError(agent.executeTool("claim_task", taskId(schema)), "重复认领");
            checkToolError(agent.executeTool("update_task", dependency(schema, docs)), "已开始的任务加依赖");
            String completed = agent.executeTool("complete_task", taskId(schema));
            check(completed.contains("已解锁") && completed.contains(api) && completed.contains(docs) && !completed.contains(tests),
                    "完成后应只报告刚解锁的任务: " + completed);
            String listed = agent.executeTool("list_tasks", new JSONObject());
            check(listed.contains("[x] " + schema) && listed.contains("被阻塞：" + api), "列表应显示状态和阻塞原因: " + listed);

            // 跨会话：新的 Main 从磁盘读到同样的任务图。
            Main next = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project);
            check(next.tasks.list().size() == 4 && next.tasks.load(api).blockedBy().equals(List.of(schema)),
                    "新进程应读到磁盘上的任务和依赖");
            check(next.executeTool("get_task", taskId(api)).contains("\"owner\":null"), "get_task 应返回完整 JSON");
            check(next.executeTool("claim_task", taskId(api)).contains("负责人 agent"), "依赖完成后应能认领");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // 用真实子进程跑 DemoMcpServer，走完整的 stdio JSON-RPC。
    private static void checkMcp() throws Exception {
        Path project = Files.createTempDirectory("agent4j-mcp-");
        try {
            Files.createDirectories(project.resolve("docs"));
            Files.writeString(project.resolve("docs/hooks.md"), "# Hooks\nPreToolUse 在工具执行前触发。\n", StandardCharsets.UTF_8);
            String java = ProcessHandle.current().info().command().orElse("java");
            JSONObject servers = new JSONObject();
            for (String kind : List.of("docs", "deploy")) {
                JSONObject server = new JSONObject();
                server.put("command", java);
                server.put("args", List.of("-cp", System.getProperty("java.class.path"), "DemoMcpServer", kind));
                server.put("allow", kind.equals("docs") ? List.of("search", "get_version") : List.of("status"));
                servers.put(kind, server);
            }
            JSONObject config = new JSONObject();
            config.put("mcpServers", servers);
            Files.writeString(project.resolve(".mcp.json"), config.toJSONString(), StandardCharsets.UTF_8);

            List<String> asked = new ArrayList<>();
            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project, (tool, arguments, reason) -> {
                asked.add(tool);
                return false;
            });
            try {
                List<String> before = toolNames(agent);
                check(before.contains("connect_mcp") && before.stream().noneMatch(name -> name.startsWith("mcp__")),
                        "连接前只应有 connect_mcp: " + before);

                String connected = agent.executeTool("connect_mcp", JSON.parseObject("{\"name\":\"docs\"}"));
                check(connected.contains("demo-docs 1.0") && connected.contains("mcp__docs__search（直接执行）"),
                        "连接结果应列出改名后的工具和权限: " + connected);
                check(toolNames(agent).contains("mcp__docs__get_version"), "连接后下一次请求应带上 MCP 工具");
                check(agent.executeTool("connect_mcp", JSON.parseObject("{\"name\":\"docs\"}")).contains("已经连接"),
                        "重复连接应提示已连接");
                checkToolError(agent.executeTool("connect_mcp", JSON.parseObject("{\"name\":\"github\"}")), "未知 server");

                String hits = agent.executeTool("mcp__docs__search", JSON.parseObject("{\"query\":\"pretooluse\"}"));
                check(hits.contains("docs/hooks.md:2:"), "search 应通过 tools/call 真实搜索文档: " + hits);
                check(agent.executeTool("mcp__docs__get_version", new JSONObject()).contains("v2.1.0"), "无参工具应可调用");
                String missing = agent.executeTool("mcp__docs__search", new JSONObject());
                check(missing.startsWith("工具错误: MCP server 报告执行失败"), "isError 结果应标成工具错误: " + missing);

                // 权限来自宿主配置：status 在放行名单里，trigger 不在，即使 server 没说它危险也要问用户。
                agent.executeTool("connect_mcp", JSON.parseObject("{\"name\":\"deploy\"}"));
                JSONObject service = JSON.parseObject("{\"service\":\"web\"}");
                check(agent.hooks.preToolUse("mcp__deploy__status", service) == null, "放行名单里的工具应直接执行");
                String denied = agent.hooks.preToolUse("mcp__deploy__trigger", service);
                check(denied != null && denied.startsWith("权限拒绝") && asked.equals(List.of("mcp__deploy__trigger")),
                        "不在放行名单的 MCP 工具应询问用户");
            } finally {
                agent.mcp.close();
            }
            check(McpManager.normalize("get.version").equals("get_version") && McpManager.normalize("a/b c").equals("a_b_c"),
                    "工具名中不合法的字符应换成下划线");
            check(toolNames(new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", Files.createTempDirectory(project, "empty")))
                    .stream().noneMatch(name -> name.contains("mcp")), "没有 .mcp.json 时不应提供 connect_mcp");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkGoal() throws Exception {
        // 判断器的返回必须结构完整，否则当作判断失败，不能当作完成。
        check(GoalController.parse("```json\n{\"ok\":true,\"reason\":\"退出码 0\"}\n```").getBooleanValue("ok"),
                "应接受带代码块的 JSON");
        for (String bad : List.of("没问题", "{\"reason\":\"x\"}", "{\"ok\":true,\"reason\":\" \"}",
                "{\"ok\":true,\"reason\":\"x\",\"impossible\":true}")) {
            try {
                GoalController.parse(bad);
                throw new AssertionError("不合格的判断结果应被拒绝: " + bad);
            } catch (IllegalStateException expected) {
                // 预期
            }
        }
        JSONArray long_ = userInput();
        long_.add(output("c1", "旧结果"));
        long_.add(output("c2", "开头" + "x".repeat(30_000) + "结尾"));
        String transcript = GoalController.transcript(long_, 1_000);
        check(transcript.length() <= 1_000 && transcript.contains("开头") && transcript.contains("结尾")
                && transcript.contains("中间省略") && !transcript.contains("旧结果"), "最新一条太长时只保留头尾");

        Path project = Files.createTempDirectory("agent4j-goal-");
        try {
            // 第 1 次判断说缺测试结果，第 2 次通过：模型应被自动续跑一轮。
            List<JSONArray> inputs = new ArrayList<>();
            Main agent = goalAgent(project, inputs, List.of(
                    "{\"ok\":false,\"reason\":\"对话里没有测试的退出码\",\"impossible\":false}",
                    "{\"ok\":true,\"reason\":\"exit 0 已出现\",\"impossible\":false}"));
            agent.goal.set("测试退出码为 0");
            agent.runTurn(JSON.parseArray("[{\"type\":\"message\",\"role\":\"user\",\"content\":\"测试退出码为 0\"}]"));
            check(inputs.size() == 2, "未完成时应自动续跑一轮: " + inputs.size());
            JSONObject continuation = inputs.get(1).getJSONObject(inputs.get(1).size() - 1);
            check(continuation.getString("content").startsWith("[目标还没有完成]")
                    && continuation.getString("content").contains("没有测试的退出码"), "续跑消息应带上判断器的原因");
            check(!agent.goal.active() && agent.goal.evaluations() == 2, "通过后目标应结束");
            check(agent.buildRequest(new JSONArray()).getString("instructions").contains("判断器") == false,
                    "目标说明只加在这一次提问的指令里");

            // 一直判定未完成：连续 5 次 block 后第 6 次停下，目标保持有效。
            inputs.clear();
            Main stubborn = goalAgent(project, inputs, List.of("{\"ok\":false,\"reason\":\"还缺结果\"}"));
            stubborn.goal.set("测试退出码为 0");
            stubborn.runTurn(userInput());
            check(inputs.size() == GoalController.BLOCK_CAP + 1 && stubborn.goal.active(),
                    "超过连续上限应停下且不清除目标: " + inputs.size());

            // 判断器出错：不续跑，也不宣称完成。
            inputs.clear();
            Main broken = goalAgent(project, inputs, List.of("这不是 JSON"));
            broken.goal.set("测试退出码为 0");
            broken.runTurn(userInput());
            check(inputs.size() == 1 && broken.goal.active(), "判断失败时应停下并保留目标");

            // 没有目标：和以前一样，模型不调工具就结束，不发判断请求。
            inputs.clear();
            Main plain = goalAgent(project, inputs, List.of());
            plain.runTurn(userInput());
            check(inputs.size() == 1, "没有目标时不应续跑");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkGrep() throws Exception {
        Path project = Files.createTempDirectory("agent4j-grep-");
        try {
            Files.createDirectories(project.resolve("book"));
            StringBuilder novel = new StringBuilder();
            for (int line = 1; line <= 300; line++) {
                novel.append(line % 100 == 1 ? "第" + (line / 100 + 1) + "章 开端" : line % 7 == 0 ? "侯卫东走进办公室，侯卫东坐下。" : "普通的一行 " + line)
                        .append('\n');
            }
            Files.writeString(project.resolve("book/novel.txt"), novel, StandardCharsets.UTF_8);
            Files.writeString(project.resolve("Main.java"), "int x = a.b(1);\n", StandardCharsets.UTF_8);
            Files.writeString(project.resolve(".env"), "TEST_SECRET=侯卫东\n", StandardCharsets.UTF_8);
            Files.write(project.resolve("gbk.txt"), "侯卫东".getBytes(java.nio.charset.Charset.forName("GBK")));
            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project);

            // 字面搜索中文：42 行匹配，每行 2 处；.env 不搜，GBK 文件跳过并说明。
            String counted = agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"侯卫东\",\"count_only\":true}"));
            check(counted.startsWith("找到 42 行匹配，共 84 处，分布在 1 个文件") && counted.contains("book/novel.txt：42 行 / 84 处")
                    && counted.contains("跳过 1 个"), "count_only 应统计行数、次数和分布: " + counted);
            String limited = agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"侯卫东\",\"max_results\":3}"));
            check(limited.contains("book/novel.txt:7: 侯卫东走进办公室") && limited.contains("还有 39 行"),
                    "应返回 路径:行号: 内容，并提示没显示完: " + limited);
            check(!agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"TEST_SECRET\"}")).contains("TEST_SECRET="),
                    "不能搜到密钥文件的内容");

            // 正则、字面特殊字符、上下文、glob 过滤、指定文件。
            check(agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"^第[0-9]+章\",\"regex\":true,\"count_only\":true}"))
                    .startsWith("找到 3 行匹配"), "正则应能找章节标题");
            check(agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"a.b(\"}")).contains("Main.java:1: int x = a.b(1);"),
                    "字面搜索时特殊字符不用转义");
            String withContext = agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"第2章\",\"context\":1}"));
            check(withContext.contains("book/novel.txt-100- 普通的一行 100") && withContext.contains("book/novel.txt:101: 第2章 开端")
                    && withContext.contains("book/novel.txt-102- 普通的一行 102"), "context 应带上前后行: " + withContext);
            check(agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"侯卫东\",\"glob\":\"**/*.java\"}")).startsWith("没有找到"),
                    "glob 应过滤文件");
            check(agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"开端\",\"path\":\"book/novel.txt\",\"count_only\":true}"))
                    .startsWith("找到 3 行匹配"), "path 可以指定单个文件");
            checkToolError(agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"[\",\"regex\":true}")), "错误的正则");
            checkToolError(agent.executeTool("grep", JSON.parseObject("{\"pattern\":\"x\",\"path\":\"..\"}")), "项目外的路径");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkRecovery(Path root) throws Exception {
        // HTTP 层：前两次 429，第三次成功；一直 503 时重试上限后报错；400 且提到上下文长度时识别为超长。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] hits = {0, 0};
        server.createContext("/flaky", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = (hits[0]++ < 2 ? "{\"error\":\"rate limited\"}" : JSON.toJSONString(finalResponse())).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(hits[0] <= 2 ? 429 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/down", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits[1]++;
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/overflow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Main flaky = new Main(TEST_KEY, URI.create(base + "/flaky"), "test-model", root);
            flaky.retryBaseMillis = 5;
            check("completed".equals(flaky.callModel(userInput()).getString("status")) && hits[0] == 3, "429 应退避重试直到成功");
            Main down = new Main(TEST_KEY, URI.create(base + "/down"), "test-model", root);
            down.retryBaseMillis = 5;
            check(catchMessage(() -> down.callModel(userInput())).contains("HTTP 503") && hits[1] == 5, "重试 4 次后仍失败应报错: " + hits[1]);
            Main overflow = new Main(TEST_KEY, URI.create(base + "/overflow"), "test-model", root);
            try {
                overflow.callModel(userInput());
                throw new AssertionError("上下文超长应被识别");
            } catch (Main.PromptTooLongException expected) {
                // 预期
            }
        } finally {
            server.stop(0);
        }

        // 循环层：第一次超长 → 被动压缩后重试同一轮成功。
        int[] calls = {0};
        List<String> summarized = new ArrayList<>();
        Main compacting = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) throws Exception {
                if (calls[0]++ == 0) {
                    throw new Main.PromptTooLongException();
                }
                check(input.size() == 1 && input.getJSONObject(0).getString("content").contains("超长后补救压缩"), "重试前应已压缩历史");
                return finalResponse();
            }

            @Override
            String summarize(String conversation) {
                summarized.add(conversation);
                return "摘要";
            }
        };
        compacting.agentLoop(userInput());
        check(calls[0] == 2 && summarized.size() == 1, "超长时应被动压缩一次再重试");

        // 截断：第一次截断 → 调大上限重试；第二次仍截断但有文字 → 保留文字并请模型接着写；第三次完成。
        List<JSONArray> inputs = new ArrayList<>();
        List<Integer> limits = new ArrayList<>();
        Main truncating = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", root) {
            @Override
            JSONObject callModel(JSONArray input) {
                inputs.add(JSON.parseArray(input.toJSONString()));
                limits.add(buildRequest(input).getIntValue("max_output_tokens"));
                if (inputs.size() <= 2) {
                    JSONObject response = finalResponse("前半段内容");
                    response.put("status", "incomplete");
                    response.put("incomplete_details", JSON.parseObject("{\"reason\":\"max_output_tokens\"}"));
                    return response;
                }
                return finalResponse("后半段内容");
            }
        };
        JSONArray history = userInput();
        String answer = truncating.agentLoop(history);
        check("前半段内容后半段内容".equals(answer), "最终回答应把续写的各段拼起来: " + answer);
        check(limits.equals(List.of(4096, 8192, 8192)), "截断后应把上限调大: " + limits);
        JSONArray third = inputs.get(2);
        check(third.size() == 3 && "前半段内容".equals(third.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
                && third.getJSONObject(2).getString("content").contains("被截断"), "应保留已输出的文字并请模型接着写");
        check(truncating.buildRequest(new JSONArray()).getIntValue("max_output_tokens") == 4096, "正常完成后应恢复默认上限");
    }

    private static void checkTeam() throws Exception {
        Path project = Files.createTempDirectory("agent4j-team-");
        try {
            // 原子认领：8 个线程同时抢同一个任务，只能有一个成功。
            TaskStore store = new TaskStore(project, "agent");
            String contested = store.create("抢手的任务", "").id();
            java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
            List<Thread> racers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String who = "racer" + i;
                Thread thread = new Thread(() -> {
                    try {
                        store.claim(contested, who);
                        winners.incrementAndGet();
                    } catch (Exception lost) {
                        // 被别人抢先
                    }
                });
                racers.add(thread);
                thread.start();
            }
            for (Thread thread : racers) {
                thread.join();
            }
            check(winners.get() == 1, "并发认领只能有一个成功: " + winners.get());
            String second = store.create("第二个任务", "").id();
            String owner = store.load(contested).owner();
            checkToolError("工具错误: " + catchMessage(() -> store.claim(second, owner)), "手上有任务时不能再认领");
            store.complete(contested, owner);

            // 收件箱：读出即消费；等待会被新消息唤醒。
            MessageBus bus = new MessageBus(project, (tag, text) -> { });
            bus.send("alice", "lead", "result", "做完了", null);
            check(bus.read("lead").size() == 1 && bus.read("lead").isEmpty(), "读收件箱应消费掉消息");
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    bus.send("lead", "bob", "message", "开始吧", null);
                } catch (Exception ignored) {
                    // 测试线程
                }
            }).start();
            check(bus.waitFor("bob", 5_000).get(0).content().equals("开始吧"), "等待中的收件人应被唤醒");

            // 完整流程：Lead 启动队友 → 队友干活、完成任务、发 result 和 idle → 空闲时自动认领下一个 ready 任务
            // → Lead 想结束时运行时先等团队事件 → 收到 [团队事件] 再结束 → 关机握手。
            List<String> approvals = Collections.synchronizedList(new ArrayList<>());
            List<JSONArray> leadInputs = Collections.synchronizedList(new ArrayList<>());
            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project, (tool, arguments, reason) -> {
                approvals.add(reason);
                return true;
            }) {
                @Override
                JSONObject callModel(JSONArray input) {
                    String instructions = buildRequest(input).getString("instructions");
                    if (instructions.startsWith("你是团队里的队友")) {
                        // 队友：第一轮读文件，第二轮完成当前任务，第三轮给出总结。
                        JSONObject last = input.getJSONObject(input.size() - 1);
                        String taskId = null;
                        for (int index = input.size() - 1; index >= 0 && taskId == null; index--) {
                            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("task_[0-9a-f]{8}")
                                    .matcher(String.valueOf(input.getJSONObject(index).get("content")));
                            if (matcher.find()) {
                                taskId = matcher.group();
                            }
                        }
                        if ("message".equals(last.getString("type"))) {
                            return toolResponse("README.md", "read_" + taskId);
                        }
                        if (last.getString("call_id").startsWith("read_")) {
                            JSONObject response = JSON.parseObject("{\"status\":\"completed\",\"output\":[]}");
                            JSONObject complete = call("done_" + taskId);
                            complete.put("name", "complete_task");
                            complete.put("arguments", "{\"task_id\":\"" + taskId + "\"}");
                            response.getJSONArray("output").add(complete);
                            return response;
                        }
                        return finalResponse("已完成 " + taskId);
                    }
                    leadInputs.add(JSON.parseArray(input.toJSONString()));
                    if (leadInputs.size() == 1) {
                        JSONObject response = JSON.parseObject("{\"status\":\"completed\",\"output\":[]}");
                        JSONObject spawn = call("spawn_alice");
                        spawn.put("name", "spawn_teammate");
                        spawn.put("arguments", "{\"name\":\"alice\",\"role\":\"读文档\",\"task_id\":\"" + second + "\"}");
                        response.getJSONArray("output").add(spawn);
                        return response;
                    }
                    return finalResponse("等队友的结果。");
                }
            };
            String third = agent.tasks.create("第三个任务", "").id();
            long started = System.currentTimeMillis();
            agent.agentLoop(userInput());
            check(approvals.stream().anyMatch(reason -> reason.contains("启动队友 alice")), "启动队友应先征得用户同意");
            check(agent.tasks.load(second).status().equals("completed") && agent.tasks.load(third).status().equals("completed")
                    && "alice".equals(agent.tasks.load(third).owner()), "队友应完成初始任务，并在空闲时自动认领下一个");
            String delivered = leadInputs.stream().flatMap(input -> input.stream())
                    .map(item -> String.valueOf(((JSONObject) item).get("content")))
                    .filter(text -> text.startsWith("[团队事件]")).reduce("", String::concat);
            check(delivered.contains("alice 的结果（" + second + "）") && delivered.contains("alice 空闲"),
                    "result 和 idle_notification 应作为团队事件送给 Lead: " + delivered);
            check(System.currentTimeMillis() - started < 60_000, "不应卡住");
            check(agent.team.check(agent.team.teammate("alice").profile, "bash") != null,
                    "没有任务的队友不能使用工作区工具");

            // 计划闸门：批准前不能改文件。
            TeamRuntime.Teammate alice = agent.team.teammate("alice");
            alice.taskId = "task_00000000";
            alice.planGate = "pending";
            check(agent.team.check(alice.profile, "write_file") != null && agent.team.check(alice.profile, "read_file") == null,
                    "计划审批前应挡住写操作、放行读操作");
            alice.taskId = null;
            alice.planGate = "not_required";

            agent.team.shutdownAll(10_000);
            check(agent.team.teammate("alice").state.equals("STOPPED"), "关机握手后队友应停止");
            checkToolError(agent.executeTool("spawn_teammate", JSON.parseObject("{\"name\":\"lead\",\"role\":\"x\",\"task_id\":\"" + third + "\"}")),
                    "保留名字");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @FunctionalInterface
    private interface Failing {
        void run() throws Exception;
    }

    private static String catchMessage(Failing action) {
        try {
            action.run();
            return "（没有出错）";
        } catch (Exception exception) {
            return exception.getMessage();
        }
    }

    private static void checkCron() throws Exception {
        // 表达式校验：5 段，每段在范围内。
        for (String good : List.of("* * * * *", "*/5 * * * *", "0 9 * * 1-5", "0,30 8-18 * * *", "0 0 1 1 7")) {
            CronScheduler.validate(good);
        }
        for (String bad : List.of("* * * *", "60 * * * *", "*/0 * * * *", "5-1 * * * *", "0 24 * * *", "a * * * *")) {
            try {
                CronScheduler.validate(bad);
                throw new AssertionError("不合法的表达式应被拒绝: " + bad);
            } catch (IllegalArgumentException expected) {
                // 预期
            }
        }
        java.time.LocalDateTime monday9 = java.time.LocalDateTime.of(2026, 10, 26, 9, 0);
        check(CronScheduler.matches("0 9 * * 1-5", monday9) && !CronScheduler.matches("0 9 * * 1-5", monday9.plusDays(6)),
                "工作日 9 点应只匹配工作日");
        check(CronScheduler.matches("*/5 * * * *", monday9.plusMinutes(5)) && !CronScheduler.matches("*/5 * * * *", monday9.plusMinutes(6)),
                "*/5 应匹配整 5 分钟");
        check(CronScheduler.matches("0 9 1 * 0", monday9.minusDays(1)), "日和星期都限定时，满足其一即匹配（周日）");

        Path project = Files.createTempDirectory("agent4j-cron-");
        try {
            List<String> events = new ArrayList<>();
            CronScheduler cron = new CronScheduler(project, (tag, text) -> events.add(text));
            CronScheduler.Job every = cron.schedule("* * * * *", "统计日志", true, true);
            CronScheduler.Job once = cron.schedule("* * * * *", "提醒一次", false, false);
            cron.poll(monday9);
            cron.poll(monday9.plusSeconds(30));
            List<CronScheduler.Job> due = cron.awaitDue(System.currentTimeMillis() + 100);
            check(due.size() == 2, "同一分钟只应入队一次: " + due.size());
            cron.poll(monday9.plusMinutes(1));
            check(cron.awaitDue(System.currentTimeMillis() + 100).isEmpty(), "未确认交付前不应重复入队");

            // 存盘的任务在“进程重启”后还在；等待交付的会重新入队（至少一次）；内存里的任务消失。
            CronScheduler restarted = new CronScheduler(project, (tag, text) -> events.add(text));
            check(restarted.list().size() == 1 && restarted.list().get(0).id.equals(every.id), "只应恢复存盘的任务");
            check(restarted.awaitDue(System.currentTimeMillis() + 100).size() == 1, "重启前未确认的任务应重新交付");

            cron.acknowledge(every, true);
            cron.acknowledge(once, true);
            check(cron.list().size() == 1, "一次性任务交付后应删除，周期任务保留");
            cron.poll(monday9.plusMinutes(2));
            check(cron.awaitDue(System.currentTimeMillis() + 100).size() == 1, "周期任务确认后下一分钟应再触发");
            checkToolError(new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project)
                    .executeTool("schedule_cron", JSON.parseObject("{\"cron\":\"61 * * * *\",\"prompt\":\"x\"}")), "非法 cron");

            Files.writeString(project.resolve(CronScheduler.FILE), "{坏掉的");
            try {
                new CronScheduler(project, (tag, text) -> { });
                throw new AssertionError("损坏的任务文件应报错");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("损坏"), "错误应说明文件损坏");
            }
            Files.delete(project.resolve(CronScheduler.FILE));

            // 常驻模式：到点的一次性任务作为 [Scheduled] 消息送进同一段对话，交付后没有任务了就提前结束。
            List<JSONArray> inputs = new ArrayList<>();
            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project) {
                @Override
                JSONObject callModel(JSONArray input) {
                    inputs.add(JSON.parseArray(input.toJSONString()));
                    return finalResponse("好的。");
                }
            };
            JSONArray history = userInput();
            agent.agentLoop(history);
            agent.cron.schedule("* * * * *", "检查 CI 状态", false, false);
            agent.cron.poll(java.time.LocalDateTime.now());
            long started = System.currentTimeMillis();
            agent.serve(history, 1);
            agent.cron.close();
            check(inputs.size() == 2 && inputs.get(1).getJSONObject(inputs.get(1).size() - 1).getString("content")
                    .equals("[Scheduled] 检查 CI 状态"), "定时 prompt 应作为新消息送进同一段对话");
            check(inputs.get(1).size() == 3, "定时回合应接着原来的对话: " + inputs.get(1).size());
            check(System.currentTimeMillis() - started < 10_000 && agent.cron.isEmpty(), "没有任务后应提前结束常驻");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkBackground() throws Exception {
        Path project = Files.createTempDirectory("agent4j-background-");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String slow = windows ? "ping -n 3 127.0.0.1 >nul & echo BUILD DONE" : "sleep 2; echo BUILD DONE";
        String forever = windows ? "ping -n 60 127.0.0.1 >nul" : "sleep 60";
        try {
            // 第 1 轮：模型把慢命令放到后台；第 2 轮：模型立刻想结束；第 3 轮：应该收到通知，再给最终回答。
            List<JSONArray> inputs = new ArrayList<>();
            Main agent = new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project) {
                @Override
                JSONObject callModel(JSONArray input) {
                    inputs.add(JSON.parseArray(input.toJSONString()));
                    if (inputs.size() == 1) {
                        JSONObject response = JSON.parseObject("{\"status\":\"completed\",\"output\":[]}");
                        JSONObject call = call("call_bg");
                        call.put("name", "bash");
                        JSONObject arguments = commandArguments(slow);
                        arguments.put("run_in_background", true);
                        call.put("arguments", arguments.toJSONString());
                        response.getJSONArray("output").add(call);
                        return response;
                    }
                    return finalResponse(inputs.size() == 2 ? "构建在后台跑着，我先结束。" : "构建完成。");
                }
            };
            long start = System.nanoTime();
            agent.agentLoop(userInput());
            String placeholder = inputs.get(1).getJSONObject(inputs.get(1).size() - 1).getString("output");
            check(placeholder.startsWith("[后台任务 bg_0001 已启动]"), "后台命令应立刻返回占位结果: " + placeholder);
            check(inputs.size() == 3, "模型想结束时应先等后台结果，再给它一轮: " + inputs.size());
            String notification = inputs.get(2).getJSONObject(inputs.get(2).size() - 1).getString("content");
            check(notification.startsWith("<task_notification>") && notification.contains("<status>completed</status>")
                    && notification.contains("BUILD DONE"), "后台结果应作为通知送达: " + notification);
            check(Files.readString(project.resolve(".task_outputs/background/bg_0001.txt")).contains("BUILD DONE"),
                    "完整输出应保存到磁盘");
            check((System.nanoTime() - start) / 1e9 < 30, "不应一直卡住");

            // 子 agent 不能启动后台任务；程序退出时结束还在跑的后台进程。
            agent.background.allowWhen(() -> false);
            JSONObject backgroundCall = commandArguments(forever);
            backgroundCall.put("run_in_background", true);
            checkToolError(agent.executeTool("bash", backgroundCall), "子 agent 启动后台任务");
            agent.background.allowWhen(() -> true);
            agent.executeTool("bash", backgroundCall);
            check(agent.background.runningCount() == 1, "应有一个后台任务在跑");
            agent.background.close();
            check(agent.background.runningCount() == 0, "关闭时应清理后台任务");
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkWorkflow() throws Exception {
        Path project = Files.createTempDirectory("agent4j-workflow-");
        try {
            // 假的子 agent：正确性发现 1 条（真），边界条件发现 1 条（误报），可读性没发现。
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            java.util.Set<String> failOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.Set<String> badOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();
            WorkflowRuntime.AgentRunner runner = (label, instructions, prompt) -> {
                check(instructions.contains("没有任何工具") && instructions.contains("JSON Schema"), "子 agent 指令应说明没有工具并给出 schema");
                calls.add(label);
                if (failOnce.remove(label)) {
                    throw new java.io.IOException("模拟网络中断");
                }
                if (badOnce.remove(label)) {
                    return "我觉得没问题。";
                }
                if (label.equals("audit:正确性")) {
                    return "```json\n{\"findings\":[{\"title\":\"除零\",\"detail\":\"div 没有检查除数\",\"severity\":\"high\"}]}\n```";
                }
                if (label.equals("audit:边界条件")) {
                    return "{\"findings\":[{\"title\":\"溢出\",\"detail\":\"add 可能溢出\",\"severity\":\"medium\"}]}";
                }
                if (label.startsWith("audit:")) {
                    return "{\"findings\":[]}";
                }
                return "{\"isReal\":" + label.contains("正确性") + ",\"reason\":\"核对过代码\"}";
            };
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            Tool tool = new Workflows(new WorkflowRuntime(project, runner, (tag, text) -> events.add(text))).tool();
            JSONObject args = JSON.parseObject("{\"name\":\"review-changes\",\"args\":{\"changes\":\"int div(int a,int b){return a/b;}\"}}");

            // 正常运行：3 个审计 + 2 个验证，只确认真实的那条。
            JSONObject first = JSON.parseObject(tool.handler().run(args));
            String runId = first.getString("runId");
            check(runId.matches("wf_[0-9a-f]{12}"), "应返回 runId: " + runId);
            check(calls.size() == 5, "应派出 3 个审计和 2 个验证: " + calls);
            JSONArray confirmed = first.getJSONObject("result").getJSONArray("confirmed");
            check(confirmed.size() == 1 && confirmed.getJSONObject(0).getString("title").equals("除零"),
                    "只应保留验证为真的发现: " + confirmed);
            check(Files.readAllLines(project.resolve(".workflows/" + runId + ".journal.jsonl")).size() == 5, "journal 应记录 5 次调用");
            check(Files.exists(project.resolve(".workflows/" + runId + ".output.json"))
                    && !Files.exists(project.resolve(".workflows/" + runId + ".lock")), "应保存输出并释放锁");

            // 原样续跑：全部命中 journal，不请求模型。
            calls.clear();
            JSONObject resumeArgs = JSON.parseObject("{\"name\":\"review-changes\"}");
            resumeArgs.put("resume_from_run_id", runId);
            JSONObject again = JSON.parseObject(tool.handler().run(resumeArgs));
            check(calls.isEmpty() && again.getJSONObject("task").getIntValue("agentsCached") == 5
                    && again.getString("runId").equals(runId), "续跑应全部命中缓存并沿用参数: " + calls);
            // 真实运行中模型续跑时传了空字符串，不能把原来的参数清掉。
            JSONObject blankArgs = JSON.parseObject("{\"name\":\"review-changes\",\"args\":{\"changes\":\"\"}}");
            blankArgs.put("resume_from_run_id", runId);
            JSONObject blank = JSON.parseObject(tool.handler().run(blankArgs));
            check(calls.isEmpty() && blank.getJSONObject("task").getIntValue("agentsCached") == 5,
                    "续跑时空值参数应沿用原来的值: " + calls);

            // 中途失败：可读性的审计断网。错误信息带 runId，续跑时只补跑失败的那一步。
            calls.clear();
            failOnce.add("audit:可读性");
            String failedRun;
            try {
                tool.handler().run(JSON.parseObject("{\"name\":\"review-changes\",\"args\":{\"changes\":\"int sub(int a,int b){return a-b;}\"}}"));
                throw new AssertionError("子 agent 失败时 workflow 应报错");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("可续跑"), "错误应提示可以续跑: " + expected.getMessage());
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("wf_[0-9a-f]{12}").matcher(expected.getMessage());
                check(matcher.find(), "错误里应带 runId");
                failedRun = matcher.group();
            }
            calls.clear();
            JSONObject retry = JSON.parseObject("{\"name\":\"review-changes\"}");
            retry.put("resume_from_run_id", failedRun);
            tool.handler().run(retry);
            check(calls.contains("audit:可读性") && !calls.contains("audit:正确性") && !calls.contains("audit:边界条件"),
                    "续跑只应补跑失败的那一步: " + calls);

            // 输出不符合 schema：提醒一次重试；两次都不合格就失败。
            calls.clear();
            badOnce.add("audit:可读性");
            tool.handler().run(JSON.parseObject("{\"name\":\"review-changes\",\"args\":{\"changes\":\"int mul(int a,int b){return a*b;}\"}}"));
            check(calls.stream().filter("audit:可读性"::equals).count() == 2, "不合格时应重试一次: " + calls);
            check(events.stream().anyMatch(text -> text.contains("输出不合格")), "重试应有进度事件");
            check(WorkflowRuntime.SchemaCheck.validate(JSON.parseObject("{\"findings\":[{\"title\":\"x\",\"detail\":\"y\",\"severity\":\"urgent\"}]}"),
                    Workflows.FINDINGS_SCHEMA, "$") != null, "enum 之外的值应不合格");

            // 锁：另一个进程正在用这个 runId 时不能续跑。
            Files.writeString(project.resolve(".workflows/" + runId + ".lock"), "123");
            checkToolError(new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project) {
            }.executeTool("workflow", resumeArgs), "被锁住的运行");
            checkToolError(new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project)
                    .executeTool("workflow", JSON.parseObject("{\"name\":\"deploy-all\"}")), "未知 workflow");

            check(WorkflowRuntime.key("agent", "a", "p", Workflows.VERDICT_SCHEMA)
                    .equals(WorkflowRuntime.key("agent", "a", "p", Workflows.VERDICT_SCHEMA)), "调用 key 应稳定");
            try {
                WorkflowRuntime.validate(new WorkflowRuntime.Meta("../evil", "x", List.of()));
                throw new AssertionError("不安全的 workflow 名称应被拒绝");
            } catch (WorkflowRuntime.WorkflowException expected) {
                // 预期
            }
        } finally {
            try (var paths = Files.walk(project)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // 模型每轮都直接回答；判断请求按顺序返回 verdicts（用完后重复最后一个），记忆提取返回 []。
    private static Main goalAgent(Path project, List<JSONArray> inputs, List<String> verdicts) throws Exception {
        int[] next = {0};
        return new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project) {
            @Override
            JSONObject callModel(JSONArray input) {
                inputs.add(JSON.parseArray(input.toJSONString()));
                if (inputs.size() == 1) {
                    check(buildRequest(input).getString("instructions").contains("判断器") == goal.active(),
                            "有目标时（且只有这时）指令应提醒写出验证结果");
                }
                return finalResponse("做完了。");
            }

            @Override
            String ask(String traceTag, String title, String instructions, String text, int maxOutputTokens) {
                if (!traceTag.equals("goal-")) {
                    return "[]";
                }
                check(verdicts.size() > 0, "没有目标时不应调用判断器");
                check(text.contains("completion_condition") && text.contains("做完了。"), "判断器应收到条件和对话");
                return verdicts.get(Math.min(next[0]++, verdicts.size() - 1));
            }
        };
    }

    private static List<String> toolNames(Main agent) {
        JSONArray tools = agent.buildRequest(new JSONArray()).getJSONArray("tools");
        List<String> names = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            names.add(tools.getJSONObject(index).getString("name"));
        }
        return names;
    }

    private static String createdId(String output) {
        check(output.startsWith("已创建 task_"), "create_task 应返回 ID: " + output);
        return output.substring(4, 17);
    }

    private static JSONObject taskId(String id) {
        JSONObject arguments = new JSONObject();
        arguments.put("task_id", id);
        return arguments;
    }

    private static JSONObject dependency(String id, String blockedBy) {
        JSONObject arguments = taskId(id);
        arguments.put("addBlockedBy", JSONArray.of(blockedBy));
        return arguments;
    }

    // 模型直接给最终回答；筛选请求选第 0 条，提取和整理请求返回 reply。
    private static Main memoryAgent(Path project, List<String> asked, String[] instructions, String reply) throws Exception {
        return new Main(TEST_KEY, UNUSED_ENDPOINT, "test-model", project) {
            @Override
            JSONObject callModel(JSONArray input) {
                if (instructions != null) {
                    instructions[0] = buildRequest(input).getString("instructions");
                }
                return finalResponse("好的。");
            }

            @Override
            String ask(String traceTag, String title, String instructions, String text, int maxOutputTokens) {
                asked.add(traceTag);
                return traceTag.equals("memory-select-") ? "[0]" : reply;
            }
        };
    }

    private static Main failingMerge(Path project) throws Exception {
        return memoryAgent(project, new ArrayList<>(), null, """
                [{"name":"a","type":"user","description":"x","body":"y"},{"name":"A","type":"user","description":"z","body":"w"}]
                """);
    }

    // 每个工具结果都必须能在它前面找到同 call_id 的调用。
    private static void checkPairs(JSONArray history) {
        List<String> calls = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            JSONObject item = history.getJSONObject(index);
            if ("function_call".equals(item.getString("type"))) {
                calls.add(item.getString("call_id"));
            } else if ("function_call_output".equals(item.getString("type"))) {
                check(calls.contains(item.getString("call_id")), "不应留下孤立的工具结果: " + item.getString("call_id"));
            }
        }
    }

    private static JSONObject call(String callId) {
        JSONObject call = toolResponse("README.md", callId).getJSONArray("output").getJSONObject(0);
        call.put("id", "fc_" + callId);
        return call;
    }

    private static JSONObject output(String callId, String text) {
        JSONObject output = new JSONObject();
        output.put("type", "function_call_output");
        output.put("call_id", callId);
        output.put("output", text);
        return output;
    }

    private static JSONObject todoArguments(String todos) {
        JSONObject arguments = new JSONObject();
        arguments.put("todos", JSON.parseArray(todos));
        return arguments;
    }

    private static JSONObject commandArguments(String command) {
        JSONObject arguments = new JSONObject();
        arguments.put("command", command);
        return arguments;
    }

    private static JSONObject globArguments(String pattern) {
        JSONObject arguments = new JSONObject();
        arguments.put("pattern", pattern);
        return arguments;
    }

    private static void checkHttpRoundTrip(Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JSONObject> requests = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/responses", exchange -> {
            JSONObject captured = new JSONObject();
            captured.put("method", exchange.getRequestMethod());
            captured.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            captured.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
            captured.put("body", JSON.parseObject(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            requests.add(captured);
            JSONObject response = requests.size() == 1
                    ? toolResponse("README.md", "call_read") : finalResponse();
            byte[] body = JSON.toJSONBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/unauthorized", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // 模拟服务端在错误正文中回显 key；客户端异常也不能暴露它。
            byte[] body = ("{\"error\":\"Unauthorized " + TEST_KEY + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Main agent = new Main(TEST_KEY, URI.create(baseUrl + "/responses"), "test-model", root);
            JSONArray history = userInput();
            agent.agentLoop(history);
            check(requests.size() == 2, "一次工具调用应产生两轮真实 HTTP 请求");
            for (JSONObject captured : requests) {
                check("POST".equals(captured.getString("method")), "模型请求必须使用 POST");
                check(("Bearer " + TEST_KEY).equals(captured.getString("authorization")),
                        "模型请求应使用 Bearer 认证");
                check(captured.getString("contentType").startsWith("application/json"),
                        "模型请求应声明 JSON 内容类型");
                check("test-model".equals(captured.getJSONObject("body").getString("model")),
                        "模型请求应使用配置的 model");
            }
            JSONArray firstInput = requests.get(0).getJSONObject("body").getJSONArray("input");
            check(firstInput.size() == 1, "首次请求应包含用户问题");
            check("读取 README.md，告诉我这个项目做什么。".equals(
                    firstInput.getJSONObject(0).getString("content")), "请求中文应以 UTF-8 发送");
            JSONArray secondInput = requests.get(1).getJSONObject("body").getJSONArray("input");
            check(secondInput.size() == 3, "第二轮应回传用户问题、模型调用和工具结果");
            JSONObject result = secondInput.getJSONObject(2);
            check("function_call_output".equals(result.getString("type")), "工具结果 item 类型错误");
            check("call_read".equals(result.getString("call_id")), "工具结果应与调用 ID 配对");
            check(FILE_CONTENT.equals(result.getString("output")), "真实文件内容应完整回传模型");
            check(history.size() == 4, "真实 HTTP 闭环应保存最终回答");

            Main unauthorized = new Main(TEST_KEY, URI.create(baseUrl + "/unauthorized"),
                    "test-model", root);
            try {
                unauthorized.callModel(userInput());
                throw new AssertionError("HTTP 401 不能被当作模型响应");
            } catch (Exception expected) {
                String message = String.valueOf(expected.getMessage());
                check(message.contains("401"), "HTTP 错误应包含状态码");
                check(!message.contains(TEST_KEY), "HTTP 错误不能泄漏 API Key");
            }
        } finally {
            server.stop(0);
        }
    }

    private static JSONArray userInput() {
        return JSON.parseArray("""
                [{"type":"message","role":"user","content":"读取 README.md，告诉我这个项目做什么。"}]
                """);
    }

    private static JSONObject pathArguments(String path) {
        JSONObject arguments = new JSONObject();
        arguments.put("path", path);
        return arguments;
    }

    private static JSONObject toolResponse(String path, String callId) {
        JSONObject response = JSON.parseObject("""
                {"status":"completed","output":[
                  {"type":"function_call","id":"fc_read","name":"read_file"}
                ]}
                """);
        JSONObject call = response.getJSONArray("output").getJSONObject(0);
        call.put("call_id", callId);
        call.put("arguments", JSON.toJSONString(pathArguments(path)));
        return response;
    }

    private static JSONObject finalResponse() {
        return finalResponse("读取完成。");
    }

    private static JSONObject finalResponse(String text) {
        JSONObject response = JSON.parseObject("""
                {"status":"completed","output":[
                  {"type":"message","role":"assistant",
                   "content":[{"type":"output_text"}]}
                ]}
                """);
        response.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text", text);
        return response;
    }

    private static void checkToolError(String output, String context) {
        check(output != null && output.startsWith("工具错误:"), context + " 应作为工具错误文本返回");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

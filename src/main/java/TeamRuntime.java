import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * s13 的团队运行时：Lead（父 agent）加若干常驻队友。
 *
 * 和 s06 子 agent 的区别：子 agent 跑一次就结束；队友在自己的线程里一直活着，在 WORK 和 IDLE 之间切换，
 * 跨任务保留自己的对话历史，通过收件箱和 Lead 双向通信，空闲时自己去共享任务板认领 ready 的任务。
 *
 * 协议：
 *   完成一项工作后发两个事件：result（产出了什么）和 idle_notification（我能接新活了）；
 *   关机：shutdown_request(request_id) → 队友收尾 → shutdown_response(request_id)；
 *   计划审批：require_plan 的队友先 submit_plan，Lead 用 review_plan 批准前，bash / write_file / edit_file 被挡住。
 * 课程里的 git worktree 没有做：所有队友共用项目目录。
 */
final class TeamRuntime {
    static final String LEAD = "lead";
    static final Set<String> RESERVED = Set.of(LEAD, "agent");
    static final Set<String> WORKSPACE_TOOLS = Set.of("read_file", "write_file", "edit_file", "glob", "grep", "bash");
    static final Set<String> MUTATING_TOOLS = Set.of("write_file", "edit_file", "bash");
    static final long IDLE_SCAN_MILLIS = 2_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    interface LoopRunner {
        String run(JSONArray history, Main.Profile profile) throws Exception;
    }

    @FunctionalInterface
    interface ProfileFactory {
        Main.Profile create(String name, String instructions, Map<String, Tool> tools);
    }

    static final class Teammate {
        final String name;
        final String role;
        final JSONArray history = new JSONArray();
        volatile Main.Profile profile;
        volatile String state = "WORK";
        // 当前负责的任务。认领时设置；任务完成后回到 IDLE 时释放。没有任务时不能用工作区工具。
        volatile String taskId;
        // not_required / required / pending / approved / rejected
        volatile String planGate;
        Thread thread;

        Teammate(String name, String role, boolean requirePlan) {
            this.name = name;
            this.role = role;
            this.planGate = requirePlan ? "required" : "not_required";
        }
    }

    // 带 request_id 的控制请求：回复用 id 找到原请求，类型对不上或已经处理过的回复不生效。
    record Request(String id, String type, String target, String payload) {
    }

    private final TaskStore tasks;
    private final MessageBus bus;
    private final LoopRunner runner;
    private final ProfileFactory profiles;
    private final Map<String, Tool> baseTools;
    private final String teammateNote;
    private final Permission.Approver approver;
    private final BiConsumer<String, String> events;
    private final Map<String, Teammate> teammates = new LinkedHashMap<>();
    private final Map<String, Request> pending = new ConcurrentHashMap<>();
    private final Map<String, Teammate> byProfileName = new ConcurrentHashMap<>();

    TeamRuntime(TaskStore tasks, MessageBus bus, LoopRunner runner, ProfileFactory profiles, Map<String, Tool> baseTools,
                String teammateNote, Permission.Approver approver, BiConsumer<String, String> events) {
        this.tasks = tasks;
        this.bus = bus;
        this.runner = runner;
        this.profiles = profiles;
        this.baseTools = baseTools;
        this.teammateNote = teammateNote;
        this.approver = approver;
        this.events = events;
    }

    Teammate teammateFor(Main.Profile profile) {
        return profile == null ? null : byProfileName.get(profile.name());
    }

    synchronized Teammate teammate(String name) {
        return teammates.get(name);
    }

    // ---------- 工具分发前的闸门 ----------

    // 队友调工具前检查：没有认领任务不能碰工作区；计划没批准不能执行命令或写文件。返回 null 表示放行。
    String check(Main.Profile profile, String toolName) {
        Teammate teammate = teammateFor(profile);
        if (teammate == null) {
            return null;
        }
        if (WORKSPACE_TOOLS.contains(toolName) && teammate.taskId == null) {
            return "工具错误: 你还没有认领任务，不能使用 " + toolName + "。先用 list_tasks 和 claim_task 认领一个任务。";
        }
        if (MUTATING_TOOLS.contains(toolName) && !Set.of("not_required", "approved").contains(teammate.planGate)) {
            return "工具错误: 计划状态是 " + teammate.planGate + "，计划获批前不能使用 " + toolName
                    + "。先用 submit_plan 提交计划，等 Lead 批准。";
        }
        return null;
    }

    // ---------- Lead 的工具 ----------

    List<Tool> leadTools() {
        return List.of(
                new Tool("spawn_teammate", "启动一个常驻队友并把一个任务交给它。先用 create_task 建好任务，task_id 必须是 ready 的任务。"
                        + "启动需要用户确认。启动后结束本轮即可，队友的结果会作为 [团队事件] 自动送来，不用轮询。",
                        JSON.parseObject("""
                                {"type": "object", "required": ["name", "role", "task_id"],
                                 "properties": {
                                   "name": {"type": "string", "description": "队友名，小写字母开头，如 alice"},
                                   "role": {"type": "string", "description": "这个队友的职责"},
                                   "task_id": {"type": "string", "description": "初始任务 ID"},
                                   "require_plan": {"type": "boolean", "description": "是否要求先提交计划、批准后才能改文件和执行命令"}}}
                                """),
                        arguments -> spawn(FileTools.requireString(arguments, "name"), FileTools.requireString(arguments, "role"),
                                FileTools.requireString(arguments, "task_id"), Boolean.TRUE.equals(arguments.get("require_plan")))),
                sendTool(LEAD),
                new Tool("list_teammates", "列出队友的状态（WORK / IDLE / STOPPED）、当前任务和计划状态。",
                        JSON.parseObject("{\"type\": \"object\", \"properties\": {}}"), arguments -> describe()),
                new Tool("shutdown_teammate", "请一个队友收尾后关机（发送带 request_id 的关机请求，队友回复后才算关机）。",
                        JSON.parseObject("{\"type\": \"object\", \"required\": [\"name\"], \"properties\": {\"name\": {\"type\": \"string\"}}}"),
                        arguments -> requestShutdown(FileTools.requireString(arguments, "name"))),
                new Tool("review_plan", "审批队友提交的计划。request_id 来自 [团队事件] 里的 plan_approval_request。",
                        JSON.parseObject("""
                                {"type": "object", "required": ["request_id", "approve"],
                                 "properties": {"request_id": {"type": "string"}, "approve": {"type": "boolean"},
                                                "feedback": {"type": "string"}}}
                                """),
                        arguments -> reviewPlan(FileTools.requireString(arguments, "request_id"),
                                Boolean.TRUE.equals(arguments.get("approve")),
                                arguments.getString("feedback") == null ? "" : arguments.getString("feedback"))));
    }

    private Tool sendTool(String from) {
        return new Tool("send_message", "给 lead 或某个队友发一条消息。对方空闲时会被唤醒。",
                JSON.parseObject("""
                        {"type": "object", "required": ["to", "content"],
                         "properties": {"to": {"type": "string"}, "content": {"type": "string"}}}
                        """),
                arguments -> {
                    String to = FileTools.requireString(arguments, "to");
                    if (!to.equals(LEAD) && teammate(to) == null) {
                        throw new IllegalArgumentException("没有这个队友: " + to);
                    }
                    bus.send(from, to, "message", FileTools.requireString(arguments, "content"), null);
                    return "已发送给 " + to;
                });
    }

    String spawn(String name, String role, String taskId, boolean requirePlan) throws IOException {
        if (!MessageBus.validName(name) || RESERVED.contains(name)) {
            throw new IllegalArgumentException("队友名必须是小写字母开头的短名字，且不能是 lead / agent: " + name);
        }
        synchronized (this) {
            if (teammates.containsKey(name)) {
                throw new IllegalArgumentException("已经有叫 " + name + " 的队友了");
            }
        }
        // 启动队友会增加成本和能修改工作区的角色，由用户确认。
        if (!approver.approve("spawn_teammate", JSON.parseObject("{\"name\":\"" + name + "\"}"),
                "启动队友 " + name + "（" + role + "），初始任务 " + taskId + (requirePlan ? "，需先审批计划" : ""))) {
            throw new IllegalArgumentException("用户没有同意启动队友 " + name);
        }
        // 先认领初始任务，认领失败就不启动线程。
        tasks.claim(taskId, name);
        TaskStore.Task task = tasks.load(taskId);
        Teammate teammate = new Teammate(name, role, requirePlan);
        teammate.taskId = taskId;
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (String base : List.of("read_file", "write_file", "edit_file", "glob", "grep", "bash")) {
            tools.put(base, baseTools.get(base));
        }
        for (Tool tool : tasks.teammateTools(name)) {
            tools.put(tool.name(), tool);
        }
        Tool send = sendTool(name);
        tools.put(send.name(), send);
        Tool plan = submitPlanTool(teammate);
        tools.put(plan.name(), plan);
        String instructions = "你是团队里的队友 " + name + "，职责：" + role + "。你的工作来自共享任务板，由 Lead 协调。"
                + "做完当前任务后务必调用 complete_task，然后用几句话总结做了什么、结果如何，作为最终回答（会自动作为 result 发给 Lead）。"
                + "需要别人配合或遇到问题时用 send_message 告诉 lead。不要做职责和任务以外的事。"
                + (requirePlan ? "你需要先用 submit_plan 提交计划，Lead 批准前不能执行命令、写文件或改文件。" : "")
                + teammateNote;
        teammate.profile = profiles.create(name, instructions, tools);
        // 写明“已经替你认领好了”：真实运行里队友曾在第一轮又去 claim_task，得到一个失败。
        teammate.history.add(userMessage("[分配给你的任务 " + task.id() + "，已经替你认领好，直接开始做] " + task.subject()
                + (task.description().isBlank() ? "" : "\n" + task.description())));
        synchronized (this) {
            teammates.put(name, teammate);
            byProfileName.put(teammate.profile.name(), teammate);
        }
        teammate.thread = new Thread(() -> lifecycle(teammate), "teammate-" + name);
        teammate.thread.setDaemon(true);
        teammate.thread.start();
        events.accept("团队", "启动队友 " + name + "（" + role + "），认领 " + taskId);
        return "已启动队友 " + name + "，它已认领 " + taskId + "（" + task.subject() + "）。结果会作为 [团队事件] 自动送来。";
    }

    private Tool submitPlanTool(Teammate teammate) {
        return new Tool("submit_plan", "向 Lead 提交工作计划，等待批准。批准前不能执行命令或改文件。",
                JSON.parseObject("{\"type\": \"object\", \"required\": [\"plan\"], \"properties\": {\"plan\": {\"type\": \"string\"}}}"),
                arguments -> {
                    String plan = FileTools.requireString(arguments, "plan");
                    String id = requestId();
                    pending.put(id, new Request(id, "plan_approval", teammate.name, plan));
                    teammate.planGate = "pending";
                    JSONObject metadata = new JSONObject();
                    metadata.put("request_id", id);
                    metadata.put("task_id", teammate.taskId);
                    bus.send(teammate.name, LEAD, "plan_approval_request", plan, metadata);
                    return "计划已提交（" + id + "），等待 Lead 审批。审批结果到达前你可以结束这一轮。";
                });
    }

    String requestShutdown(String name) throws IOException {
        Teammate teammate = teammate(name);
        if (teammate == null || "STOPPED".equals(teammate.state)) {
            throw new IllegalArgumentException("没有正在运行的队友: " + name);
        }
        String id = requestId();
        pending.put(id, new Request(id, "shutdown", name, ""));
        JSONObject metadata = new JSONObject();
        metadata.put("request_id", id);
        bus.send(LEAD, name, "shutdown_request", "请收尾后关机。", metadata);
        return "已向 " + name + " 发送关机请求 " + id + "，它回复后才算关机。";
    }

    String reviewPlan(String requestId, boolean approve, String feedback) throws IOException {
        Request request = pending.get(requestId);
        if (request == null || !request.type().equals("plan_approval")) {
            throw new IllegalArgumentException("没有待审批的计划请求: " + requestId);
        }
        pending.remove(requestId);
        JSONObject metadata = new JSONObject();
        metadata.put("request_id", requestId);
        metadata.put("approve", approve);
        bus.send(LEAD, request.target(), "plan_approval_response", feedback.isBlank() ? (approve ? "批准" : "驳回") : feedback, metadata);
        return (approve ? "已批准 " : "已驳回 ") + request.target() + " 的计划（" + requestId + "）";
    }

    synchronized String describe() {
        if (teammates.isEmpty()) {
            return "还没有队友。";
        }
        List<String> lines = new ArrayList<>();
        for (Teammate teammate : teammates.values()) {
            lines.add(teammate.name + "  " + teammate.state + "  任务 " + (teammate.taskId == null ? "无" : teammate.taskId)
                    + "  计划 " + teammate.planGate + "  职责：" + teammate.role);
        }
        return String.join("\n", lines);
    }

    // ---------- 队友的生命周期：WORK → IDLE → WORK ……直到关机 ----------

    private void lifecycle(Teammate teammate) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // WORK：用自己的历史和 Profile 跑一次循环。
                teammate.state = "WORK";
                String answer;
                try {
                    answer = runner.run(teammate.history, teammate.profile);
                    if (answer == null) {
                        answer = "（达到轮数上限，没有给出总结）";
                    }
                } catch (Exception exception) {
                    answer = "工作出错：" + exception.getMessage();
                }
                // result 和 idle_notification 是两个事件：一个说产出了什么，一个说能不能接新活。
                JSONObject metadata = new JSONObject();
                metadata.put("task_id", teammate.taskId);
                bus.send(teammate.name, LEAD, "result", answer.isBlank() ? "（没有总结）" : answer, metadata);
                releaseIfCompleted(teammate);
                teammate.state = "IDLE";
                bus.send(teammate.name, LEAD, "idle_notification", "等待更多工作。", null);
                if (!idle(teammate)) {
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            events.accept("团队", teammate.name + " 收件箱读写失败，停止：" + exception.getMessage());
        } finally {
            teammate.state = "STOPPED";
        }
    }

    // IDLE：先看收件箱（关机、审批、Lead 的指令优先），没有消息再去任务板找 ready 的任务。
    // 返回 true 表示有新工作，回到 WORK；false 表示关机。
    private boolean idle(Teammate teammate) throws IOException, InterruptedException {
        while (true) {
            List<MessageBus.Message> inbox = bus.waitFor(teammate.name, IDLE_SCAN_MILLIS);
            boolean work = false;
            for (MessageBus.Message message : inbox) {
                String requestId = message.metadata() == null ? null : message.metadata().getString("request_id");
                switch (message.type()) {
                    case "shutdown_request" -> {
                        JSONObject metadata = new JSONObject();
                        metadata.put("request_id", requestId);
                        bus.send(teammate.name, LEAD, "shutdown_response", "同意关机。", metadata);
                        return false;
                    }
                    case "plan_approval_response" -> {
                        boolean approved = message.metadata().getBooleanValue("approve");
                        teammate.planGate = approved ? "approved" : "rejected";
                        teammate.history.add(userMessage("[计划审批] " + (approved ? "已批准" : "被驳回") + "：" + message.content()
                                + (approved ? "\n现在可以按计划执行。" : "\n请修改计划后重新 submit_plan。")));
                        work = true;
                    }
                    default -> {
                        teammate.history.add(userMessage("[来自 " + message.from() + " 的消息] " + message.content()));
                        work = true;
                    }
                }
            }
            if (work) {
                return true;
            }
            if (teammate.taskId == null) {
                TaskStore.Task task = tasks.claimNextReady(teammate.name);
                if (task != null) {
                    teammate.taskId = task.id();
                    events.accept("团队", teammate.name + " 空闲中自动认领 " + task.id() + "（" + task.subject() + "）");
                    teammate.history.add(userMessage("[自动认领的任务 " + task.id() + "，已经替你认领好，直接开始做] " + task.subject()
                            + (task.description().isBlank() ? "" : "\n" + task.description())));
                    return true;
                }
            }
        }
    }

    private void releaseIfCompleted(Teammate teammate) {
        if (teammate.taskId == null) {
            return;
        }
        try {
            if ("completed".equals(tasks.load(teammate.taskId).status())) {
                teammate.taskId = null;
            }
        } catch (IOException | IllegalArgumentException exception) {
            teammate.taskId = null;
        }
    }

    // ---------- Lead 一侧：运行时投递团队事件 ----------

    // 取走 Lead 收件箱里的所有消息，先更新协议状态，再整理成一条 [团队事件] 文本。没有消息返回 null。
    String consumeLeadInbox() throws IOException {
        List<MessageBus.Message> messages = bus.read(LEAD);
        if (messages.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (MessageBus.Message message : messages) {
            String requestId = message.metadata() == null ? null : message.metadata().getString("request_id");
            if (message.type().equals("shutdown_response")) {
                Request request = requestId == null ? null : pending.get(requestId);
                // 用 request_id 找到原请求；类型或对象对不上的回复不生效。
                if (request != null && request.type().equals("shutdown") && request.target().equals(message.from())) {
                    pending.remove(requestId);
                    lines.add("- " + message.from() + " 已关机（" + requestId + "）");
                }
                continue;
            }
            String task = message.metadata() == null ? null : message.metadata().getString("task_id");
            lines.add(switch (message.type()) {
                case "result" -> "- " + message.from() + " 的结果" + (task == null ? "" : "（" + task + "）") + "：\n" + indent(message.content());
                case "idle_notification" -> "- " + message.from() + " 空闲，可以接新任务";
                case "plan_approval_request" -> "- " + message.from() + " 提交了计划，等你用 review_plan 审批（request_id " + requestId + "）：\n"
                        + indent(message.content());
                default -> "- " + message.from() + " 的消息：" + message.content();
            });
        }
        return lines.isEmpty() ? null : "[团队事件]\n" + String.join("\n", lines);
    }

    // Lead 想结束时用：还有队友在干活、收件箱里有没处理的事件、或者有 ready 任务且有空闲队友能接，就先别结束。
    synchronized boolean busy() throws IOException {
        if (bus.hasMessages(LEAD)) {
            return true;
        }
        boolean idleFree = false;
        for (Teammate teammate : teammates.values()) {
            if ("WORK".equals(teammate.state)) {
                return true;
            }
            idleFree |= "IDLE".equals(teammate.state) && teammate.taskId == null;
        }
        if (idleFree) {
            for (TaskStore.Task task : tasks.list()) {
                if ("pending".equals(task.status()) && task.owner() == null && tasks.incompleteDependencies(task).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    // 等 Lead 收件箱来消息，最多 timeoutMillis。返回是否等到了。
    boolean awaitLeadEvents(long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (bus.hasMessages(LEAD)) {
                return true;
            }
            if (!busy()) {
                return false;
            }
            Thread.sleep(200);
        }
        return bus.hasMessages(LEAD);
    }

    // 程序结束时：给还在运行的队友发关机请求，等它们回复，最后中断线程。
    void shutdownAll(long waitMillis) {
        List<Teammate> running = new ArrayList<>();
        synchronized (this) {
            for (Teammate teammate : teammates.values()) {
                if (!"STOPPED".equals(teammate.state)) {
                    running.add(teammate);
                }
            }
        }
        for (Teammate teammate : running) {
            try {
                requestShutdown(teammate.name);
            } catch (IOException | IllegalArgumentException ignored) {
                // 已经停了
            }
        }
        long deadline = System.currentTimeMillis() + waitMillis;
        for (Teammate teammate : running) {
            try {
                teammate.thread.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            if (teammate.thread.isAlive()) {
                teammate.thread.interrupt();
                events.accept("团队", teammate.name + " 没有及时回复关机请求，已中断");
            }
        }
        if (!running.isEmpty()) {
            events.accept("团队", "已关闭 " + running.size() + " 个队友");
        }
    }

    private static String requestId() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "req_" + HexFormat.of().formatHex(bytes);
    }

    private static String indent(String text) {
        return "  " + text.strip().replace("\n", "\n  ");
    }

    private static JSONObject userMessage(String text) {
        JSONObject message = new JSONObject();
        message.put("type", "message");
        message.put("role", "user");
        message.put("content", text);
        return message;
    }
}

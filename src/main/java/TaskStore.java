import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s10 的任务系统。每个任务是 .tasks/task_xxxxxxxx.json 一个文件，有状态、负责人（owner）和前置任务（blockedBy）。
 * 和 s05 的 todo_write 不同：它存在磁盘上，跨会话保留；任务之间有依赖，依赖没完成的任务不能认领。
 *
 * 状态机：pending --claim_task--> in_progress --complete_task--> completed
 */
final class TaskStore {
    static final List<String> STATUSES = List.of("pending", "in_progress", "completed");
    private static final Pattern ID = Pattern.compile("task_[0-9a-f]{8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Task(String id, String subject, String description, String status, String owner, List<String> blockedBy) {
        Task with(String status, String owner, List<String> blockedBy) {
            return new Task(id, subject, description, status, owner, blockedBy);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("subject", subject);
            json.put("description", description);
            json.put("status", status);
            json.put("owner", owner);
            json.put("blockedBy", blockedBy);
            return json;
        }
    }

    private final Path dir;
    // 这个 agent 认领任务时写进 owner 的名字。s10 只有一个 agent；到多 agent 协作时，靠它区分谁在做哪个任务。
    private final String owner;

    TaskStore(Path root, String owner) {
        this.dir = root.resolve(".tasks");
        this.owner = owner;
    }

    // ---------- 存储 ----------

    // ID 随机生成，用 CREATE_NEW 写入：文件已存在就换一个 ID，不会覆盖别的任务。
    Task create(String subject, String description) throws IOException {
        if (subject.isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        Files.createDirectories(dir);
        for (int attempt = 0; attempt < 100; attempt++) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            Task task = new Task("task_" + HexFormat.of().formatHex(bytes), subject.strip(), description.strip(),
                    "pending", null, List.of());
            try {
                Files.writeString(path(task.id()), json(task), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return task;
            } catch (FileAlreadyExistsException exception) {
                // 极少见的 ID 冲突，换一个再试
            }
        }
        throw new IOException("分配不到唯一的任务 ID");
    }

    Task load(String id) throws IOException {
        JSONObject json;
        try {
            json = JSON.parseObject(Files.readString(path(id), StandardCharsets.UTF_8));
        } catch (NoSuchFileException exception) {
            throw new IllegalArgumentException("任务不存在: " + id);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("任务文件不是合法的 JSON: " + id);
        }
        Task task = new Task(json.getString("id"), json.getString("subject"), json.getString("description"),
                json.getString("status"), json.getString("owner"),
                json.getList("blockedBy", String.class) == null ? List.of() : json.getList("blockedBy", String.class));
        if (!id.equals(task.id())) {
            throw new IllegalArgumentException("任务文件里的 ID 和文件名不一致: " + id);
        }
        if (!STATUSES.contains(task.status())) {
            throw new IllegalArgumentException("任务状态不合法: " + task.status());
        }
        return task;
    }

    // 先写临时文件再原子替换：并发读的线程不会读到写了一半的任务文件。
    void save(Task task) throws IOException {
        Files.createDirectories(dir);
        Path temp = dir.resolve(task.id() + ".json.tmp");
        Files.writeString(temp, json(task), StandardCharsets.UTF_8);
        Files.move(temp, path(task.id()), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    List<Task> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> ids;
        try (Stream<Path> files = Files.list(dir)) {
            ids = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && ID.matcher(name.substring(0, name.length() - 5)).matches())
                    .map(name -> name.substring(0, name.length() - 5)).sorted().toList();
        }
        List<Task> tasks = new ArrayList<>();
        for (String id : ids) {
            tasks.add(load(id));
        }
        return tasks;
    }

    // ---------- 依赖 ----------

    // 只能给还没开始、没人认领的任务加依赖。整批先校验，全部通过才保存；重复添加的依赖会被忽略。
    Task addDependencies(String id, List<String> dependencies) throws IOException {
        return locked(() -> addDependenciesLocked(id, dependencies));
    }

    private Task addDependenciesLocked(String id, List<String> dependencies) throws IOException {
        Task task = load(id);
        if (!"pending".equals(task.status()) || task.owner() != null) {
            throw new IllegalArgumentException("任务 " + id + " 已经开始，只有 pending 且无人认领的任务才能加依赖");
        }
        Set<String> merged = new LinkedHashSet<>(task.blockedBy());
        for (String dependency : new LinkedHashSet<>(dependencies)) {
            if (dependency.equals(id)) {
                throw new IllegalArgumentException("任务不能依赖自己");
            }
            load(dependency);
            if (!merged.contains(dependency) && dependsOn(dependency, id)) {
                throw new IllegalArgumentException("会形成循环依赖: " + id + " -> " + dependency);
            }
            merged.add(dependency);
        }
        Task updated = task.with(task.status(), task.owner(), List.copyOf(merged));
        save(updated);
        return updated;
    }

    // from 是否直接或间接依赖 target。加边 id -> dependency 之前检查 dependency 是否已经依赖 id，是就会成环。
    private boolean dependsOn(String from, String target) throws IOException {
        Deque<String> pending = new ArrayDeque<>(List.of(from));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(target)) {
                return true;
            }
            if (visited.add(current)) {
                pending.addAll(load(current).blockedBy());
            }
        }
        return false;
    }

    // 还没完成的前置任务。前置任务的文件不见了也算没完成，宁可卡住也不误放行。
    List<String> incompleteDependencies(Task task) {
        List<String> incomplete = new ArrayList<>();
        for (String dependency : task.blockedBy()) {
            try {
                if (!"completed".equals(load(dependency).status())) {
                    incomplete.add(dependency);
                }
            } catch (IOException | IllegalArgumentException exception) {
                incomplete.add(dependency);
            }
        }
        return incomplete;
    }

    // ---------- 状态变化 ----------

    String claim(String id) throws IOException {
        return claim(id, owner);
    }

    // s13：认领必须原子执行。多个队友可能同时看到同一个候选任务，检查和写入放在同一把锁里，
    // 只有一个能把它推进到 in_progress。锁同时挡住同进程的其他线程和使用同一 .tasks/ 的其他进程。
    String claim(String id, String who) throws IOException {
        return locked(() -> {
            Task task = load(id);
            if (!"pending".equals(task.status()) || task.owner() != null) {
                throw new IllegalArgumentException("任务 " + id + " 的状态是 " + task.status()
                        + (task.owner() == null ? "" : "（负责人 " + task.owner() + "）") + "，不能认领");
            }
            for (Task other : list()) {
                if ("in_progress".equals(other.status()) && who.equals(other.owner())) {
                    throw new IllegalArgumentException(who + " 手上还有进行中的任务 " + other.id() + "，先完成它再认领新的");
                }
            }
            List<String> blockers = incompleteDependencies(task);
            if (!blockers.isEmpty()) {
                throw new IllegalArgumentException("任务 " + id + " 还被这些任务阻塞: " + String.join(", ", blockers));
            }
            save(task.with("in_progress", who, task.blockedBy()));
            return "已认领 " + id + "（" + task.subject() + "），状态 pending → in_progress，负责人 " + who;
        });
    }

    String complete(String id) throws IOException {
        return complete(id, owner);
    }

    // 完成后对比前后两次“可以开始的任务”，差集就是这次刚被解锁的下游任务。
    String complete(String id, String who) throws IOException {
        return locked(() -> {
            Task task = load(id);
            if (!"in_progress".equals(task.status())) {
                throw new IllegalArgumentException("任务 " + id + " 的状态是 " + task.status() + "，只有 in_progress 的任务能完成");
            }
            if (!who.equals(task.owner())) {
                throw new IllegalArgumentException("任务 " + id + " 的负责人是 " + task.owner() + "，不是 " + who);
            }
            Set<String> readyBefore = readyWithDependencies();
            save(task.with("completed", task.owner(), task.blockedBy()));
            Set<String> readyAfter = readyWithDependencies();
            List<String> unblocked = new ArrayList<>();
            for (Task candidate : list()) {
                if (readyAfter.contains(candidate.id()) && !readyBefore.contains(candidate.id())) {
                    unblocked.add(candidate.id() + "（" + candidate.subject() + "）");
                }
            }
            return "已完成 " + id + "（" + task.subject() + "）"
                    + (unblocked.isEmpty() ? "" : "\n已解锁：" + String.join(", ", unblocked));
        });
    }

    // s13：空闲队友找活干。扫描只是某一刻的快照，真正的认领仍走 claim 的锁；被别人抢先就试下一个。
    Task claimNextReady(String who) throws IOException {
        for (Task task : list()) {
            if ("pending".equals(task.status()) && task.owner() == null && incompleteDependencies(task).isEmpty()) {
                try {
                    claim(task.id(), who);
                    return load(task.id());
                } catch (IllegalArgumentException taken) {
                    // 被别的队友抢先认领了，或者自己手上还有任务
                }
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run() throws IOException;
    }

    // 进程内用 synchronized，跨进程用 .tasks/.lock 的文件锁。
    private synchronized <T> T locked(LockedAction<T> action) throws IOException {
        Files.createDirectories(dir);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(dir.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = channel.lock()) {
            return action.run();
        }
    }

    // 有前置任务、而且前置任务都已完成的 pending 任务。
    private Set<String> readyWithDependencies() throws IOException {
        Set<String> ready = new HashSet<>();
        for (Task task : list()) {
            if ("pending".equals(task.status()) && !task.blockedBy().isEmpty() && incompleteDependencies(task).isEmpty()) {
                ready.add(task.id());
            }
        }
        return ready;
    }

    String render() throws IOException {
        List<Task> tasks = list();
        if (tasks.isEmpty()) {
            return "还没有任务。用 create_task 创建。";
        }
        List<String> lines = new ArrayList<>();
        for (Task task : tasks) {
            String marker = switch (task.status()) {
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[ ]";
            };
            List<String> blockers = incompleteDependencies(task);
            String state = !"pending".equals(task.status()) ? ""
                    : blockers.isEmpty() ? " · 可以开始" : " · 被阻塞：" + String.join(", ", blockers);
            lines.add(marker + " " + task.id() + ": " + task.subject() + " [" + task.status() + "]"
                    + (task.owner() == null ? "" : " 负责人 " + task.owner())
                    + (task.blockedBy().isEmpty() ? "" : " 依赖 " + String.join(", ", task.blockedBy())) + state);
        }
        return String.join("\n", lines);
    }

    // ---------- 工具 ----------

    List<Tool> tools() {
        return List.of(
                new Tool("create_task", "创建一个持久任务（保存在 .tasks/，跨会话保留），返回运行时生成的任务 ID。"
                        + "新任务没有依赖；要加依赖，先把所有任务都创建好，再用返回的 ID 调用 update_task。",
                        JSON.parseObject("""
                                {"type": "object",
                                 "properties": {"subject": {"type": "string", "minLength": 1, "description": "任务标题"},
                                                "description": {"type": "string", "description": "任务的详细说明"}},
                                 "required": ["subject"]}
                                """),
                        arguments -> {
                            Task task = create(FileTools.requireString(arguments, "subject"),
                                    arguments.getString("description") == null ? "" : arguments.getString("description"));
                            return "已创建 " + task.id() + "：" + task.subject();
                        }),
                new Tool("update_task", "给任务添加前置任务（blockedBy）。ID 必须是 create_task 返回的真实 ID。",
                        JSON.parseObject("""
                                {"type": "object",
                                 "properties": {"task_id": {"type": "string", "pattern": "^task_[0-9a-f]{8}$"},
                                                "addBlockedBy": {"type": "array", "minItems": 1,
                                                                 "items": {"type": "string", "pattern": "^task_[0-9a-f]{8}$"},
                                                                 "description": "要先完成的任务 ID"}},
                                 "required": ["task_id", "addBlockedBy"]}
                                """),
                        arguments -> {
                            Task task = addDependencies(FileTools.requireString(arguments, "task_id"),
                                    requireIds(arguments, "addBlockedBy"));
                            return "已更新 " + task.id() + "（" + task.subject() + "）的依赖：" + String.join(", ", task.blockedBy());
                        }),
                listTool(), getTool(), claimTool(owner), completeTool(owner));
    }

    // s13：队友只能列举、查看、认领和完成任务，不能改任务图的结构；认领和完成都记在自己名下。
    List<Tool> teammateTools(String who) {
        return List.of(listTool(), getTool(), claimTool(who), completeTool(who));
    }

    private Tool listTool() {
        return new Tool("list_tasks", "列出所有任务的状态、负责人、依赖，以及 pending 任务能否开始。",
                JSON.parseObject("""
                        {"type": "object", "properties": {}}
                        """),
                arguments -> render());
    }

    private Tool getTool() {
        return new Tool("get_task", "按 ID 查看一个任务的完整内容（含详细说明）。",
                idSchema(),
                arguments -> JSON.toJSONString(load(FileTools.requireString(arguments, "task_id")).toJson(),
                        JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls));
    }

    private Tool claimTool(String who) {
        return new Tool("claim_task", "认领一个 pending 且前置任务都已完成的任务，状态变为 in_progress。开始做一个任务前调用。",
                idSchema(),
                arguments -> claim(FileTools.requireString(arguments, "task_id"), who));
    }

    private Tool completeTool(String who) {
        return new Tool("complete_task", "把自己认领的任务标记为完成，并返回因此被解锁的下游任务。",
                idSchema(),
                arguments -> complete(FileTools.requireString(arguments, "task_id"), who));
    }

    private static JSONObject idSchema() {
        return JSON.parseObject("""
                {"type": "object",
                 "properties": {"task_id": {"type": "string", "pattern": "^task_[0-9a-f]{8}$"}},
                 "required": ["task_id"]}
                """);
    }

    private static List<String> requireIds(JSONObject arguments, String key) {
        if (!(arguments.get(key) instanceof JSONArray array) || array.isEmpty()
                || !array.stream().allMatch(value -> value instanceof String)) {
            throw new IllegalArgumentException(key + " 必须是非空的任务 ID 数组。");
        }
        return array.stream().map(String.class::cast).toList();
    }

    // 文件名只能是 task_ + 8 位十六进制，模型传来的 ID 不会被当成任意路径。
    private Path path(String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("任务 ID 格式不对: " + id + "（应为 task_ 加 8 位十六进制）");
        }
        return dir.resolve(id + ".json");
    }

    private static String json(Task task) {
        return JSON.toJSONString(task.toJson(), JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls);
    }
}

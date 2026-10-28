import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * s12 的定时任务。agent 的输入不一定来自人，也可以来自时钟。
 * 保存“cron 表达式 + prompt”；调度线程每秒看一次本地时间，到点的任务先放进队列，
 * 等 agent 空闲时（常驻模式的主线程）再作为 “[Scheduled] prompt” 用户消息交给循环。
 *
 * durable=true 的任务存进 .scheduled_tasks.json，进程重启后重新加载；false 只在内存里。
 * 交付是“至少一次”：进程如果在模型收到 prompt 后、确认写回前退出，重启后会再交付一次。
 * 进程不在运行时任务不会触发，也不补跑错过的时间点；要在 agent 关闭时也准时执行，应该用系统的计划任务。
 */
final class CronScheduler implements AutoCloseable {
    static final String FILE = ".scheduled_tasks.json";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final SecureRandom RANDOM = new SecureRandom();
    // 五个字段的取值范围：分钟、小时、日、月、星期（0 和 7 都是周日）。
    private static final int[][] RANGES = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};
    private static final String[] FIELD_NAMES = {"分钟", "小时", "日", "月", "星期"};

    static final class Job {
        final String id;
        final String cron;
        final String prompt;
        final boolean recurring;
        final boolean durable;
        boolean pendingDelivery;
        String lastFired;

        Job(String id, String cron, String prompt, boolean recurring, boolean durable) {
            this.id = id;
            this.cron = cron;
            this.prompt = prompt;
            this.recurring = recurring;
            this.durable = durable;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("cron", cron);
            json.put("prompt", prompt);
            json.put("recurring", recurring);
            json.put("durable", durable);
            json.put("pendingDelivery", pendingDelivery);
            json.put("lastFired", lastFired);
            return json;
        }
    }

    private final Path file;
    private final BiConsumer<String, String> events;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final List<Job> queue = new ArrayList<>();
    private Thread ticker;

    // 启动时加载持久任务。文件损坏时报错，不静默丢掉用户的任务。
    CronScheduler(Path root, BiConsumer<String, String> events) throws IOException {
        this.file = root.resolve(FILE);
        this.events = events;
        if (!Files.isRegularFile(file)) {
            return;
        }
        JSONArray saved;
        try {
            saved = JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JSONException exception) {
            throw new IOException(FILE + " 已损坏，请检查或删除后再启动：" + exception.getMessage());
        }
        for (int index = 0; saved != null && index < saved.size(); index++) {
            JSONObject json = saved.getJSONObject(index);
            Job job = new Job(json.getString("id"), json.getString("cron"), json.getString("prompt"),
                    json.getBooleanValue("recurring"), true);
            job.lastFired = json.getString("lastFired");
            validate(job.cron);
            jobs.put(job.id, job);
            // 上次已经到期、但还没确认交付就退出了：重新入队，这就是“至少一次”。
            if (json.getBooleanValue("pendingDelivery")) {
                job.pendingDelivery = true;
                queue.add(job);
            }
        }
        if (!jobs.isEmpty()) {
            events.accept("定时", "从 " + FILE + " 加载了 " + jobs.size() + " 个任务" + (queue.isEmpty() ? "" : "，其中 " + queue.size() + " 个待交付"));
        }
    }

    // ---------- cron 表达式 ----------

    static void validate(String cron) {
        String[] fields = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron 表达式必须是 5 段（分钟 小时 日 月 星期），例如 \"*/5 * * * *\"");
        }
        for (int index = 0; index < 5; index++) {
            for (String part : fields[index].split(",")) {
                checkPart(part, index);
            }
        }
    }

    private static void checkPart(String part, int field) {
        int min = RANGES[field][0];
        int max = RANGES[field][1];
        try {
            if (part.equals("*")) {
                return;
            }
            if (part.startsWith("*/")) {
                if (Integer.parseInt(part.substring(2)) <= 0) {
                    throw new NumberFormatException();
                }
                return;
            }
            String[] range = part.split("-", -1);
            int low = Integer.parseInt(range[0]);
            int high = range.length == 2 ? Integer.parseInt(range[1]) : low;
            if (range.length > 2 || low < min || high > max || low > high) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(FIELD_NAMES[field] + "字段 \"" + part + "\" 不合法，取值范围 " + min + "-" + max);
        }
    }

    // 标准 cron 的规则：日和星期都被限定时，满足其中一个就算匹配。
    static boolean matches(String cron, LocalDateTime moment) {
        String[] fields = cron.trim().split("\\s+");
        int dayOfWeek = moment.getDayOfWeek().getValue() % 7;
        boolean minute = fieldMatches(fields[0], moment.getMinute());
        boolean hour = fieldMatches(fields[1], moment.getHour());
        boolean month = fieldMatches(fields[3], moment.getMonthValue());
        boolean dom = fieldMatches(fields[2], moment.getDayOfMonth());
        boolean dow = fieldMatches(fields[4], dayOfWeek) || (dayOfWeek == 0 && fieldMatches(fields[4], 7));
        boolean day = !fields[2].equals("*") && !fields[4].equals("*") ? dom || dow : dom && dow;
        return minute && hour && month && day;
    }

    private static boolean fieldMatches(String field, int value) {
        for (String part : field.split(",")) {
            if (part.equals("*")) {
                return true;
            }
            if (part.startsWith("*/")) {
                if (value % Integer.parseInt(part.substring(2)) == 0) {
                    return true;
                }
                continue;
            }
            String[] range = part.split("-");
            int low = Integer.parseInt(range[0]);
            int high = range.length == 2 ? Integer.parseInt(range[1]) : low;
            if (value >= low && value <= high) {
                return true;
            }
        }
        return false;
    }

    // ---------- 任务管理 ----------

    synchronized Job schedule(String cron, String prompt, boolean recurring, boolean durable) throws IOException {
        validate(cron);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        Job job = new Job("cron_" + HexFormat.of().formatHex(bytes), cron.trim(), prompt.strip(), recurring, durable);
        jobs.put(job.id, job);
        persist();
        return job;
    }

    synchronized boolean cancel(String id) throws IOException {
        Job removed = jobs.remove(id);
        queue.remove(removed);
        if (removed != null) {
            persist();
        }
        return removed != null;
    }

    synchronized List<Job> list() {
        return new ArrayList<>(jobs.values());
    }

    synchronized boolean isEmpty() {
        return jobs.isEmpty();
    }

    // 调度线程每秒调用一次：匹配当前分钟、这一分钟还没触发过、也没有在等交付的任务，标记后入队。
    synchronized void poll(LocalDateTime moment) throws IOException {
        String marker = moment.format(MINUTE);
        for (Job job : jobs.values()) {
            if (job.pendingDelivery || marker.equals(job.lastFired) || !matches(job.cron, moment)) {
                continue;
            }
            boolean previousPending = job.pendingDelivery;
            String previousFired = job.lastFired;
            job.pendingDelivery = true;
            job.lastFired = marker;
            try {
                persist();
            } catch (IOException exception) {
                // 状态没写进磁盘就不入队，恢复原样，下一秒再试。
                job.pendingDelivery = previousPending;
                job.lastFired = previousFired;
                throw exception;
            }
            queue.add(job);
            events.accept("定时", job.id + " 到点（" + marker + "），已入队，等 agent 空闲时交付：" + job.prompt);
            notifyAll();
        }
    }

    // 常驻模式的主线程用：等到有到期任务或超过 deadline，取走队列里所有任务。
    synchronized List<Job> awaitDue(long deadlineMillis) throws InterruptedException {
        while (queue.isEmpty() && !jobs.isEmpty()) {
            long left = deadlineMillis - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            wait(Math.min(left, 1_000));
        }
        List<Job> due = new ArrayList<>(queue);
        queue.clear();
        return due;
    }

    // 模型已经收到这条 prompt：一次性任务删除，周期任务清除待交付标记。delivered=false 时放回队列。
    synchronized void acknowledge(Job job, boolean delivered) throws IOException {
        if (!delivered) {
            queue.add(job);
            return;
        }
        if (!jobs.containsKey(job.id)) {
            return;
        }
        if (job.recurring) {
            job.pendingDelivery = false;
        } else {
            jobs.remove(job.id);
        }
        persist();
    }

    // 只写 durable 的任务。先写临时文件再原子替换，写到一半时进程被杀也不会留下半个文件。
    private void persist() throws IOException {
        JSONArray durable = new JSONArray();
        for (Job job : jobs.values()) {
            if (job.durable) {
                durable.add(job.toJson());
            }
        }
        if (durable.isEmpty() && !Files.exists(file)) {
            return;
        }
        Path temp = file.resolveSibling(FILE + ".tmp");
        Files.writeString(temp, JSON.toJSONString(durable, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls),
                StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------- 调度线程 ----------

    synchronized void start() {
        if (ticker != null) {
            return;
        }
        ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    poll(LocalDateTime.now());
                    Thread.sleep(1_000);
                } catch (InterruptedException exception) {
                    return;
                } catch (IOException exception) {
                    events.accept("定时", "保存任务状态失败，稍后重试：" + exception.getMessage());
                }
            }
        }, "cron-ticker");
        ticker.setDaemon(true);
        ticker.start();
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.interrupt();
            ticker = null;
        }
    }

    // ---------- 工具 ----------

    List<Tool> tools() {
        return List.of(
                new Tool("schedule_cron", "创建一个定时任务：到 cron 表达式指定的时间，把 prompt 作为一条 [Scheduled] 消息交给你执行。"
                        + "cron 是 5 段（分钟 小时 日 月 星期），支持 *、*/N、N、N-M、N,M，使用本机时间。"
                        + "只有程序以常驻模式（--serve）运行时才会触发；durable=true 时任务存盘，重启后还在。",
                        JSON.parseObject("""
                                {"type": "object", "required": ["cron", "prompt"],
                                 "properties": {
                                   "cron": {"type": "string", "description": "例如 */5 * * * * 表示每 5 分钟"},
                                   "prompt": {"type": "string", "description": "到点后要执行的任务"},
                                   "recurring": {"type": "boolean", "description": "是否重复执行，默认 true；false 表示触发一次后删除"},
                                   "durable": {"type": "boolean", "description": "是否存盘，默认 false"}}}
                                """),
                        arguments -> {
                            Job job = schedule(FileTools.requireString(arguments, "cron"), FileTools.requireString(arguments, "prompt"),
                                    !Boolean.FALSE.equals(arguments.get("recurring")), Boolean.TRUE.equals(arguments.get("durable")));
                            events.accept("定时", "创建 " + job.id + "：" + job.cron + " → " + job.prompt);
                            return "已创建定时任务 " + job.id + "（" + job.cron + "，" + (job.recurring ? "重复" : "一次性") + "，"
                                    + (job.durable ? "存盘" : "只在内存") + "）";
                        }),
                new Tool("list_crons", "列出所有定时任务。", JSON.parseObject("{\"type\": \"object\", \"properties\": {}}"),
                        arguments -> {
                            List<String> lines = new ArrayList<>();
                            for (Job job : list()) {
                                lines.add(job.id + "  " + job.cron + "  " + (job.recurring ? "重复" : "一次性") + "  "
                                        + (job.durable ? "存盘" : "内存") + "  上次触发 " + (job.lastFired == null ? "无" : job.lastFired)
                                        + "  → " + job.prompt);
                            }
                            return lines.isEmpty() ? "没有定时任务。" : String.join("\n", lines);
                        }),
                new Tool("cancel_cron", "按 ID 取消一个定时任务。",
                        JSON.parseObject("{\"type\": \"object\", \"required\": [\"id\"], \"properties\": {\"id\": {\"type\": \"string\"}}}"),
                        arguments -> {
                            String id = FileTools.requireString(arguments, "id");
                            if (!cancel(id)) {
                                throw new IllegalArgumentException("没有这个定时任务: " + id);
                            }
                            events.accept("定时", "取消 " + id);
                            return "已取消定时任务 " + id;
                        }));
    }
}

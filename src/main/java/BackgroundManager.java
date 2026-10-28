import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * s11 的后台任务。耗时的 bash 命令放到后台线程执行，工具调用立刻返回一个占位结果，主循环继续往下走。
 * 命令完成后，结果不会补回原来那次工具调用（那个 call_id 早就有结果了），
 * 而是在之后某一轮开始前，作为一条 <task_notification> 用户消息注入对话。
 */
final class BackgroundManager implements AutoCloseable {
    static final int TIMEOUT_SECONDS = 600;
    // 通知里只放结果开头，完整输出存盘，模型需要时用 read_file 读。
    static final int SUMMARY_CHARS = 2_000;

    private record Task(String id, String command, long startedNanos) {
    }

    private record Finished(Task task, String status, String output) {
    }

    private final Path root;
    private final BiConsumer<String, String> events;
    private BashTool bash;
    private final Map<String, Task> running = new LinkedHashMap<>();
    private final Map<String, Process> processes = new LinkedHashMap<>();
    private final List<Finished> ready = new ArrayList<>();
    private int counter;
    // 只有父 agent 能启动后台任务：后台结果只送回父 agent 的对话。
    // s13 之后同时有多个线程在跑循环，所以按“调用者是谁”判断，而不是一个全局开关。
    private volatile java.util.function.BooleanSupplier allowed = () -> true;

    BackgroundManager(Path root, BiConsumer<String, String> events) {
        this.root = root;
        this.events = events;
    }

    // BashTool 和 BackgroundManager 互相引用，构造完再接上。
    void bind(BashTool bash) {
        this.bash = bash;
    }

    void allowWhen(java.util.function.BooleanSupplier allowed) {
        this.allowed = allowed;
    }

    synchronized String start(String command) {
        if (!allowed.getAsBoolean()) {
            throw new IllegalArgumentException("只有父 agent 能启动后台任务，请直接执行。");
        }
        String id = String.format("bg_%04d", ++counter);
        Task task = new Task(id, command, System.nanoTime());
        running.put(id, task);
        Thread thread = new Thread(() -> runTask(task), "background-" + id);
        thread.setDaemon(true);
        thread.start();
        events.accept("后台", "启动 " + id + "：" + command);
        return id;
    }

    private void runTask(Task task) {
        String status;
        String output;
        try {
            output = bash.execute(task.command(), TIMEOUT_SECONDS, process -> {
                synchronized (this) {
                    processes.put(task.id(), process);
                }
            });
            status = output.matches("(?s).*（退出码 \\d+）$") ? "failed" : "completed";
        } catch (IOException | RuntimeException exception) {
            output = "工具错误: " + exception.getMessage();
            status = "failed";
        }
        synchronized (this) {
            processes.remove(task.id());
            if (running.remove(task.id()) == null) {
                return;
            }
            ready.add(new Finished(task, status, output));
            notifyAll();
        }
        events.accept("后台", task.id() + " " + (status.equals("completed") ? "完成" : "失败")
                + String.format("（%.1f s）", (System.nanoTime() - task.startedNanos()) / 1e9) + "，结果会在下一轮开始前送给模型");
    }

    synchronized int runningCount() {
        return running.size();
    }

    synchronized boolean hasReady() {
        return !ready.isEmpty();
    }

    // 模型想结束、但还有后台任务没跑完时用：最多等 timeoutMillis，直到至少有一个任务完成。
    synchronized boolean awaitAny(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (ready.isEmpty() && !running.isEmpty()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            wait(left);
        }
        return !ready.isEmpty();
    }

    // 取走所有已完成的任务，生成通知文本。完整输出写到 .task_outputs/background/<id>.txt。
    List<String> collect() throws IOException {
        List<Finished> done;
        synchronized (this) {
            done = new ArrayList<>(ready);
            ready.clear();
        }
        List<String> notifications = new ArrayList<>();
        for (Finished finished : done) {
            Path saved = root.resolve(".task_outputs").resolve("background").resolve(finished.task().id() + ".txt");
            Files.createDirectories(saved.getParent());
            Files.writeString(saved, finished.output(), StandardCharsets.UTF_8);
            String summary = finished.output().length() <= SUMMARY_CHARS ? finished.output()
                    : finished.output().substring(0, SUMMARY_CHARS) + "\n…（共 " + finished.output().length()
                    + " 字符，完整输出见 output_file）";
            notifications.add("<task_notification>\n"
                    + "  <task_id>" + finished.task().id() + "</task_id>\n"
                    + "  <status>" + finished.status() + "</status>\n"
                    + "  <command>" + finished.task().command() + "</command>\n"
                    + "  <output_file>.task_outputs/background/" + finished.task().id() + ".txt</output_file>\n"
                    + "  <summary>\n" + summary + "\n  </summary>\n"
                    + "</task_notification>");
        }
        return notifications;
    }

    // 程序退出时结束还在跑的后台进程（连同它启动的子进程），不留下孤儿进程。
    @Override
    public synchronized void close() {
        for (Process process : processes.values()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        if (!running.isEmpty()) {
            events.accept("后台", "程序结束，终止仍在运行的后台任务：" + String.join(", ", running.keySet()));
        }
        processes.clear();
        running.clear();
    }
}

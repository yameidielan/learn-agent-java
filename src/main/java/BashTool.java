import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * s03 补上的 bash 工具：在项目目录执行一条命令行命令。
 * 名字沿用课程的 bash，Windows 上实际通过 cmd.exe 执行。
 * 注意：命令不受 FileTools 的路径沙箱约束，安全只靠 Permission 的权限闸门。
 */
final class BashTool {
    private static final int TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    // cmd.exe 输出到管道时使用系统本地编码（中文 Windows 是 GBK），不是 UTF-8。
    private static final Charset OUTPUT_CHARSET =
            Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));

    private final Path root;
    // s11：后台任务管理器。为 null 时不支持 run_in_background。
    private final BackgroundManager background;

    BashTool(Path root) {
        this(root, null);
    }

    BashTool(Path root, BackgroundManager background) {
        this.root = root;
        this.background = background;
    }

    Tool tool() {
        String shell = WINDOWS
                ? "Windows cmd.exe 执行，请使用 cmd 语法（如 dir、type、del），不要用 Unix 或 PowerShell 语法"
                : "系统 shell（sh）执行";
        JSONObject parameters = JSON.parseObject("""
                {"type": "object",
                 "properties": {
                   "command": {"type": "string", "description": "要执行的命令"}
                 },
                 "required": ["command"]}
                """);
        String backgroundNote = "";
        if (background != null) {
            parameters.getJSONObject("properties").put("run_in_background", JSON.parseObject("""
                    {"type": "boolean", "description": "耗时长、且后面的工作不依赖它结果的命令（安装依赖、全量测试、构建）设为 true"}
                    """));
            backgroundNote = "设置 run_in_background=true 时命令在后台执行，立刻返回任务编号，完成后结果会以 <task_notification> 消息"
                    + "出现在之后的对话里，后台超时 " + BackgroundManager.TIMEOUT_SECONDS + " 秒；在此期间可以继续做别的事。";
        }
        return new Tool("bash", "在项目目录执行一条命令行命令，返回输出和退出码。命令通过 " + shell
                + "，超时 " + TIMEOUT_SECONDS + " 秒。读写文件请优先使用专用文件工具。" + backgroundNote,
                parameters, this::run);
    }

    String run(JSONObject arguments) throws IOException {
        String command = FileTools.requireString(arguments, "command");
        if (Boolean.TRUE.equals(arguments.get("run_in_background")) && background != null) {
            String id = background.start(command);
            return "[后台任务 " + id + " 已启动] 结果会在之后的轮次以 <task_notification> 送达，现在可以先做别的事。";
        }
        return execute(command, TIMEOUT_SECONDS, process -> { });
    }

    // 执行一条命令并返回格式化后的输出。onStart 拿到进程对象，后台任务用它在退出时结束进程。
    String execute(String command, int timeoutSeconds, java.util.function.Consumer<Process> onStart) throws IOException {
        ProcessBuilder builder = WINDOWS
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        Process process = builder.directory(root.toFile()).redirectErrorStream(true).start();
        onStart.accept(process);
        // 不提供输入，避免等待键盘输入的命令一直卡住。
        process.getOutputStream().close();
        // 在后台读取输出，避免输出太多填满管道导致进程卡住。
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalArgumentException("命令超时（" + timeoutSeconds + " 秒），已终止。");
            }
            String text = new String(output.get(), OUTPUT_CHARSET).strip();
            if (text.length() > MAX_OUTPUT_CHARS) {
                text = text.substring(0, MAX_OUTPUT_CHARS) + "\n...（输出过长，已截断）";
            }
            if (text.isEmpty()) {
                text = "（无输出）";
            }
            int exitCode = process.exitValue();
            return exitCode == 0 ? text : text + "\n（退出码 " + exitCode + "）";
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断。");
        } catch (ExecutionException exception) {
            throw new IOException("读取命令输出失败：" + exception.getCause());
        }
    }
}

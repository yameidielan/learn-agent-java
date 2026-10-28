import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * s14：和一个 MCP server 的连接。用 stdio 传输：把 server 作为子进程启动，
 * 向它的 stdin 一行写一条 JSON-RPC 消息，从它的 stdout 一行读一条。
 *
 * 一次连接的消息顺序：
 *   initialize（协商协议版本）→ notifications/initialized（通知，没有回复）→ tools/list → tools/call ...
 */
final class McpClient implements AutoCloseable {
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String EOF = "\u0000EOF";

    // 每条收发的消息都交给它，用于日志和 trace。outgoing 为 true 表示 agent 发给 server。
    interface Traffic {
        void message(String server, boolean outgoing, JSONObject message);
    }

    record ToolInfo(String name, String description, JSONObject inputSchema) {
    }

    private final String name;
    private final Process process;
    private final BufferedWriter writer;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final Traffic traffic;
    private final Duration timeout;
    private int nextId = 1;
    private JSONObject serverInfo = new JSONObject();
    private String protocolVersion = "";

    private McpClient(String name, Process process, Traffic traffic, Duration timeout) {
        this.name = name;
        this.process = process;
        this.traffic = traffic;
        this.timeout = timeout;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        // stdout 由一个后台线程按行读进队列，请求方带超时地等，server 卡住时不会把 agent 一起卡死。
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = input.readLine()) != null; ) {
                    lines.add(line);
                }
            } catch (IOException exception) {
                // 进程退出时流会被关闭
            }
            lines.add(EOF);
        }, "mcp-" + name + "-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    // 启动 server 进程并完成握手。stderr 是 server 自己的日志，写到 stderrLog，不和协议消息混在一起。
    static McpClient start(String name, List<String> command, Path workDir, Path stderrLog,
                           Traffic traffic, Duration timeout) throws IOException {
        Files.createDirectories(stderrLog.getParent());
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()))
                .start();
        McpClient client = new McpClient(name, process, traffic, timeout);
        try {
            client.initialize();
            return client;
        } catch (IOException | RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    private void initialize() throws IOException {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", new JSONObject());
        params.put("clientInfo", JSON.parseObject("{\"name\": \"agent4j\", \"version\": \"0.1\"}"));
        JSONObject result = request("initialize", params);
        protocolVersion = result.getString("protocolVersion");
        serverInfo = result.getJSONObject("serverInfo") == null ? new JSONObject() : result.getJSONObject("serverInfo");
        if (result.getJSONObject("capabilities") == null || result.getJSONObject("capabilities").get("tools") == null) {
            throw new IOException("MCP server " + name + " 没有声明 tools 能力");
        }
        // 握手的最后一步是通知：没有 id，server 不回复。
        JSONObject initialized = new JSONObject();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        send(initialized);
    }

    // tools/list 可能分页：带着 nextCursor 继续请求，直到没有下一页。
    List<ToolInfo> listTools() throws IOException {
        List<ToolInfo> tools = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject params = new JSONObject();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JSONObject result = request("tools/list", params);
            JSONArray page = result.getJSONArray("tools");
            for (int index = 0; page != null && index < page.size(); index++) {
                JSONObject tool = page.getJSONObject(index);
                tools.add(new ToolInfo(tool.getString("name"),
                        tool.getString("description") == null ? "" : tool.getString("description"),
                        tool.getJSONObject("inputSchema")));
            }
            cursor = result.getString("nextCursor");
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    // 工具执行失败（isError=true）属于正常结果，原样带上标记交给模型；协议层错误（未知工具、参数不合法）抛异常。
    String callTool(String tool, JSONObject arguments) throws IOException {
        JSONObject params = new JSONObject();
        params.put("name", tool);
        params.put("arguments", arguments);
        JSONObject result = request("tools/call", params);
        List<String> parts = new ArrayList<>();
        JSONArray content = result.getJSONArray("content");
        for (int index = 0; content != null && index < content.size(); index++) {
            JSONObject block = content.getJSONObject(index);
            parts.add("text".equals(block.getString("type")) ? block.getString("text")
                    : "[" + block.getString("type") + " 类型的内容，未显示]");
        }
        String text = String.join("\n", parts);
        return result.getBooleanValue("isError") ? "工具错误: MCP server 报告执行失败：" + text : text;
    }

    String name() {
        return name;
    }

    String describeServer() {
        return serverInfo.getString("name") + " " + serverInfo.getString("version") + "，协议 " + protocolVersion;
    }

    // 发一个请求并等待同 id 的响应。等待期间收到的通知忽略；server 反过来发的请求（如 ping）按协议回复。
    private JSONObject request(String method, JSONObject params) throws IOException {
        int id = nextId++;
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        send(message);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            String line;
            try {
                line = lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 MCP server " + name + " 时被中断");
            }
            if (line == null) {
                throw new IOException("MCP server " + name + " 在 " + timeout.toSeconds() + " 秒内没有响应 " + method);
            }
            if (line.equals(EOF)) {
                lines.add(EOF);
                throw new IOException("MCP server " + name + " 已退出（stderr 日志在 .mcp-logs/" + name + ".log）");
            }
            if (line.isBlank()) {
                continue;
            }
            JSONObject incoming;
            try {
                incoming = JSON.parseObject(line);
            } catch (JSONException exception) {
                throw new IOException("MCP server " + name + " 输出了不是 JSON 的内容：" + line);
            }
            traffic.message(name, false, incoming);
            if (incoming.containsKey("method")) {
                answerServerRequest(incoming);
                continue;
            }
            if (!Integer.valueOf(id).equals(incoming.getInteger("id"))) {
                continue;
            }
            JSONObject error = incoming.getJSONObject("error");
            if (error != null) {
                throw new IllegalArgumentException("MCP server " + name + " 返回错误 " + error.getInteger("code")
                        + "：" + error.getString("message"));
            }
            return incoming.getJSONObject("result") == null ? new JSONObject() : incoming.getJSONObject("result");
        }
    }

    private void answerServerRequest(JSONObject incoming) throws IOException {
        if (!incoming.containsKey("id")) {
            return;
        }
        JSONObject reply = new JSONObject();
        reply.put("jsonrpc", "2.0");
        reply.put("id", incoming.get("id"));
        if ("ping".equals(incoming.getString("method"))) {
            reply.put("result", new JSONObject());
        } else {
            reply.put("error", JSON.parseObject("{\"code\": -32601, \"message\": \"agent4j 不支持这个方法\"}"));
        }
        send(reply);
    }

    // stdio 传输规定一条消息占一行，消息里不能有换行。fastjson2 默认输出就是单行的。
    private void send(JSONObject message) throws IOException {
        traffic.message(name, true, message);
        writer.write(JSON.toJSONString(message));
        writer.write('\n');
        writer.flush();
    }

    // 关闭顺序按规范：先关 server 的 stdin，让它自己退出；等不到再强制结束。
    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException exception) {
            // server 已经退出
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}

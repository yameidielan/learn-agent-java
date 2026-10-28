import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * s14 的演示 MCP server，作为独立进程运行，只通过 stdin/stdout 说 JSON-RPC，日志写 stderr。
 * 用法：java DemoMcpServer docs   或   java DemoMcpServer deploy
 *
 * docs：search 在运行目录的 docs/ 下真实搜索 Markdown 文件，get_version 返回固定版本号。
 * deploy：status 和 trigger 是假的部署平台，只返回文字，不做任何事。
 */
public final class DemoMcpServer {
    private static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final int MAX_HITS = 8;

    private final String kind;

    private DemoMcpServer(String kind) {
        this.kind = kind;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !List.of("docs", "deploy").contains(args[0])) {
            System.err.println("用法：java DemoMcpServer docs|deploy");
            System.exit(2);
        }
        DemoMcpServer server = new DemoMcpServer(args[0]);
        System.err.println("DemoMcpServer " + args[0] + " 启动，工作目录 " + Path.of("").toAbsolutePath());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // stdin 关闭（客户端断开）时 readLine 返回 null，server 随之退出。
        for (String line; (line = input.readLine()) != null; ) {
            if (!line.isBlank()) {
                server.handle(line);
            }
        }
        System.err.println("DemoMcpServer " + args[0] + " 退出");
    }

    private void handle(String line) {
        JSONObject message;
        try {
            message = JSON.parseObject(line);
        } catch (JSONException exception) {
            reply(null, null, error(-32700, "无法解析 JSON"));
            return;
        }
        Object id = message.get("id");
        String method = message.getString("method");
        JSONObject params = message.getJSONObject("params") == null ? new JSONObject() : message.getJSONObject("params");
        if (id == null) {
            System.err.println("收到通知 " + method);
            return;
        }
        System.err.println("收到请求 #" + id + " " + method);
        switch (method == null ? "" : method) {
            case "initialize" -> {
                JSONObject result = new JSONObject();
                // 客户端要求的版本我们支持就原样返回；这个演示只支持一个版本。
                result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
                result.put("capabilities", JSON.parseObject("{\"tools\": {}}"));
                result.put("serverInfo", JSON.parseObject("{\"name\": \"demo-" + kind + "\", \"version\": \"1.0\"}"));
                reply(id, result, null);
            }
            case "ping" -> reply(id, new JSONObject(), null);
            case "tools/list" -> {
                JSONObject result = new JSONObject();
                result.put("tools", tools());
                reply(id, result, null);
            }
            case "tools/call" -> callTool(id, params);
            default -> reply(id, null, error(-32601, "不支持的方法: " + method));
        }
    }

    // annotations 是 server 对自己工具的说明。宿主可以参考，但不能据此授权。
    private JSONArray tools() {
        if (kind.equals("docs")) {
            return JSON.parseArray("""
                    [{"name": "search", "description": "在文档库里按关键词搜索，返回匹配的文件、行号和那一行内容。",
                      "inputSchema": {"type": "object", "properties": {"query": {"type": "string", "description": "关键词，多个用空格分开"}},
                                      "required": ["query"]},
                      "annotations": {"readOnlyHint": true}},
                     {"name": "get_version", "description": "查看文档 API 的版本号。",
                      "inputSchema": {"type": "object", "properties": {}},
                      "annotations": {"readOnlyHint": true}}]
                    """);
        }
        return JSON.parseArray("""
                [{"name": "status", "description": "查看一个服务当前的部署状态。",
                  "inputSchema": {"type": "object", "properties": {"service": {"type": "string"}}, "required": ["service"]},
                  "annotations": {"readOnlyHint": true}},
                 {"name": "trigger", "description": "触发一个服务的部署。",
                  "inputSchema": {"type": "object", "properties": {"service": {"type": "string"}}, "required": ["service"]},
                  "annotations": {"destructiveHint": true}}]
                """);
    }

    // 未知工具是协议错误（JSON-RPC error）；参数不对、执行失败是工具错误（result 里 isError=true），模型可以据此修正。
    private void callTool(Object id, JSONObject params) {
        String name = params.getString("name");
        JSONObject arguments = params.getJSONObject("arguments") == null ? new JSONObject() : params.getJSONObject("arguments");
        List<String> known = kind.equals("docs") ? List.of("search", "get_version") : List.of("status", "trigger");
        if (!known.contains(name)) {
            reply(id, null, error(-32602, "未知工具: " + name));
            return;
        }
        try {
            String text = switch (name) {
                case "search" -> search(requireString(arguments, "query"));
                case "get_version" -> "文档 API 版本 v2.1.0";
                case "status" -> requireString(arguments, "service") + "：运行中，版本 v1.4.2（演示数据）";
                default -> "已触发 " + requireString(arguments, "service") + " 的部署（演示，没有真的部署）";
            };
            reply(id, toolResult(text, false), null);
        } catch (IllegalArgumentException | IOException exception) {
            reply(id, toolResult(exception.getMessage(), true), null);
        }
    }

    private static String search(String query) throws IOException {
        Path docs = Path.of("docs");
        if (!Files.isDirectory(docs)) {
            return "运行目录下没有 docs/ 目录，文档库为空。";
        }
        List<String> terms = List.of(query.toLowerCase(Locale.ROOT).trim().split("\\s+"));
        List<String> hits = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(docs)) {
            files = walk.filter(path -> path.toString().endsWith(".md")).sorted().toList();
        }
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size() && hits.size() < MAX_HITS; index++) {
                String lower = lines.get(index).toLowerCase(Locale.ROOT);
                if (terms.stream().allMatch(lower::contains)) {
                    String text = lines.get(index).strip();
                    hits.add(file.toString().replace('\\', '/') + ":" + (index + 1) + ": "
                            + (text.length() > 120 ? text.substring(0, 120) + "…" : text));
                }
            }
        }
        return hits.isEmpty() ? "没有找到同时包含 " + terms + " 的行。"
                : "找到 " + hits.size() + " 处（最多显示 " + MAX_HITS + " 处）：\n" + String.join("\n", hits);
    }

    private static String requireString(JSONObject arguments, String key) {
        if (!(arguments.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 必须是非空字符串");
        }
        return value;
    }

    private static JSONObject toolResult(String text, boolean isError) {
        JSONObject block = new JSONObject();
        block.put("type", "text");
        block.put("text", text);
        JSONObject result = new JSONObject();
        result.put("content", JSONArray.of(block));
        result.put("isError", isError);
        return result;
    }

    private static JSONObject error(int code, String message) {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    private static void reply(Object id, JSONObject result, JSONObject error) {
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        if (error != null) {
            message.put("error", error);
        } else {
            message.put("result", result);
        }
        OUT.println(JSON.toJSONString(message));
    }
}

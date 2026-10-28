import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * s14：宿主（agent）这一侧的 MCP 管理。
 * 读运行目录下的 .mcp.json 知道有哪些 server、怎么启动、哪些工具可以直接放行；
 * 模型调用 connect_mcp 时才启动对应 server，把发现的工具改名为 mcp__{server}__{tool} 加进父 agent 的工具表，
 * 下一轮请求就会带上它们。
 */
final class McpManager implements AutoCloseable {
    static final String CONFIG_FILE = ".mcp.json";
    private static final Pattern DISALLOWED = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1_000;
    private static final int MAX_OUTPUT = 50_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // allow 是宿主自己写的放行名单，不来自 server。没列出的工具每次调用都要用户批准。
    record ServerConfig(String name, List<String> command, Set<String> allow) {
    }

    private final Path root;
    private final Map<String, ServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    // 改名后的工具名 -> 是否在放行名单里
    private final Map<String, Boolean> allowed = new LinkedHashMap<>();
    // 父 agent 的工具表。连接成功后新工具直接放进去，请求构造和工具分发都不用改。
    private final Map<String, Tool> pool;
    private final BiConsumer<String, String> events;
    private final McpClient.Traffic traffic;

    McpManager(Path root, Map<String, Tool> pool, BiConsumer<String, String> events, McpClient.Traffic traffic)
            throws IOException {
        this.root = root;
        this.pool = pool;
        this.events = events;
        this.traffic = traffic;
        Path config = root.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config)) {
            return;
        }
        JSONObject servers;
        try {
            servers = JSON.parseObject(Files.readString(config, StandardCharsets.UTF_8)).getJSONObject("mcpServers");
        } catch (JSONException | NullPointerException exception) {
            throw new IOException(CONFIG_FILE + " 不是合法的配置：需要 {\"mcpServers\": {...}}");
        }
        for (String name : servers == null ? Set.<String>of() : servers.keySet()) {
            JSONObject server = servers.getJSONObject(name);
            List<String> command = new ArrayList<>();
            command.add(server.getString("command"));
            JSONArray args = server.getJSONArray("args");
            for (int index = 0; args != null && index < args.size(); index++) {
                command.add(args.getString(index));
            }
            JSONArray allow = server.getJSONArray("allow");
            configs.put(name, new ServerConfig(name, command,
                    allow == null ? Set.of() : new LinkedHashSet<>(allow.toList(String.class))));
        }
    }

    boolean hasServers() {
        return !configs.isEmpty();
    }

    Tool connectTool() {
        JSONObject parameters = JSON.parseObject("""
                {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
                """);
        parameters.getJSONObject("properties").getJSONObject("name").put("enum", List.copyOf(configs.keySet()));
        return new Tool("connect_mcp", "连接一个 MCP server 并发现它提供的工具。连接后，下一轮起可以调用名为 "
                + "mcp__<server>__<工具> 的新工具。可连接的 server：" + String.join(", ", configs.keySet()),
                parameters, arguments -> connect(FileTools.requireString(arguments, "name")));
    }

    String connect(String name) throws IOException {
        if (clients.containsKey(name)) {
            return "MCP server " + name + " 已经连接过了。";
        }
        ServerConfig config = configs.get(name);
        if (config == null) {
            throw new IllegalArgumentException("未知的 MCP server '" + name + "'。可连接：" + String.join(", ", configs.keySet()));
        }
        events.accept("MCP", "启动 server " + name + "：" + String.join(" ", config.command()));
        McpClient client = McpClient.start(name, config.command(), root,
                root.resolve(".mcp-logs").resolve(normalize(name) + ".log"), traffic, TIMEOUT);
        try {
            Map<String, Tool> discovered = new LinkedHashMap<>();
            Map<String, Boolean> policy = new LinkedHashMap<>();
            for (McpClient.ToolInfo info : client.listTools()) {
                String prefixed = "mcp__" + normalize(name) + "__" + normalize(info.name());
                // 改名后可能撞名（比如 get.version 和 get_version），也可能超过模型工具名的长度上限，都拒绝连接。
                if (prefixed.length() > MAX_NAME) {
                    throw new IllegalArgumentException("MCP 工具名超过 " + MAX_NAME + " 个字符：" + prefixed);
                }
                if (pool.containsKey(prefixed) || discovered.containsKey(prefixed)) {
                    throw new IllegalArgumentException("MCP 工具改名后和已有工具重名：" + prefixed);
                }
                JSONObject schema = info.inputSchema() == null
                        ? JSON.parseObject("{\"type\": \"object\", \"properties\": {}}") : info.inputSchema();
                if (!"object".equals(schema.getString("type"))) {
                    throw new IllegalArgumentException("MCP 工具 " + info.name() + " 的 inputSchema 不是 object");
                }
                // 描述来自外部 server，是会进入模型上下文的不可信文本，限制长度并标明来源。
                String description = "[MCP " + name + "] " + clip(info.description(), MAX_DESCRIPTION);
                String rawName = info.name();
                discovered.put(prefixed, new Tool(prefixed, description, schema, arguments -> {
                    try {
                        return clip(client.callTool(rawName, arguments), MAX_OUTPUT);
                    } catch (IOException exception) {
                        throw new IllegalArgumentException("MCP 通信失败：" + exception.getMessage());
                    }
                }));
                policy.put(prefixed, config.allow().contains(rawName));
            }
            // 全部检查通过才一起加入，不会只加进去一半。
            pool.putAll(discovered);
            allowed.putAll(policy);
            clients.put(name, client);
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : policy.entrySet()) {
                names.add(entry.getKey() + (entry.getValue() ? "（直接执行）" : "（每次需用户批准）"));
            }
            return "已连接 MCP server " + name + "（" + client.describeServer() + "），发现 " + discovered.size()
                    + " 个工具：" + String.join(", ", names);
        } catch (IOException | RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    // PreToolUse hook：MCP 工具按宿主的放行名单决定，server 在 annotations 里自称 readOnly 也不算数。
    String check(String toolName, JSONObject arguments, Permission.Approver approver) {
        if (!toolName.startsWith("mcp__")) {
            return null;
        }
        if (Boolean.TRUE.equals(allowed.get(toolName))) {
            return null;
        }
        if (approver.approve(toolName, arguments, "外部 MCP 工具，不在 " + CONFIG_FILE + " 的放行名单里")) {
            return null;
        }
        return "权限拒绝: 用户没有批准调用外部工具 " + toolName + "。不要换个写法绕过，请改用其他做法或向用户说明。";
    }

    static String normalize(String name) {
        String normalized = DISALLOWED.matcher(name).replaceAll("_");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("MCP 名称不能为空");
        }
        return normalized;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…（已截断，共 " + text.length() + " 字符）";
    }

    @Override
    public void close() {
        for (McpClient client : clients.values()) {
            client.close();
        }
        clients.clear();
    }
}

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 控制台输出。每轮按“请求 → 响应 → 回复 → 工具调用 → 结果”分块打印，
 * 带耗时、token 用量、上下文占用和结果预览；子 agent 的输出整体缩进。
 * 默认带 ANSI 颜色，设置环境变量 NO_COLOR 后输出纯文本。
 */
final class ConsoleLog {
    private static final boolean COLOR = System.getenv("NO_COLOR") == null;
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final int PREVIEW_LINES = 6;
    private static final int PREVIEW_LINE_CHARS = 150;
    private static final int ARGUMENT_CHARS = 80;
    private static final int HEADER_WIDTH = 60;

    private final PrintStream out;
    // s13：每个线程各自的缩进。队友在自己的线程里跑循环，输出带上自己的前缀，互不覆盖。
    private final ThreadLocal<String> indent = ThreadLocal.withInitial(() -> "");

    ConsoleLog(PrintStream out) {
        this.out = out;
    }

    String indent() {
        return indent.get();
    }

    void indent(String indent) {
        this.indent.set(indent);
    }

    void banner(String model, Path root) {
        line(paint(BOLD, "agent4j") + paint(DIM, "  模型 " + model + " · 项目目录 " + root));
    }

    void prompt(String text) {
        labeled(paint(BOLD + CYAN, "▶ 提问 "), text);
    }

    // 系统提示词（请求里的 instructions）和可用工具。每个循环开始时完整显示一次，之后每轮请求都原样带上；
    // 没有工具的是摘要、记忆筛选这类一次性请求，只发一次。
    void systemPrompt(String label, String instructions, Collection<String> tools) {
        line("");
        line(paint(BOLD + BLUE, "▣ " + label) + paint(DIM, "  instructions · " + number(instructions.length())
                + " 字符 · " + (tools.isEmpty() ? "一次性请求，不带工具" : "每轮请求都会原样带上")));
        for (String text : instructions.split("\n", -1)) {
            line(paint(DIM, "    │ ") + text);
        }
        line(paint(DIM, "    工具 " + tools.size() + " 个：") + (tools.isEmpty() ? "无" : String.join(", ", tools)));
    }

    void round(int round, int maxRounds, String who) {
        String title = "── 第 " + round + "/" + maxRounds + " 轮 · " + who + " ";
        line("");
        line(paint(CYAN, title + "─".repeat(Math.max(4, HEADER_WIDTH - width(title)))));
    }

    // 请求概况：历史项数、工具数，以及历史大小相对压缩阈值的占用。
    void request(int items, int tools, int chars, int limit) {
        int percent = (int) Math.min(999, chars * 100L / limit);
        String color = percent >= 100 ? RED : percent >= 80 ? YELLOW : GREEN;
        line(paint(DIM, "→ 请求  ") + "历史 " + items + " 项 · 工具 " + tools + " 个 · 上下文 "
                + paint(color, bar(percent) + " " + percent + "%") + paint(DIM, "（" + number(chars) + " / " + number(limit) + " 字符）"));
    }

    void response(JSONObject response, long millis) {
        JSONObject usage = response.getJSONObject("usage");
        String tokens = "无用量信息";
        if (usage != null) {
            JSONObject details = usage.getJSONObject("input_tokens_details");
            int cached = details == null ? 0 : details.getIntValue("cached_tokens");
            tokens = "输入 " + number(usage.getIntValue("input_tokens")) + " tokens（缓存 " + number(cached)
                    + "）· 输出 " + number(usage.getIntValue("output_tokens")) + " tokens";
        }
        int calls = 0;
        JSONArray output = response.getJSONArray("output");
        for (int index = 0; output != null && index < output.size(); index++) {
            if ("function_call".equals(output.getJSONObject(index).getString("type"))) {
                calls++;
            }
        }
        String callText = calls == 0 ? "" : paint(DIM, " · ") + paint(YELLOW, "工具调用 " + calls + " 个");
        line(paint(DIM, "← 响应  ") + seconds(millis) + paint(DIM, " · ") + tokens + callText);
    }

    // 模型在调用工具前顺带说的话（phase = commentary）。任务还没做完，所以叫“回复”，和最后的“最终回答”区分开。
    void commentary(String text) {
        labeled(paint(BLUE, "◆ 回复 "), text);
    }

    // 一轮有多个工具调用时显示序号 [i/n]，按执行顺序编号；只有一个时不显示。
    void toolCall(int number, int total, String name, JSONObject arguments) {
        String prefix = total > 1 ? "● [" + number + "/" + total + "] " : "● ";
        line(paint(BOLD + YELLOW, prefix + name) + "  " + paint(DIM, formatArguments(arguments)));
    }

    // full 为 true 时完整显示（如待办清单），否则只显示前几行。
    void toolResult(String output, long millis, boolean full) {
        boolean failed = output.startsWith("工具错误") || output.startsWith("权限拒绝");
        String status = failed ? paint(RED, "  ✘ 失败") : paint(GREEN, "  ✔ 完成");
        long lineCount = output.lines().count();
        line(status + paint(DIM, "  " + millis + " ms · " + number(output.length()) + " 字符 · " + lineCount + " 行"));
        preview(output, full ? Integer.MAX_VALUE : PREVIEW_LINES);
    }

    void blocked(String reason) {
        labeled(paint(RED, "  ✘ 已阻止 "), reason);
    }

    // s14：agent 和 MCP server 之间的一条 JSON-RPC 消息。⇢ 是发给 server，⇠ 是 server 发回来。
    void mcpMessage(String server, boolean outgoing, JSONObject message) {
        String kind = message.containsKey("method")
                ? (message.containsKey("id") ? "请求 #" + message.get("id") + " " : "通知 ") + message.getString("method")
                : (message.containsKey("error") ? "错误 #" : "响应 #") + message.get("id");
        Object body = message.containsKey("params") ? message.get("params")
                : message.containsKey("result") ? message.get("result") : message.get("error");
        line(paint(DIM, "    " + (outgoing ? "⇢" : "⇠") + " MCP " + server + "  ") + paint(CYAN, kind)
                + paint(DIM, body == null ? "" : "  " + clip(JSON.toJSONString(body), PREVIEW_LINE_CHARS)));
    }

    // s17：一次目标判断的结果。number 是第几次判断，verdict 是结论，next 是接下来怎么做，raw 是判断器原始返回。
    void verdict(int number, String verdict, String next, boolean good, String condition, String reason, String raw) {
        line("");
        line(paint(BOLD + (good ? GREEN : YELLOW), "⚖ 目标判断 #" + number + "  " + verdict)
                + paint(DIM, "  → " + next));
        labeled(paint(DIM, "    条件  "), condition);
        labeled(paint(DIM, "    原因  "), reason);
        if (raw != null) {
            labeled(paint(DIM, "    原文  "), paint(DIM, raw.strip()));
        }
    }

    void event(String tag, String text) {
        labeled(paint(MAGENTA, "◇ " + tag + " "), text);
    }

    void warn(String text) {
        labeled(paint(YELLOW, "⚠ "), text);
    }

    void answer(String text) {
        line("");
        line(paint(BOLD + GREEN, "■ 最终回答"));
        line(text);
    }

    void summary(String text) {
        line("");
        line(paint(DIM, "─".repeat(HEADER_WIDTH)));
        labeled(paint(BOLD, "◎ 汇总 "), text);
    }

    void line(String text) {
        String prefix = indent.get();
        out.println(prefix + text.replace("\n", "\n" + prefix));
    }

    // 不换行输出，用于等待用户输入的提示。
    void print(String text) {
        out.print(indent.get() + text);
    }

    // 多行文本：第一行跟在标签后面，后续行对齐到标签之后。
    private void labeled(String label, String text) {
        String pad = " ".repeat(width(strip(label)));
        line(label + text.strip().replace("\n", "\n" + pad));
    }

    private void preview(String output, int maxLines) {
        List<String> lines = output.lines().toList();
        int shown = Math.min(maxLines, lines.size());
        for (int index = 0; index < shown; index++) {
            line(paint(DIM, "    │ ") + clip(lines.get(index), PREVIEW_LINE_CHARS));
        }
        if (lines.size() > shown) {
            line(paint(DIM, "    │ …… 还有 " + (lines.size() - shown) + " 行未显示"));
        }
    }

    // 参数按 key=value 显示；长文本只显示开头和总长度，换行显示成 ⏎。
    static String formatArguments(JSONObject arguments) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            String text = value instanceof String string ? string : JSON.toJSONString(value);
            text = text.replace("\r", "").replace("\n", "⏎");
            if (text.length() > ARGUMENT_CHARS) {
                text = text.substring(0, ARGUMENT_CHARS) + "…（共 " + number(text.length()) + " 字符）";
            }
            parts.add(entry.getKey() + "=" + text);
        }
        return String.join("  ", parts);
    }

    static String number(long value) {
        return String.format("%,d", value);
    }

    private static String seconds(long millis) {
        return String.format("%.1f s", millis / 1000.0);
    }

    private static String bar(int percent) {
        int filled = Math.min(10, (percent + 5) / 10);
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    // 估算显示宽度：中日韩字符和全角符号按 2 列计算。
    private static int width(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += text.charAt(index) >= 0x2E80 ? 2 : 1;
        }
        return width;
    }

    private static String strip(String painted) {
        return painted.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    private static String paint(String style, String text) {
        return COLOR ? style + text + RESET : text;
    }
}

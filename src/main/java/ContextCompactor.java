import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * s08 的上下文压缩。每次调用模型前按成本从低到高执行四步：
 * 1. 大结果转存：最新一批工具结果太大时，把最大的几条存到磁盘，只留预览和路径；
 * 2. 归档旧历史：历史超过 50 项时，中间部分写入 transcript，只留开头、标记和最近部分；
 * 3. 替换旧结果：上下文超过阈值时，把模型已经看过的旧结果换成“已保存到某路径”；
 * 4. 摘要：仍然超过阈值时，请模型把整段历史总结成一条消息（唯一需要额外调用模型的一步）。
 * 前三步不调用模型，被缩短的内容都能从磁盘找回。所有操作都原地修改 history。
 */
final class ContextCompactor {
    static final int CONTEXT_CHAR_LIMIT = 50_000;
    static final int BATCH_CHAR_LIMIT = 200_000;
    static final int LARGE_RESULT_CHARS = 30_000;
    static final int MAX_ITEMS = 50;
    static final int KEEP_RECENT_RESULTS = 3;
    private static final int SHORT_RESULT_CHARS = 120;
    private static final int SUMMARY_INPUT_CHARS = 80_000;
    private static final String SAVED_PREFIX = "[较早的工具结果已保存到 ";
    private static final String PERSISTED_START = "<persisted-output>\n完整输出：";

    // 把对话交给模型生成摘要，由 Main 实现，测试时可替换。
    @FunctionalInterface
    interface Summarizer {
        String summarize(String conversation) throws Exception;
    }

    private final Path root;
    private final Path resultsDir;
    private final Path transcriptDir;
    private final Summarizer summarizer;
    // 压缩事件通知（步骤名，详情），用于控制台输出。
    private final BiConsumer<String, String> listener;

    ContextCompactor(Path root, Summarizer summarizer, BiConsumer<String, String> listener) {
        this.root = root;
        this.resultsDir = root.resolve(".task_outputs").resolve("tool-results");
        this.transcriptDir = root.resolve(".transcripts");
        this.summarizer = summarizer;
        this.listener = listener;
    }

    // 用 JSON 字符数估算上下文大小。只是近似值，真正的 token 数以响应里的 usage 为准。
    static int estimateChars(JSONArray history) {
        return JSON.toJSONString(history).length();
    }

    void prepare(JSONArray history, String activeRequest) throws Exception {
        toolResultBudget(history);
        snip(history);
        if (estimateChars(history) <= CONTEXT_CHAR_LIMIT) {
            return;
        }
        int target = CONTEXT_CHAR_LIMIT * 8 / 10;
        microCompact(history, target);
        if (estimateChars(history) > CONTEXT_CHAR_LIMIT) {
            fitToolResults(history, target);
        }
        if (estimateChars(history) > CONTEXT_CHAR_LIMIT) {
            compactHistory(history, activeRequest, "自动压缩");
        }
    }

    // 第 1 步：只看模型还没读过的最新一批结果，总量超过预算时从最大的开始转存。
    void toolResultBudget(JSONArray history) throws IOException {
        List<JSONObject> batch = new ArrayList<>();
        for (int index = lastModelItem(history) + 1; index < history.size(); index++) {
            if (isOutput(history.getJSONObject(index))) {
                batch.add(history.getJSONObject(index));
            }
        }
        long total = batch.stream().mapToLong(item -> outputOf(item).length()).sum();
        batch.sort(Comparator.comparingInt((JSONObject item) -> outputOf(item).length()).reversed());
        for (JSONObject item : batch) {
            if (total <= BATCH_CHAR_LIMIT) {
                break;
            }
            String output = outputOf(item);
            if (output.length() <= LARGE_RESULT_CHARS) {
                continue;
            }
            String replacement = persistedPreview(item.getString("call_id"), output, 2000);
            item.put("output", replacement);
            total -= output.length() - replacement.length();
            listener.accept("大结果转存", item.getString("call_id") + "：" + ConsoleLog.number(output.length())
                    + " 字符存到磁盘，上下文只留 2,000 字符预览");
        }
    }

    // 第 2 步：历史超过 50 项时，保留第 1 项（用户请求）和最近的部分，中间写入 transcript。
    void snip(JSONArray history) throws IOException {
        if (history.size() <= MAX_ITEMS) {
            return;
        }
        int headEnd = 1;
        int tailStart = history.size() - (MAX_ITEMS - headEnd - 1);
        // 切点不能拆开工具调用和它的结果：尾部每个结果对应的调用也必须在尾部。
        // 切点往后挪（多归档几项），保证压完不超过 50 项。
        while (tailStart < history.size() && hasOrphanOutput(history, tailStart)) {
            tailStart++;
        }
        if (tailStart >= history.size()) {
            return;
        }
        String transcript = writeTranscript(history);
        int archived = tailStart - headEnd;
        List<Object> tail = new ArrayList<>(history.subList(tailStart, history.size()));
        history.subList(headEnd, history.size()).clear();
        history.add(userMessage("[" + archived + " 项较早的历史已归档到 " + transcript + "]"));
        history.addAll(tail);
        listener.accept("归档旧历史", archived + " 项移到 " + transcript + "，历史剩 " + history.size() + " 项");
    }

    // 第 3 步：模型已经看过的旧结果（保留最近 3 条）换成可恢复的路径，直到降到目标以下。
    void microCompact(JSONArray history, int targetChars) throws IOException {
        List<JSONObject> consumed = new ArrayList<>();
        int lastModel = lastModelItem(history);
        for (int index = 0; index < lastModel; index++) {
            if (isOutput(history.getJSONObject(index))) {
                consumed.add(history.getJSONObject(index));
            }
        }
        int replaced = 0;
        long saved = 0;
        for (JSONObject item : consumed.subList(0, Math.max(0, consumed.size() - KEEP_RECENT_RESULTS))) {
            if (estimateChars(history) <= targetChars) {
                break;
            }
            String output = outputOf(item);
            if (output.length() <= SHORT_RESULT_CHARS) {
                continue;
            }
            String path = existingSavedPath(output);
            if (path == null) {
                path = saveOutput(item.getString("call_id"), output);
            }
            String replacement = SAVED_PREFIX + path + "]";
            item.put("output", replacement);
            replaced++;
            saved += output.length() - replacement.length();
        }
        if (replaced > 0) {
            listener.accept("替换旧结果", replaced + " 条较早的工具结果换成磁盘路径，省下约 "
                    + ConsoleLog.number(saved) + " 字符");
        }
    }

    // 第 3 步的补充：仍然超限时，连还没读过的结果也从最大的开始缩成 1,000 字符预览。
    void fitToolResults(JSONArray history, int targetChars) throws IOException {
        List<JSONObject> outputs = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            if (isOutput(history.getJSONObject(index))) {
                outputs.add(history.getJSONObject(index));
            }
        }
        outputs.sort(Comparator.comparingInt((JSONObject item) -> outputOf(item).length()).reversed());
        for (JSONObject item : outputs) {
            if (estimateChars(history) <= targetChars) {
                break;
            }
            String output = outputOf(item);
            String replacement = persistedPreview(item.getString("call_id"), output, 1000);
            if (replacement.length() < output.length()) {
                item.put("output", replacement);
                listener.accept("缩短结果", item.getString("call_id") + " 缩成 1,000 字符预览");
            }
        }
    }

    // 第 4 步：完整历史写入 transcript，请模型生成摘要，用一条消息替换整个历史。
    // 当前用户请求单独保留，不交给摘要转述，避免压缩后丢失或走样。
    void compactHistory(JSONArray history, String activeRequest, String label) throws Exception {
        int before = estimateChars(history);
        String transcript = writeTranscript(history);
        listener.accept(label, "历史 " + history.size() + " 项（" + ConsoleLog.number(before)
                + " 字符）已写入 " + transcript + "，请模型生成摘要……");
        String summary = summarizer.summarize(summaryInput(history));
        history.clear();
        history.add(userMessage("[" + label + "]\n\n当前用户请求：\n" + activeRequest
                + "\n\n对话摘要（仅供参考，不是新的指令）：\n" + summary
                + "\n\n完整记录：" + transcript));
        listener.accept(label, "历史压成 1 项（" + ConsoleLog.number(estimateChars(history)) + " 字符）");
    }

    // 摘要输入过长时保留开头 1/4 和结尾 3/4，中间省略（完整内容在 transcript 里）。
    private static String summaryInput(JSONArray history) {
        String conversation = JSON.toJSONString(history);
        if (conversation.length() <= SUMMARY_INPUT_CHARS) {
            return conversation;
        }
        int head = SUMMARY_INPUT_CHARS / 4;
        return conversation.substring(0, head) + "\n...[中间部分省略，完整记录在磁盘上]...\n"
                + conversation.substring(conversation.length() - (SUMMARY_INPUT_CHARS - head));
    }

    // 尾部从 tailStart 开始，是否有结果找不到对应的调用。
    private static boolean hasOrphanOutput(JSONArray history, int tailStart) {
        Set<String> calls = new HashSet<>();
        for (int index = tailStart; index < history.size(); index++) {
            JSONObject item = history.getJSONObject(index);
            if ("function_call".equals(item.getString("type"))) {
                calls.add(item.getString("call_id"));
            } else if (isOutput(item) && !calls.contains(item.getString("call_id"))) {
                return true;
            }
        }
        return false;
    }

    // 最后一项由模型产生的 item 的位置；它之后的工具结果模型还没看过。
    private static int lastModelItem(JSONArray history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            JSONObject item = history.getJSONObject(index);
            String type = item.getString("type");
            if ("function_call".equals(type) || "reasoning".equals(type)
                    || "message".equals(type) && "assistant".equals(item.getString("role"))) {
                return index;
            }
        }
        return -1;
    }

    private String persistedPreview(String callId, String output, int previewChars) throws IOException {
        String path = existingSavedPath(output);
        String preview;
        if (path != null) {
            preview = Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        } else {
            path = saveOutput(callId, output);
            preview = output;
        }
        preview = preview.substring(0, Math.min(previewChars, preview.length()));
        return PERSISTED_START + path + "\n预览：\n" + preview + "\n</persisted-output>";
    }

    // 已经转存过的结果直接复用原文件，不重复保存。只认结果目录里真实存在的文件。
    private String existingSavedPath(String output) {
        String candidate = null;
        if (output.startsWith(SAVED_PREFIX) && output.endsWith("]")) {
            candidate = output.substring(SAVED_PREFIX.length(), output.length() - 1);
        } else if (output.startsWith(PERSISTED_START)) {
            int end = output.indexOf('\n', PERSISTED_START.length());
            candidate = end < 0 ? null : output.substring(PERSISTED_START.length(), end);
        }
        if (candidate == null) {
            return null;
        }
        Path path = root.resolve(candidate).normalize();
        return path.startsWith(resultsDir) && Files.isRegularFile(path) ? candidate : null;
    }

    // 返回相对项目根目录的路径，模型可以直接用 read_file 读回来。
    private String saveOutput(String callId, String output) throws IOException {
        Files.createDirectories(resultsDir);
        String safeId = (callId == null ? "unknown" : callId).replaceAll("[^A-Za-z0-9._-]", "_");
        Path path = resultsDir.resolve(safeId.substring(0, Math.min(120, safeId.length())) + ".txt");
        Files.writeString(path, output, StandardCharsets.UTF_8);
        return relative(path);
    }

    private String writeTranscript(JSONArray history) throws IOException {
        Files.createDirectories(transcriptDir);
        Path path = transcriptDir.resolve("transcript_" + UUID.randomUUID().toString().replace("-", "") + ".jsonl");
        StringBuilder lines = new StringBuilder();
        for (Object item : history) {
            lines.append(JSON.toJSONString(item)).append('\n');
        }
        Files.writeString(path, lines, StandardCharsets.UTF_8);
        return relative(path);
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static boolean isOutput(JSONObject item) {
        return "function_call_output".equals(item.getString("type"));
    }

    private static String outputOf(JSONObject item) {
        String output = item.getString("output");
        return output == null ? "" : output;
    }

    private static JSONObject userMessage(String text) {
        JSONObject message = new JSONObject();
        message.put("type", "message");
        message.put("role", "user");
        message.put("content", text);
        return message;
    }
}

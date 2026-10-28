import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * s13 的消息总线。每个 agent 一个收件箱文件 .mailboxes/<名字>.jsonl，一行一条消息。
 * Lead 和队友不能共用一份对话历史（否则一个队友的工具结果会进到另一个队友的上下文里），
 * 所以通信走收件箱，由运行时决定什么时候把消息放进谁的对话。
 * 一把锁保护所有收件箱文件；消息到达时 notifyAll 唤醒正在等的 agent。
 */
final class MessageBus {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,19}");

    record Message(String from, String to, String type, String content, JSONObject metadata) {
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("from", from);
            json.put("to", to);
            json.put("type", type);
            json.put("content", content);
            json.put("metadata", metadata == null ? new JSONObject() : metadata);
            return json;
        }
    }

    private final Path dir;
    private final BiConsumer<String, String> events;

    MessageBus(Path root, BiConsumer<String, String> events) {
        this.dir = root.resolve(".mailboxes");
        this.events = events;
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    synchronized void send(String from, String to, String type, String content, JSONObject metadata) throws IOException {
        if (!validName(to)) {
            throw new IllegalArgumentException("收件人名字不合法: " + to);
        }
        Files.createDirectories(dir);
        Message message = new Message(from, to, type, content, metadata);
        Files.writeString(dir.resolve(to + ".jsonl"), message.toJson().toJSONString() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String preview = content.replaceAll("\\s+", " ");
        events.accept("团队", from + " → " + to + "（" + type + "）" + (preview.length() > 100 ? preview.substring(0, 100) + "…" : preview));
        notifyAll();
    }

    synchronized boolean hasMessages(String name) {
        Path inbox = dir.resolve(name + ".jsonl");
        try {
            return Files.isRegularFile(inbox) && Files.size(inbox) > 0;
        } catch (IOException exception) {
            return false;
        }
    }

    // 读出并清空收件箱。读是消费：每条消息只会被处理一次。
    synchronized List<Message> read(String name) throws IOException {
        Path inbox = dir.resolve(name + ".jsonl");
        List<Message> messages = new ArrayList<>();
        if (!Files.isRegularFile(inbox)) {
            return messages;
        }
        for (String line : Files.readAllLines(inbox, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JSONObject json = JSON.parseObject(line);
                messages.add(new Message(json.getString("from"), json.getString("to"), json.getString("type"),
                        json.getString("content"), json.getJSONObject("metadata")));
            } catch (JSONException exception) {
                events.accept("团队", name + " 的收件箱里有一行坏数据，已跳过");
            }
        }
        Files.delete(inbox);
        return messages;
    }

    // 等到收件箱里有消息或超时，然后读出。超时返回空列表。
    synchronized List<Message> waitFor(String name, long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!hasMessages(name)) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return List.of();
            }
            wait(left);
        }
        return read(name);
    }
}

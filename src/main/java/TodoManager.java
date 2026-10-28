import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * s05 的 todo_write 工具：模型用它维护自己的待办清单。
 * 它不增加任何执行能力，只保存计划状态并把清单渲染回给模型。
 */
final class TodoManager {
    private static final int MAX_TODOS = 20;
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");

    record Todo(String content, String status) {
    }

    private List<Todo> items = List.of();

    Tool tool() {
        return new Tool("todo_write", "创建并维护当前任务的待办清单。每次传入完整清单（会整体替换旧清单），"
                + "状态为 pending、in_progress 或 completed，同一时间最多一项 in_progress。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {
                           "todos": {
                             "type": "array",
                             "maxItems": 20,
                             "items": {
                               "type": "object",
                               "properties": {
                                 "content": {"type": "string", "description": "这一步要做什么"},
                                 "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                               },
                               "required": ["content", "status"]
                             }
                           }
                         },
                         "required": ["todos"]}
                        """),
                this::update);
    }

    // 先完整校验，全部通过才替换旧清单；任何一项不合法都不改动现有状态。
    String update(JSONObject arguments) {
        JSONArray todos = parseTodos(arguments.get("todos"));
        if (todos.size() > MAX_TODOS) {
            throw new IllegalArgumentException("待办最多 " + MAX_TODOS + " 项。");
        }
        List<Todo> validated = new ArrayList<>();
        int inProgress = 0;
        for (int index = 0; index < todos.size(); index++) {
            if (!(todos.get(index) instanceof JSONObject todo)) {
                throw new IllegalArgumentException("todos[" + index + "] 必须是对象。");
            }
            String content = todo.getString("content") == null ? "" : todo.getString("content").strip();
            String status = todo.getString("status") == null ? "pending" : todo.getString("status").strip();
            if (content.isEmpty()) {
                throw new IllegalArgumentException("todos[" + index + "] 缺少 content。");
            }
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("todos[" + index + "] 的状态 '" + status + "' 不合法。");
            }
            if (status.equals("in_progress")) {
                inProgress++;
            }
            validated.add(new Todo(content, status));
        }
        if (inProgress > 1) {
            throw new IllegalArgumentException("同一时间只能有一项 in_progress。");
        }
        items = List.copyOf(validated);
        return render();
    }

    String render() {
        if (items.isEmpty()) {
            return "（没有待办）";
        }
        StringBuilder text = new StringBuilder();
        long done = 0;
        for (Todo todo : items) {
            String marker = switch (todo.status()) {
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[ ]";
            };
            text.append(marker).append(' ').append(todo.content()).append('\n');
            if (todo.status().equals("completed")) {
                done++;
            }
        }
        return text.append("\n（已完成 ").append(done).append('/').append(items.size()).append("）").toString();
    }

    // 模型有时把数组当作 JSON 字符串传过来，两种写法都接受。
    private static JSONArray parseTodos(Object value) {
        if (value instanceof JSONArray array) {
            return array;
        }
        if (value instanceof String text) {
            try {
                JSONArray array = JSON.parseArray(text);
                if (array != null) {
                    return array;
                }
            } catch (JSONException ignored) {
                // 落到下面的统一报错
            }
        }
        throw new IllegalArgumentException("todos 必须是数组。");
    }
}

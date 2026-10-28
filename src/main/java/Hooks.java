import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * s04 的 hook 注册表：在循环的四个固定节点调用回调。
 * 扩展逻辑通过 onXxx 注册，循环只调用触发方法，不再关心具体做什么。
 * 同一事件的 hook 按注册顺序执行。
 */
final class Hooks {

    // 用户提问进入模型之前。只做观察，不影响流程。
    @FunctionalInterface
    interface UserPromptSubmit {
        void handle(String prompt);
    }

    // 工具执行之前。返回非 null 表示阻止执行，返回值作为工具结果回传给模型。
    @FunctionalInterface
    interface PreToolUse {
        String handle(String toolName, JSONObject arguments);
    }

    // 工具执行之后。能看到输出，但不能修改它。
    @FunctionalInterface
    interface PostToolUse {
        void handle(String toolName, JSONObject arguments, String output);
    }

    // 模型准备结束时。返回非 null 表示不许结束，返回值作为新的用户消息让模型继续。
    @FunctionalInterface
    interface Stop {
        String handle(JSONArray history);
    }

    private final List<UserPromptSubmit> userPromptSubmitHooks = new ArrayList<>();
    private final List<PreToolUse> preToolUseHooks = new ArrayList<>();
    private final List<PostToolUse> postToolUseHooks = new ArrayList<>();
    private final List<Stop> stopHooks = new ArrayList<>();

    // -- 注册，对应 Python 版的 register_hook --

    void onUserPromptSubmit(UserPromptSubmit hook) {
        userPromptSubmitHooks.add(hook);
    }

    void onPreToolUse(PreToolUse hook) {
        preToolUseHooks.add(hook);
    }

    void onPostToolUse(PostToolUse hook) {
        postToolUseHooks.add(hook);
    }

    void onStop(Stop hook) {
        stopHooks.add(hook);
    }

    // -- 触发，对应 Python 版的 trigger_hooks --

    void userPromptSubmit(String prompt) {
        for (UserPromptSubmit hook : userPromptSubmitHooks) {
            hook.handle(prompt);
        }
    }

    // 第一个返回非 null 的 hook 生效，后面的不再执行。
    String preToolUse(String toolName, JSONObject arguments) {
        for (PreToolUse hook : preToolUseHooks) {
            String blocked = hook.handle(toolName, arguments);
            if (blocked != null) {
                return blocked;
            }
        }
        return null;
    }

    void postToolUse(String toolName, JSONObject arguments, String output) {
        for (PostToolUse hook : postToolUseHooks) {
            hook.handle(toolName, arguments, output);
        }
    }

    // 第一个返回非 null 的 hook 生效，后面的不再执行。
    String stop(JSONArray history) {
        for (Stop hook : stopHooks) {
            String reason = hook.handle(history);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }
}

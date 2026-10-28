import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * s16：宿主注册的 workflow 和暴露给模型的 workflow 工具。
 * 模型只能选名字、传参数、给续跑的 runId，不能提交脚本——能跑什么由宿主决定。
 */
final class Workflows {
    record Registered(WorkflowRuntime.Meta meta, WorkflowRuntime.Script script) {
    }

    static final List<String> DIMENSIONS = List.of("正确性", "边界条件", "可读性");
    static final JSONObject FINDINGS_SCHEMA = JSON.parseObject("""
            {"type": "object", "required": ["findings"],
             "properties": {"findings": {"type": "array", "items": {
               "type": "object", "required": ["title", "detail", "severity"],
               "properties": {"title": {"type": "string"}, "detail": {"type": "string"},
                              "severity": {"type": "string", "enum": ["high", "medium", "low"]}}}}}}
            """);
    static final JSONObject VERDICT_SCHEMA = JSON.parseObject("""
            {"type": "object", "required": ["isReal", "reason"],
             "properties": {"isReal": {"type": "boolean"}, "reason": {"type": "string"}}}
            """);

    private final Map<String, Registered> registry = new LinkedHashMap<>();
    private final WorkflowRuntime runtime;

    Workflows(WorkflowRuntime runtime) {
        this.runtime = runtime;
        register(new WorkflowRuntime.Meta("review-changes",
                "按正确性、边界条件、可读性三个维度并行审查一段代码，每条发现再单独验证，只返回确认过的问题",
                List.of("Review", "Verify")), Workflows::reviewChanges);
    }

    // 注册时就校验元数据，有问题启动时就报错，不等到模型调用时才发现。
    void register(WorkflowRuntime.Meta meta, WorkflowRuntime.Script script) {
        WorkflowRuntime.validate(meta);
        registry.put(meta.name(), new Registered(meta, script));
    }

    Tool tool() {
        JSONObject parameters = JSON.parseObject("""
                {"type": "object", "required": ["name"],
                 "properties": {
                   "name": {"type": "string", "description": "要运行的 workflow 名称"},
                   "args": {"type": "object", "description": "传给 workflow 的参数"},
                   "resume_from_run_id": {"type": "string", "description": "续跑之前某次运行时填它的 runId"}}}
                """);
        parameters.getJSONObject("properties").getJSONObject("name").put("enum", List.copyOf(registry.keySet()));
        StringBuilder description = new StringBuilder("运行一个宿主预先写好的 workflow：一次调用在背后编排多个子 agent 并行工作，"
                + "返回结构化结果和 runId。子 agent 没有工具，只能看到 args 里给的内容。中途失败时可以带 resume_from_run_id 续跑，"
                + "已完成的子 agent 不会重跑；续跑时不用再传 args，会沿用那次运行的参数。可用的 workflow：");
        for (Registered registered : registry.values()) {
            description.append("\n- ").append(registered.meta().name()).append("：").append(registered.meta().description());
        }
        description.append("\n  review-changes 的 args：{\"changes\": \"要审查的代码或改动全文\"}");
        return new Tool("workflow", description.toString(), parameters, arguments -> {
            String name = FileTools.requireString(arguments, "name");
            Registered registered = registry.get(name);
            if (registered == null) {
                throw new IllegalArgumentException("未知 workflow '" + name + "'。可用：" + String.join(", ", registry.keySet()));
            }
            Object args = arguments.get("args");
            if (args != null && !(args instanceof JSONObject)) {
                throw new IllegalArgumentException("args 必须是 JSON 对象");
            }
            try {
                JSONObject out = runtime.run(registered.meta(), registered.script(), (JSONObject) args,
                        arguments.getString("resume_from_run_id"));
                return JSON.toJSONString(out, JSONWriter.Feature.PrettyFormat);
            } catch (WorkflowRuntime.WorkflowException exception) {
                throw new IllegalArgumentException(exception.getMessage());
            } catch (Exception exception) {
                throw new IllegalArgumentException("workflow 运行出错：" + exception.getMessage());
            }
        });
    }

    // 示例 workflow：每个维度独立走“审计 → 验证”（pipeline），每条发现的验证之间并行（parallel）。
    // 中间结果都在脚本变量里，不进入主 agent 的对话历史；主 agent 只拿到最后确认过的问题。
    static Object reviewChanges(WorkflowRuntime.Context ctx, JSONObject args) {
        String changes = args.getString("changes");
        if (changes == null || changes.isBlank()) {
            throw new WorkflowRuntime.WorkflowException("args.changes 不能为空：要把待审查的代码全文放进去");
        }
        ctx.phase("Review");
        List<Object> results = ctx.pipeline(DIMENSIONS,
                // 阶段 1：审计这个维度
                (previous, dimension, index) -> {
                    JSONObject out = ctx.agent("audit:" + dimension,
                            "只从「" + dimension + "」这个角度审查下面的代码，列出确实存在的问题（没有就返回空数组）：\n\n" + changes,
                            FINDINGS_SCHEMA);
                    JSONObject audited = new JSONObject();
                    audited.put("dimension", dimension);
                    audited.put("findings", out.getJSONArray("findings"));
                    return audited;
                },
                // 阶段 2：每条发现单独做对抗性验证，并行等齐
                (previous, dimension, index) -> {
                    ctx.phase("Verify");
                    JSONArray findings = ((JSONObject) previous).getJSONArray("findings");
                    List<Callable<JSONObject>> checks = new ArrayList<>();
                    for (int i = 0; i < findings.size(); i++) {
                        JSONObject finding = findings.getJSONObject(i);
                        checks.add(() -> ctx.agent("verify:" + dimension + ":" + finding.getString("title"),
                                "下面是别人对这段代码提出的一条问题。请对照代码核实它是否真实存在，不要附和，误报就说是误报。\n\n"
                                        + "问题：" + finding.toJSONString() + "\n\n代码：\n" + changes,
                                VERDICT_SCHEMA));
                    }
                    List<JSONObject> verdicts = ctx.parallel(checks);
                    JSONArray confirmed = new JSONArray();
                    for (int i = 0; i < findings.size(); i++) {
                        if (verdicts.get(i).getBooleanValue("isReal")) {
                            JSONObject finding = findings.getJSONObject(i);
                            finding.put("dimension", dimension);
                            confirmed.add(finding);
                        }
                    }
                    ctx.log("「" + dimension + "」发现 " + findings.size() + " 条，确认 " + confirmed.size() + " 条");
                    return confirmed;
                });
        List<JSONObject> all = new ArrayList<>();
        for (Object result : results) {
            for (Object finding : (JSONArray) result) {
                all.add((JSONObject) finding);
            }
        }
        List<String> order = List.of("high", "medium", "low");
        all.sort(Comparator.comparingInt(finding -> order.indexOf(finding.getString("severity"))));
        JSONObject result = new JSONObject();
        result.put("confirmed", all);
        result.put("summary", "审查了 " + DIMENSIONS.size() + " 个维度，确认 " + all.size() + " 个问题");
        return result;
    }
}

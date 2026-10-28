import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;

/**
 * 一个工具 = 给模型看的说明书（名字、描述、参数 Schema）+ 在本地真正执行的逻辑。
 * 模型只能看到说明书，看不到 handler。
 */
record Tool(String name, String description, JSONObject parameters, Handler handler) {

    @FunctionalInterface
    interface Handler {
        // 参数不合法时抛 IllegalArgumentException，文件操作失败时抛 IOException，
        // 由 Main.executeTool 统一转换成错误文本回传给模型。
        String run(JSONObject arguments) throws IOException;
    }

    // 请求里 tools 数组的一项。
    JSONObject definition() {
        JSONObject definition = new JSONObject();
        definition.put("type", "function");
        definition.put("name", name);
        definition.put("description", description);
        definition.put("parameters", parameters);
        return definition;
    }
}

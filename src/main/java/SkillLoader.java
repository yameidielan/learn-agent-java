import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s07 的技能加载：启动时扫描 skills/&lt;目录&gt;/SKILL.md，只把名称和描述放进指令（技能目录），
 * 模型调用 load_skill 时才把完整内容作为工具结果返回。
 */
final class SkillLoader {
    private static final int MAX_SKILL_BYTES = 64 * 1024;
    // frontmatter 里形如 "name: value" 的一行。只支持本课用到的 YAML 子集：单行值和 | / > 多行块。
    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z][\\w-]*):\\s*(.*)$");

    record Skill(String name, String description, String content) {
    }

    // 按目录名排序，先扫描到的同名技能生效。
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    SkillLoader(Path skillsDir) throws IOException {
        if (!Files.isDirectory(skillsDir)) {
            return;
        }
        Path root = skillsDir.toRealPath();
        List<Path> manifests;
        try (Stream<Path> dirs = Files.list(root)) {
            manifests = dirs.map(dir -> dir.resolve("SKILL.md")).sorted().toList();
        }
        for (Path manifest : manifests) {
            Skill skill = read(root, manifest);
            if (skill != null) {
                skills.putIfAbsent(skill.name(), skill);
            }
        }
    }

    Tool tool() {
        return new Tool("load_skill", "按名称加载一个技能的完整说明（SKILL.md）。可用技能见指令中的技能目录。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {"name": {"type": "string", "description": "技能名称"}},
                         "required": ["name"]}
                        """),
                arguments -> load(FileTools.requireString(arguments, "name")));
    }

    // 只给模型看名称和描述，每个技能一行。
    String catalog() {
        if (skills.isEmpty()) {
            return "（没有可用技能）";
        }
        List<String> lines = new ArrayList<>();
        for (Skill skill : skills.values()) {
            lines.add("- " + skill.name() + ": " + skill.description());
        }
        return String.join("\n", lines);
    }

    // name 只用来查注册表，不会被当作路径，所以模型无法借 load_skill 读任意文件。
    String load(String name) {
        Skill skill = skills.get(name.strip());
        if (skill == null) {
            String available = skills.isEmpty() ? "无" : String.join(", ", skills.keySet());
            throw new IllegalArgumentException("未知技能 '" + name + "'。可用技能：" + available);
        }
        return skill.content();
    }

    // 读取一个 SKILL.md；不存在、指向技能目录外、过大或不是 UTF-8 时跳过。
    private static Skill read(Path root, Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest) || !manifest.toRealPath().startsWith(root)
                || Files.size(manifest) > MAX_SKILL_BYTES) {
            return null;
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Files.readAllBytes(manifest))).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
        Document document = parseDocument(content);
        Map<String, String> metadata = document.metadata();
        String body = document.body();

        String name = metadata.getOrDefault("name", "").strip();
        if (name.isEmpty()) {
            name = manifest.getParent().getFileName().toString();
        }
        String description = metadata.getOrDefault("description", "").strip();
        if (description.isEmpty()) {
            description = body.lines().findFirst().orElse("").replaceFirst("^[#\\s]+", "");
        }
        // 多行描述压成一行，目录里每个技能只占一行。
        description = description.replaceAll("\\s+", " ").strip();
        return new Skill(name, description, content);
    }

    // 带 frontmatter 的 Markdown：--- 之间是元数据，后面是正文。s09 的记忆文件也用这个格式。
    record Document(Map<String, String> metadata, String body) {
    }

    static Document parseDocument(String content) {
        String[] lines = content.split("\r?\n", -1);
        int closing = -1;
        if (lines.length > 0 && lines[0].equals("---")) {
            for (int index = 1; index < lines.length; index++) {
                if (lines[index].equals("---")) {
                    closing = index;
                    break;
                }
            }
        }
        Map<String, String> metadata = closing < 0 ? Map.of() : parseFrontmatter(lines, closing);
        String body = String.join("\n", List.of(lines).subList(closing + 1, lines.length)).strip();
        return new Document(metadata, body);
    }

    private static Map<String, String> parseFrontmatter(String[] lines, int closing) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int index = 1; index < closing; index++) {
            Matcher matcher = KEY_LINE.matcher(lines[index]);
            if (!matcher.matches()) {
                continue;
            }
            String value = matcher.group(2).strip();
            if (value.matches("[|>][-+]?")) {
                // 多行块：收集后面所有缩进的行。
                StringBuilder block = new StringBuilder();
                while (index + 1 < closing && (lines[index + 1].isBlank() || lines[index + 1].startsWith(" "))) {
                    block.append(lines[++index].strip()).append('\n');
                }
                value = block.toString();
            } else if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            metadata.put(matcher.group(1), value);
        }
        return metadata;
    }
}

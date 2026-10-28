import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * s02 的四个文件工具：read_file、write_file、edit_file、glob。
 * 所有路径都经过同一套校验，只能访问项目目录内、非密钥的文件。
 */
final class FileTools {
    static final int MAX_FILE_BYTES = 64 * 1024;
    // 分页读时允许的文件上限：可以分页读，但不去扫几百 MB 的日志。
    static final long MAX_PAGED_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_GLOB_RESULTS = 200;
    private static final int DEFAULT_GREP_RESULTS = 50;
    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT = 5;
    private static final int GREP_LINE_CHARS = 300;

    private final Path root;

    FileTools(Path root) {
        this.root = root;
    }

    List<Tool> all() {
        return List.of(readFile(), writeFile(), editFile(), glob(), grep());
    }

    // -- 工具声明：名字、描述、参数 Schema，对应 Python 版的 TOOLS 数组 --

    private Tool readFile() {
        return new Tool("read_file", "读取项目中的 UTF-8 文本文件，返回文件内容。不带 offset/limit 时读整个文件（最多 64 KiB）；"
                + "大文件用 offset 和 limit 分页读，每次最多返回 64 KiB，结果末尾会说明下一页从哪一行开始。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {
                           "path": {"type": "string", "description": "相对于项目目录的文件路径"},
                           "offset": {"type": "integer", "description": "可选，从第几行开始读（从 1 开始）"},
                           "limit": {"type": "integer", "description": "可选，最多返回多少行"}
                         },
                         "required": ["path"]}
                        """),
                this::runRead);
    }

    private Tool writeFile() {
        return new Tool("write_file", "创建或覆盖项目中的文本文件，父目录不存在时自动创建。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {
                           "path": {"type": "string", "description": "相对于项目目录的文件路径"},
                           "content": {"type": "string", "description": "完整的文件内容"}
                         },
                         "required": ["path", "content"]}
                        """),
                this::runWrite);
    }

    private Tool editFile() {
        return new Tool("edit_file", "把文件中唯一出现的一段 old_text 替换为 new_text。"
                + "old_text 必须与原文完全一致，且在文件中只出现一次。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {
                           "path": {"type": "string", "description": "相对于项目目录的文件路径"},
                           "old_text": {"type": "string", "description": "要替换的原文"},
                           "new_text": {"type": "string", "description": "替换后的新文本"}
                         },
                         "required": ["path", "old_text", "new_text"]}
                        """),
                this::runEdit);
    }

    private Tool glob() {
        return new Tool("glob", "按通配符查找项目中的文件，返回相对路径列表，例如 **/*.java。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {
                           "pattern": {"type": "string", "description": "glob 通配符，相对于项目目录"}
                         },
                         "required": ["pattern"]}
                        """),
                this::runGlob);
    }

    // 真实运行中，模型只能借 bash 跑脚本搜小说，中文输出被 GBK 控制台解码成乱码，只能靠行号凑合用。
    // grep 在 Java 里按 UTF-8 读文件，不经过控制台，也就没有编码问题。
    private Tool grep() {
        return new Tool("grep", "按内容搜索项目里的 UTF-8 文本文件，返回“路径:行号: 内容”。默认按字面搜索（中文、符号都不用转义），"
                + "regex=true 时按 Java 正则。只想知道出现多少次时用 count_only=true，不返回具体行。"
                + "结果太多时只显示前 max_results 行；需要看匹配附近的内容，用 context 带上前后几行，或用 read_file 的 offset 读。",
                JSON.parseObject("""
                        {"type": "object",
                         "properties": {
                           "pattern": {"type": "string", "description": "要搜索的文字或正则"},
                           "path": {"type": "string", "description": "可选，搜索的文件或目录，默认整个项目"},
                           "glob": {"type": "string", "description": "可选，只搜匹配的文件，如 **/*.java"},
                           "regex": {"type": "boolean", "description": "可选，pattern 是否为正则，默认 false"},
                           "ignore_case": {"type": "boolean", "description": "可选，是否忽略大小写"},
                           "context": {"type": "integer", "description": "可选，每处匹配前后各带几行，0-5"},
                           "max_results": {"type": "integer", "description": "可选，最多显示多少行匹配，默认 50，最多 200"},
                           "count_only": {"type": "boolean", "description": "可选，只统计次数和分布"}
                         },
                         "required": ["pattern"]}
                        """),
                this::runGrep);
    }

    // -- 工具实现，对应 Python 版的 run_read / run_write / run_edit / run_glob --

    String runGrep(JSONObject arguments) throws IOException {
        String text = requireString(arguments, "pattern");
        boolean regex = Boolean.TRUE.equals(arguments.get("regex"));
        int flags = Boolean.TRUE.equals(arguments.get("ignore_case"))
                ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0;
        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(regex ? text : java.util.regex.Pattern.quote(text), flags);
        } catch (java.util.regex.PatternSyntaxException exception) {
            throw new IllegalArgumentException("正则写法有误：" + exception.getDescription() + "。只想按字面搜索就去掉 regex。");
        }
        Integer contextValue = optionalPositive(arguments, "context");
        int context = contextValue == null ? 0 : Math.min(contextValue, MAX_GREP_CONTEXT);
        Integer maxValue = optionalPositive(arguments, "max_results");
        int maxResults = maxValue == null ? DEFAULT_GREP_RESULTS : Math.min(maxValue, MAX_GREP_RESULTS);
        boolean countOnly = Boolean.TRUE.equals(arguments.get("count_only"));

        // 要搜的文件：path 指向文件就只搜它；指向目录（默认项目根目录）就递归，跳过 .git 和密钥文件，可再用 glob 过滤。
        Path start = arguments.get("path") instanceof String path && !path.isBlank()
                ? resolveInside(path).toRealPath() : root;
        checkRealPath(start);
        List<PathMatcher> filters = arguments.get("glob") instanceof String glob && !glob.isBlank()
                ? globVariants(glob).stream().map(variant -> FileSystems.getDefault().getPathMatcher("glob:" + variant)).toList()
                : List.of();
        List<Path> files;
        if (Files.isRegularFile(start)) {
            files = List.of(start);
        } else {
            try (Stream<Path> walk = Files.walk(start)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(path -> !root.relativize(path).toString().replace('\\', '/').matches("(.*/)?\\.git/.*"))
                        .filter(path -> !isCredentialPath(root.relativize(path)))
                        .filter(path -> filters.isEmpty() || filters.stream().anyMatch(matcher -> matcher.matches(root.relativize(path))))
                        .sorted().toList();
            }
        }

        List<String> shown = new ArrayList<>();
        List<String> perFile = new ArrayList<>();
        int matchedLines = 0;
        long occurrences = 0;
        int matchedFiles = 0;
        int skipped = 0;
        for (Path file : files) {
            List<String> lines;
            try {
                if (Files.size(file) > MAX_PAGED_FILE_BYTES || !file.toRealPath().startsWith(root)) {
                    skipped++;
                    continue;
                }
                // readAllLines 用严格的 UTF-8 解码，遇到非 UTF-8 文件会抛异常，跳过并计数。
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException | java.io.UncheckedIOException exception) {
                // 非 UTF-8（CharacterCodingException 也是 IOException）、读不了的文件：跳过并计数，不让整次搜索失败。
                skipped++;
                continue;
            }
            String relative = root.relativize(file).toString().replace('\\', '/');
            int fileLines = 0;
            long fileOccurrences = 0;
            int lastPrinted = -1;
            for (int index = 0; index < lines.size(); index++) {
                java.util.regex.Matcher matcher = pattern.matcher(lines.get(index));
                int found = 0;
                while (matcher.find()) {
                    found++;
                    if (matcher.end() == matcher.start() && matcher.end() >= lines.get(index).length()) {
                        break;
                    }
                }
                if (found == 0) {
                    continue;
                }
                fileLines++;
                fileOccurrences += found;
                matchedLines++;
                if (countOnly || matchedLines > maxResults) {
                    continue;
                }
                // 和 grep -n 一样：匹配行用冒号，上下文行用短横线，不连续的段落之间用 -- 隔开。
                int from = Math.max(0, index - context);
                if (context > 0 && lastPrinted >= 0 && from > lastPrinted + 1) {
                    shown.add("--");
                }
                for (int line = Math.max(from, lastPrinted + 1); line <= Math.min(lines.size() - 1, index + context); line++) {
                    shown.add(relative + (line == index ? ":" : "-") + (line + 1) + (line == index ? ": " : "- ")
                            + clipLine(lines.get(line)));
                    lastPrinted = line;
                }
            }
            if (fileLines > 0) {
                matchedFiles++;
                occurrences += fileOccurrences;
                perFile.add(relative + "：" + fileLines + " 行 / " + fileOccurrences + " 处");
            }
        }

        String skippedNote = skipped == 0 ? "" : "\n（跳过 " + skipped + " 个非 UTF-8、过大或指向项目外的文件）";
        if (matchedLines == 0) {
            return "没有找到“" + text + "”（搜索了 " + files.size() + " 个文件）" + skippedNote;
        }
        String summary = "找到 " + matchedLines + " 行匹配，共 " + occurrences + " 处，分布在 " + matchedFiles + " 个文件";
        if (countOnly) {
            List<String> top = perFile.subList(0, Math.min(perFile.size(), MAX_GREP_RESULTS));
            return summary + "：\n" + String.join("\n", top)
                    + (perFile.size() > top.size() ? "\n…（还有 " + (perFile.size() - top.size()) + " 个文件）" : "") + skippedNote;
        }
        String more = matchedLines > maxResults
                ? "\n…（只显示了前 " + maxResults + " 行匹配，还有 " + (matchedLines - maxResults)
                  + " 行；可以缩小 path / glob、改用 count_only，或用 read_file 的 offset 读某处附近）" : "";
        return summary + "：\n" + String.join("\n", shown) + more + skippedNote;
    }

    private static String clipLine(String line) {
        String trimmed = line.strip();
        return trimmed.length() <= GREP_LINE_CHARS ? trimmed : trimmed.substring(0, GREP_LINE_CHARS) + "…";
    }

    String runRead(JSONObject arguments) throws IOException {
        Path file = existingFile(requireString(arguments, "path"));
        Integer offset = optionalPositive(arguments, "offset");
        Integer limit = optionalPositive(arguments, "limit");
        if (offset == null && limit == null) {
            return readText(file);
        }
        return readPage(file, offset == null ? 1 : offset, limit == null ? Integer.MAX_VALUE : limit);
    }

    // 分页读：按行流式读取，只保留 [offset, offset+limit) 这一段，单页最多 64 KiB 字符。
    // 这样超过 64 KiB 的大文件也能一页页读完，而不是只能拿到一个“文件太大”的错误。
    private static String readPage(Path file, int offset, int limit) throws IOException {
        if (Files.size(file) > MAX_PAGED_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 " + MAX_PAGED_FILE_BYTES / 1024 / 1024 + " MiB，不支持读取。");
        }
        List<String> page = new java.util.ArrayList<>();
        int pageChars = 0;
        int lineNumber = 0;
        int lastIncluded = offset - 1;
        boolean truncated = false;
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(Files.newInputStream(file), decoder))) {
            for (String line; (line = reader.readLine()) != null; ) {
                lineNumber++;
                if (lineNumber < offset || truncated || page.size() >= limit) {
                    continue;
                }
                if (pageChars + line.length() + 1 > MAX_FILE_BYTES && !page.isEmpty()) {
                    truncated = true;
                    continue;
                }
                page.add(line);
                pageChars += line.length() + 1;
                lastIncluded = lineNumber;
            }
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("文件不是 UTF-8 文本。");
        }
        if (offset > lineNumber) {
            return "（文件共 " + lineNumber + " 行，offset " + offset + " 已超出末尾）";
        }
        String header = "（第 " + offset + "–" + lastIncluded + " 行，共 " + lineNumber + " 行）\n";
        String footer = lastIncluded < lineNumber
                ? "\n...（还有 " + (lineNumber - lastIncluded) + " 行，下一页用 offset=" + (lastIncluded + 1) + "）" : "";
        return header + String.join("\n", page) + footer;
    }

    private static Integer optionalPositive(JSONObject arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer number) || number <= 0) {
            throw new IllegalArgumentException(key + " 必须是正整数。");
        }
        return number;
    }

    String runWrite(JSONObject arguments) throws IOException {
        String path = requireString(arguments, "path");
        if (!(arguments.get("content") instanceof String content)) {
            throw new IllegalArgumentException("content 必须是字符串。");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("内容超过 64 KiB。");
        }
        Path file = writableFile(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return "已写入 " + bytes.length + " 字节到 " + path;
    }

    String runEdit(JSONObject arguments) throws IOException {
        String path = requireString(arguments, "path");
        String oldText = requireString(arguments, "old_text");
        if (!(arguments.get("new_text") instanceof String newText)) {
            throw new IllegalArgumentException("new_text 必须是字符串。");
        }
        Path file = existingFile(path);
        String text = readText(file);
        int first = text.indexOf(oldText);
        if (first < 0) {
            throw new IllegalArgumentException("文件中找不到 old_text，请先用 read_file 确认原文。");
        }
        // 出现多次时不猜测替换哪一处，让模型补充上下文。
        if (text.indexOf(oldText, first + 1) >= 0) {
            throw new IllegalArgumentException("old_text 在文件中出现多次，请包含更多上下文使其唯一。");
        }
        String updated = text.substring(0, first) + newText + text.substring(first + oldText.length());
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("修改后的文件超过 64 KiB。");
        }
        Files.write(file, bytes);
        return "已修改 " + path;
    }

    String runGlob(JSONObject arguments) throws IOException {
        String pattern = requireString(arguments, "pattern");
        List<PathMatcher> matchers = globVariants(pattern).stream()
                .map(variant -> FileSystems.getDefault().getPathMatcher("glob:" + variant))
                .toList();
        List<String> matches;
        try (Stream<Path> paths = Files.walk(root)) {
            matches = paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(path -> !isCredentialPath(path))
                    .filter(path -> matchers.stream().anyMatch(matcher -> matcher.matches(path)))
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
        if (matches.isEmpty()) {
            return "（没有匹配的文件）";
        }
        List<String> shown = new ArrayList<>(matches.subList(0, Math.min(MAX_GLOB_RESULTS, matches.size())));
        if (matches.size() > MAX_GLOB_RESULTS) {
            shown.add("...（共 " + matches.size() + " 个，请缩小匹配范围）");
        }
        return String.join("\n", shown);
    }

    // Java 的 **/ 至少匹配一层目录，而 Python 的 ** 可以匹配零层。
    // 把每个 **/ 展开成"保留"和"去掉"两种写法，任一匹配即可：
    // **/*.md 能找到根目录的文件，docs/zh/**/* 也能找到 docs/zh 下直接的文件。
    private static List<String> globVariants(String pattern) {
        int index = pattern.indexOf("**/");
        if (index < 0) {
            return List.of(pattern);
        }
        String head = pattern.substring(0, index);
        List<String> variants = new ArrayList<>();
        for (String tail : globVariants(pattern.substring(index + 3))) {
            variants.add(head + "**/" + tail);
            variants.add(head + tail);
        }
        return variants;
    }

    // -- 公共的路径校验，对应 Python 版的 safe_path --

    // 词法检查：必须是相对路径、不能是密钥文件、规范化后仍在项目目录内。
    private Path resolveInside(String path) {
        Path requested = Path.of(path);
        if (requested.isAbsolute() || isCredentialPath(requested)) {
            throw new IllegalArgumentException("只允许项目内的相对路径，且不能访问密钥文件。");
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路径超出项目目录。");
        }
        return resolved;
    }

    // 读取和修改用：文件必须存在，解析链接后的真实路径也要在项目内。
    private Path existingFile(String path) throws IOException {
        Path realPath = resolveInside(path).toRealPath();
        checkRealPath(realPath);
        if (!Files.isRegularFile(realPath)) {
            throw new IllegalArgumentException("path 必须指向普通文件。");
        }
        return realPath;
    }

    // 写入用：文件可能还不存在，检查最近一个已存在的上级，防止经由链接写到项目外。
    private Path writableFile(String path) throws IOException {
        Path resolved = resolveInside(path);
        Path existing = resolved;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        checkRealPath(existing.toRealPath());
        if (Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("path 指向的是目录。");
        }
        return resolved;
    }

    private void checkRealPath(Path realPath) {
        if (!realPath.startsWith(root) || isCredentialPath(root.relativize(realPath))) {
            throw new IllegalArgumentException("实际文件路径超出项目目录或指向密钥文件。");
        }
    }

    private static String readText(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("文件超过 64 KiB，请用 offset 和 limit 分页读取。");
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    static String requireString(JSONObject arguments, String key) {
        if (!(arguments.get(key) instanceof String value) || value.isEmpty()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        return value;
    }

    private static boolean isCredentialPath(Path path) {
        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".env") || name.startsWith(".env.")
                    || name.endsWith(".pem") || name.endsWith(".key")
                    || name.endsWith(".p12") || name.endsWith(".pfx")) {
                return true;
            }
        }
        return false;
    }
}

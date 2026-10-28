import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * s03 的权限管线：每次工具调用在执行前依次经过三道闸门。
 * 1. 拒绝列表：永远禁止的操作，直接拒绝。
 * 2. 规则匹配：可能有风险的操作，交给闸门 3。
 * 3. 用户审批：暂停，等用户确认。
 * 都没命中就放行。字符串匹配只用来说明闸门的位置，挡不住刻意绕过，不是完整的安全边界。
 */
final class Permission {

    // 闸门 3 的审批方式：正式运行时问控制台，测试时注入固定答案。
    @FunctionalInterface
    interface Approver {
        boolean approve(String toolName, JSONObject arguments, String reason);
    }

    record Rule(Set<String> tools, Predicate<JSONObject> check, String message) {
    }

    // 闸门 1：拒绝列表，忽略大小写匹配命令文本。
    private static final List<String> DENY_LIST = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda",
            "format c:", "diskpart");

    // 闸门 2 用：命令开头或 ; & | ( 换行之后出现删除类命令。echo del、model 这类不算。
    private static final Pattern DESTRUCTIVE_COMMAND = Pattern.compile(
            "(?i)(?:^|[;&|()\\n])\\s*(?:rm|del|erase|rd|rmdir)(?=\\s|$|[;&|()])");

    // s17 之后补的规则：修改系统环境（环境变量、注册表、计划任务、服务、文件权限）。
    // 真实运行中，模型为了“完成”一个做不到的目标，用 setx 改写了用户 PATH。
    private static final Pattern SYSTEM_CHANGE = Pattern.compile(
            "(?i)\\b(?:setx|reg\\s+(?:add|delete|import|copy|load|restore)|SetEnvironmentVariable"
                    + "|(?:Set|New|Remove|Rename)-ItemProperty|schtasks\\s+/(?:create|change|delete)"
                    + "|sc(?:\\.exe)?\\s+(?:create|config|delete)|icacls|takeown)\\b");

    // 写入类操作。先去掉 2>nul、>nul、2>&1 这类只是丢弃或合并输出的重定向，免得误判。
    private static final Pattern WRITE_OPERATION = Pattern.compile(
            "(?i)>|\\b(?:copy|xcopy|robocopy|move|ren|rename|mkdir|md|csc|Out-File|Set-Content|Add-Content"
                    + "|New-Item|Copy-Item|Move-Item)\\b");
    private static final Pattern DISCARDED_OUTPUT = Pattern.compile("(?i)\\d?>\\s*(?:nul|&\\d)");
    // 指向项目外的写法：绝对路径、用户目录和系统目录的环境变量、~、..\ 往上跳。
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?<![\\w])[A-Za-z]:[\\\\/]");
    private static final Pattern OUTSIDE_HINT = Pattern.compile(
            "(?i)%(?:USERPROFILE|HOMEPATH|HOMEDRIVE|APPDATA|LOCALAPPDATA|TEMP|TMP|PROGRAMFILES|PROGRAMDATA|WINDIR|SYSTEMROOT)%"
                    + "|\\$env:|(?:^|[\\s\"'])~[\\\\/]|\\.\\.[\\\\/]|\\\\\\\\");

    private final Approver approver;
    // 闸门 2：规则表。文件工具已有路径沙箱硬拦截，这里只需要管 bash。
    private final List<Rule> rules;

    Permission(Approver approver) {
        this(approver, null);
    }

    // root 为 null 时不检查“写到项目外”。
    Permission(Approver approver, java.nio.file.Path root) {
        this.approver = approver;
        String rootText = root == null ? null : root.toAbsolutePath().normalize().toString()
                .replace('/', '\\').toLowerCase(Locale.ROOT);
        List<Rule> list = new java.util.ArrayList<>(List.of(
                new Rule(Set.of("bash"),
                        arguments -> DESTRUCTIVE_COMMAND.matcher(command(arguments)).find(),
                        "可能有破坏性的命令（删除文件或目录）"),
                new Rule(Set.of("bash"),
                        arguments -> SYSTEM_CHANGE.matcher(command(arguments)).find(),
                        "会修改系统环境（环境变量、注册表、计划任务、服务或文件权限）")));
        if (rootText != null) {
            list.add(new Rule(Set.of("bash"),
                    arguments -> writesOutside(command(arguments), rootText),
                    "可能写入项目目录以外的位置"));
        }
        this.rules = List.copyOf(list);
    }

    // 字符串匹配的近似判断：命令里既有写入操作，又出现了指向项目外的路径。
    // 宁可多问一次（比如把项目外的目录列表重定向到项目内文件），也不放过 cd 到用户目录再写文件这种情况。
    static boolean writesOutside(String command, String rootText) {
        String cleaned = DISCARDED_OUTPUT.matcher(command).replaceAll(" ");
        if (!WRITE_OPERATION.matcher(cleaned).find()) {
            return false;
        }
        if (OUTSIDE_HINT.matcher(cleaned).find()) {
            return true;
        }
        String normalized = cleaned.replace('/', '\\').toLowerCase(Locale.ROOT);
        java.util.regex.Matcher absolute = ABSOLUTE_PATH.matcher(normalized);
        while (absolute.find()) {
            // 路径里可能有空格，所以不切分 token，而是看这个位置是不是以项目根目录开头。
            if (!normalized.startsWith(rootText, absolute.start())) {
                return true;
            }
        }
        return false;
    }

    // 允许执行时返回 null；拒绝时返回回传给模型的说明。
    String check(String toolName, JSONObject arguments) {
        if ("bash".equals(toolName)) {
            String command = command(arguments).toLowerCase(Locale.ROOT);
            for (String denied : DENY_LIST) {
                if (command.contains(denied)) {
                    return "权限拒绝: 命令包含禁止的操作 '" + denied + "'，不会执行。";
                }
            }
        }
        for (Rule rule : rules) {
            if (rule.tools().contains(toolName) && rule.check().test(arguments)) {
                if (approver.approve(toolName, arguments, rule.message())) {
                    return null;
                }
                return "权限拒绝: 用户没有批准这次操作（" + rule.message() + "）。"
                        + "不要换个写法绕过，请改用其他做法或向用户说明。";
            }
        }
        return null;
    }

    private static String command(JSONObject arguments) {
        return arguments.get("command") instanceof String command ? command : "";
    }
}

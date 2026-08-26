package org.chosi123.random_mining.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.chosi123.random_mining.Random_mining;
import org.chosi123.random_mining.config.RandomMiningConfig;
import org.chosi123.random_mining.hardness.HardnessTable;
import org.chosi123.random_mining.hardness.HardnessTier;
import org.chosi123.random_mining.mining.MiningSpeed;
import org.chosi123.random_mining.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /randommining (별칭 /rm) 처리기. */
public final class RandomMiningCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("info", "list", "reroll", "reload", "toggle");
    private static final int MAX_LIST = 25;

    private final Random_mining plugin;

    public RandomMiningCommand(Random_mining plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> { if (requireUse(sender)) info(sender, args); }
            case "list" -> { if (requireUse(sender)) list(sender, args); }
            case "reroll" -> reroll(sender);
            case "reload" -> reload(sender);
            case "toggle" -> toggle(sender);
            default -> help(sender);
        }
        return true;
    }

    // ── 서브커맨드 ───────────────────────────────────────────────

    private void help(CommandSender sender) {
        sender.sendMessage(Text.PREFIX.append(
                Component.text("블럭 경도가 주기적으로 뒤바뀝니다. 채굴을 멈추면 죽습니다.", NamedTextColor.GRAY)));
        sender.sendMessage(usage("/rm info [블럭]", "보고 있는 블럭(또는 지정한 블럭)의 현재 경도"));
        sender.sendMessage(usage("/rm list [hard|soft] [개수]", "가장 단단한/무른 블럭 순위"));
        if (sender.hasPermission("randommining.admin")) {
            sender.sendMessage(usage("/rm reroll", "경도를 지금 즉시 재배치"));
            sender.sendMessage(usage("/rm reload", "config.yml 다시 읽기"));
            sender.sendMessage(usage("/rm toggle", "플러그인 로직 on/off"));
        }
        long remaining = plugin.rerollService().secondsUntilNextReroll();
        if (remaining >= 0) {
            sender.sendMessage(Text.info("다음 재배치까지 " + remaining + "초"));
        }
    }

    private void info(CommandSender sender, String[] args) {
        RandomMiningConfig config = plugin.config();
        HardnessTable table = plugin.hardnessTable();

        if (args.length >= 2) {
            Material material = Material.matchMaterial(args[1]);
            if (material == null || !material.isBlock()) {
                sender.sendMessage(Text.error("'" + args[1] + "' 는 알 수 없는 블럭입니다."));
                return;
            }
            if (config.mode() == RandomMiningConfig.HardnessMode.PER_BLOCK) {
                sender.sendMessage(Text.error("PER_BLOCK 모드에서는 좌표마다 경도가 달라 블럭 이름으로 조회할 수 없습니다."));
                return;
            }
            double multiplier = table.multiplierOf(material);
            sender.sendMessage(Text.PREFIX.append(line(material, multiplier, table)));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.error("콘솔에서는 블럭 이름을 함께 적어주세요. /rm info <블럭>"));
            return;
        }
        Block target = player.getTargetBlockExact(8);
        if (target == null || target.getType().isAir()) {
            sender.sendMessage(Text.error("보고 있는 블럭이 없습니다."));
            return;
        }

        double multiplier = table.multiplierOf(target);
        sender.sendMessage(Text.PREFIX.append(line(target.getType(), multiplier, table)));

        int ticks = plugin.miningManager().estimateBreakTicks(
                player, target, player.getInventory().getItemInMainHand());
        Component detail;
        if (ticks == MiningSpeed.UNBREAKABLE) {
            detail = Component.text("부술 수 없는 블럭입니다.", NamedTextColor.DARK_GRAY);
        } else if (ticks == 0) {
            detail = Component.text("즉시 파괴 (즉사 판정 없음)", NamedTextColor.DARK_GRAY);
        } else {
            detail = Component.text("지금 든 도구로 " + Text.round(ticks / 20.0D, 1) + "초", NamedTextColor.GRAY)
                    .append(Component.text(" (바닐라 경도 " + Text.round(target.getType().getHardness(), 2) + ")",
                            NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(Text.PREFIX.append(detail));
    }

    private void list(CommandSender sender, String[] args) {
        if (plugin.config().mode() == RandomMiningConfig.HardnessMode.PER_BLOCK) {
            sender.sendMessage(Text.error("PER_BLOCK 모드에서는 블럭 종류별 순위가 없습니다."));
            return;
        }
        boolean hardest = args.length < 2 || !args[1].equalsIgnoreCase("soft");
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.max(1, Math.min(MAX_LIST, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage(Text.error("개수는 숫자로 적어주세요."));
                return;
            }
        }

        HardnessTable table = plugin.hardnessTable();
        List<Map.Entry<Material, Double>> entries = table.sorted(hardest, limit);
        sender.sendMessage(Text.PREFIX.append(Component
                .text(hardest ? "가장 단단한 블럭 " : "가장 무른 블럭 ", NamedTextColor.WHITE)
                .append(Component.text("TOP " + entries.size(), NamedTextColor.YELLOW))));

        int rank = 1;
        for (Map.Entry<Material, Double> entry : entries) {
            sender.sendMessage(Component.text(" " + rank++ + ". ", NamedTextColor.DARK_GRAY)
                    .append(line(entry.getKey(), entry.getValue(), table)));
        }
    }

    private boolean requireUse(CommandSender sender) {
        if (sender.hasPermission("randommining.use")) return true;
        sender.sendMessage(Text.error("권한이 없습니다."));
        return false;
    }

    private void reroll(CommandSender sender) {
        if (!sender.hasPermission("randommining.admin")) {
            sender.sendMessage(Text.error("권한이 없습니다."));
            return;
        }
        plugin.rerollService().reroll(true);
        sender.sendMessage(Text.success("경도를 재배치했습니다."));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("randommining.admin")) {
            sender.sendMessage(Text.error("권한이 없습니다."));
            return;
        }
        plugin.reloadPlugin();
        sender.sendMessage(Text.success("config.yml 을 다시 읽고 경도를 재배치했습니다."));
    }

    private void toggle(CommandSender sender) {
        if (!sender.hasPermission("randommining.admin")) {
            sender.sendMessage(Text.error("권한이 없습니다."));
            return;
        }
        boolean now = plugin.miningManager().toggle();
        plugin.miningManager().refreshAllLocks();
        sender.sendMessage(now ? Text.success("랜덤 채굴을 켰습니다.") : Text.info("랜덤 채굴을 껐습니다. (바닐라 채굴로 돌아갑니다)"));
    }

    // ── 표시 도우미 ──────────────────────────────────────────────

    private Component line(Material material, double multiplier, HardnessTable table) {
        HardnessTier tier = table.tierOf(multiplier);
        return Component.text(Text.materialName(material), NamedTextColor.AQUA)
                .append(Component.text(" ×" + Text.round(multiplier, 2), NamedTextColor.WHITE))
                .append(Component.text(" [" + tier.label() + "]", tier.color()));
    }

    private Component usage(String command, String description) {
        return Component.text(" " + command, NamedTextColor.YELLOW)
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text(description, NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    // ── 탭 완성 ──────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return filter(List.of("hard", "soft"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            List<String> blocks = new ArrayList<>();
            for (Material material : Material.values()) {
                if (material.isLegacy() || !material.isBlock()) continue;
                blocks.add(material.name().toLowerCase(Locale.ROOT));
            }
            return filter(blocks, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) out.add(option);
        }
        return out;
    }
}

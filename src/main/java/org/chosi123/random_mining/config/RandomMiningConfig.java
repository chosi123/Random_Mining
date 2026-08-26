package org.chosi123.random_mining.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * config.yml 을 한 번에 읽어 불변 스냅샷으로 들고 있는 홀더.
 * 리로드는 새 인스턴스를 만들어 갈아끼우는 방식이라 읽는 쪽에서 동기화가 필요 없다.
 */
public final class RandomMiningConfig {

    public enum HardnessMode { PER_MATERIAL, PER_BLOCK }

    private static final String DEFAULT_MESSAGE_SELF =
            "<dark_red>☠ <white><player></white> 님은 <red>채굴을 중도 포기</red>했습니다.";
    private static final String DEFAULT_MESSAGE_VICTIM =
            "<dark_red>☠ <white><player></white> 님이 채굴을 포기해 <white><victim></white> 님이 대신 죽었습니다.";
    private static final String DEFAULT_MESSAGE_EVERYONE =
            "<dark_red>☠ <white><player></white> 님이 채굴을 포기해 <red><bold>전원 사망</bold></red> — <gray><victims></gray>";

    private final boolean enabled;
    private final Set<String> enabledWorlds;
    private final Set<String> disabledWorlds;
    private final String bypassPermission;

    private final HardnessMode mode;
    private final int rerollIntervalSeconds;
    private final double minMultiplier;
    private final double maxMultiplier;
    private final int minBreakTicks;
    private final int maxBreakTicks;
    private final double instantBreakMaxHardness;
    private final Set<Material> exemptMaterials;
    private final boolean announceChat;
    private final boolean announceTitle;
    private final boolean announceSound;

    private final boolean deathEnabled;
    private final int graceTicks;
    private final int postBreakImmunityTicks;
    private final boolean deathLightning;
    private final boolean deathSound;

    private final boolean rouletteEnabled;
    private final double everyoneChance;
    private final int rouletteSpinTicks;
    private final int rouletteMinIntervalTicks;
    private final int rouletteMaxIntervalTicks;
    private final boolean rouletteSound;

    private final String messageSelf;
    private final String messageVictim;
    private final String messageEveryone;

    private final boolean actionBar;
    private final int actionBarLength;
    private final boolean shareCrack;
    private final double crackShareRadius;

    private RandomMiningConfig(JavaPlugin plugin, FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);
        this.enabledWorlds = lowerSet(c.getStringList("enabled-worlds"));
        this.disabledWorlds = lowerSet(c.getStringList("disabled-worlds"));
        this.bypassPermission = c.getString("bypass-permission", "randommining.bypass");

        HardnessMode parsed;
        try {
            parsed = HardnessMode.valueOf(c.getString("hardness.mode", "PER_MATERIAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("hardness.mode 값이 잘못되었습니다. PER_MATERIAL 로 대체합니다.");
            parsed = HardnessMode.PER_MATERIAL;
        }
        this.mode = parsed;

        this.rerollIntervalSeconds = c.getInt("hardness.reroll-interval-seconds", 120);

        double min = Math.max(0.001D, c.getDouble("hardness.min-multiplier", 0.15D));
        double max = Math.max(min, c.getDouble("hardness.max-multiplier", 10.0D));
        this.minMultiplier = min;
        this.maxMultiplier = max;

        int minTicks = Math.max(1, c.getInt("hardness.min-break-ticks", 4));
        int maxTicks = Math.max(minTicks, c.getInt("hardness.max-break-ticks", 1200));
        this.minBreakTicks = minTicks;
        this.maxBreakTicks = maxTicks;

        this.instantBreakMaxHardness = c.getDouble("hardness.instant-break-max-vanilla-hardness", 0.0D);

        Set<Material> exempt = EnumSet.noneOf(Material.class);
        for (String raw : c.getStringList("hardness.exempt-materials")) {
            Material m = Material.matchMaterial(raw);
            if (m == null || !m.isBlock()) {
                plugin.getLogger().warning("exempt-materials 의 '" + raw + "' 는 알 수 없는 블럭입니다. 무시합니다.");
                continue;
            }
            exempt.add(m);
        }
        this.exemptMaterials = exempt;

        this.announceChat = c.getBoolean("hardness.announce.chat", true);
        this.announceTitle = c.getBoolean("hardness.announce.title", true);
        this.announceSound = c.getBoolean("hardness.announce.sound", true);

        this.deathEnabled = c.getBoolean("death.enabled", true);
        this.graceTicks = Math.max(0, c.getInt("death.grace-ticks", 3));
        this.postBreakImmunityTicks = Math.max(0, c.getInt("death.post-break-immunity-ticks", 20));
        this.deathLightning = c.getBoolean("death.lightning-effect", true);
        this.deathSound = c.getBoolean("death.sound", true);

        this.rouletteEnabled = c.getBoolean("death.roulette.enabled", true);
        this.everyoneChance = Math.max(0.0D, c.getDouble("death.roulette.everyone-chance", 100.0D));
        this.rouletteSpinTicks = Math.max(1, c.getInt("death.roulette.spin-ticks", 60));
        int minInterval = Math.max(1, c.getInt("death.roulette.min-interval-ticks", 2));
        this.rouletteMinIntervalTicks = minInterval;
        this.rouletteMaxIntervalTicks = Math.max(minInterval, c.getInt("death.roulette.max-interval-ticks", 9));
        this.rouletteSound = c.getBoolean("death.roulette.sound", true);

        // death.message 는 룰렛이 없던 시절의 키다. 남아 있으면 self 메시지로 받아준다.
        String legacy = c.getString("death.message");
        this.messageSelf = c.getString("death.messages.self", legacy != null ? legacy : DEFAULT_MESSAGE_SELF);
        this.messageVictim = c.getString("death.messages.victim", DEFAULT_MESSAGE_VICTIM);
        this.messageEveryone = c.getString("death.messages.everyone", DEFAULT_MESSAGE_EVERYONE);

        this.actionBar = c.getBoolean("display.action-bar", true);
        this.actionBarLength = Math.max(4, Math.min(60, c.getInt("display.action-bar-length", 20)));
        this.shareCrack = c.getBoolean("display.share-crack-animation", true);
        this.crackShareRadius = Math.max(0.0D, c.getDouble("display.crack-share-radius", 48.0D));
    }

    public static RandomMiningConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        return new RandomMiningConfig(plugin, plugin.getConfig());
    }

    private static Set<String> lowerSet(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    public boolean isWorldEnabled(String worldName) {
        String key = worldName.toLowerCase(Locale.ROOT);
        if (disabledWorlds.contains(key)) return false;
        return enabledWorlds.isEmpty() || enabledWorlds.contains(key);
    }

    public boolean enabled() { return enabled; }
    public String bypassPermission() { return bypassPermission; }

    public HardnessMode mode() { return mode; }
    public int rerollIntervalSeconds() { return rerollIntervalSeconds; }
    public double minMultiplier() { return minMultiplier; }
    public double maxMultiplier() { return maxMultiplier; }
    public int minBreakTicks() { return minBreakTicks; }
    public int maxBreakTicks() { return maxBreakTicks; }
    public double instantBreakMaxHardness() { return instantBreakMaxHardness; }
    public Set<Material> exemptMaterials() { return exemptMaterials; }
    public boolean announceChat() { return announceChat; }
    public boolean announceTitle() { return announceTitle; }
    public boolean announceSound() { return announceSound; }

    public boolean deathEnabled() { return deathEnabled; }
    public int graceTicks() { return graceTicks; }
    public int postBreakImmunityTicks() { return postBreakImmunityTicks; }
    public boolean deathLightning() { return deathLightning; }
    public boolean deathSound() { return deathSound; }

    public boolean rouletteEnabled() { return rouletteEnabled; }
    /** 전원 사망 확률(%) = 이 값 / (대상 인원 + 1) */
    public double everyoneChance() { return everyoneChance; }
    public int rouletteSpinTicks() { return rouletteSpinTicks; }
    public int rouletteMinIntervalTicks() { return rouletteMinIntervalTicks; }
    public int rouletteMaxIntervalTicks() { return rouletteMaxIntervalTicks; }
    public boolean rouletteSound() { return rouletteSound; }

    public String messageSelf() { return messageSelf; }
    public String messageVictim() { return messageVictim; }
    public String messageEveryone() { return messageEveryone; }

    public boolean actionBar() { return actionBar; }
    public int actionBarLength() { return actionBarLength; }
    public boolean shareCrack() { return shareCrack; }
    public double crackShareRadius() { return crackShareRadius; }
}

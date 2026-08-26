package org.chosi123.random_mining.hardness;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.chosi123.random_mining.config.RandomMiningConfig;
import org.chosi123.random_mining.mining.MiningManager;
import org.chosi123.random_mining.util.Text;

import java.time.Duration;

/** 경도 재배치를 주기적으로 돌리고 서버에 알린다. */
public final class RerollService {

    private final Plugin plugin;
    private final HardnessTable table;
    private final MiningManager miningManager;

    private RandomMiningConfig config;
    private BukkitTask task;
    private long nextRerollAtMillis;

    public RerollService(Plugin plugin, RandomMiningConfig config, HardnessTable table, MiningManager miningManager) {
        this.plugin = plugin;
        this.config = config;
        this.table = table;
        this.miningManager = miningManager;
    }

    public void start(RandomMiningConfig config) {
        this.config = config;
        stop();
        table.reroll();
        nextRerollAtMillis = 0L;

        int seconds = config.rerollIntervalSeconds();
        if (seconds <= 0) return;

        long period = seconds * 20L;
        nextRerollAtMillis = System.currentTimeMillis() + seconds * 1000L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> reroll(true), period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 지금 즉시 재배치. */
    public void reroll(boolean announce) {
        table.reroll();
        // 진행 중이던 채굴은 옛날 경도로 계산되어 있으므로 조용히 접는다. (즉사시키지 않는다)
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            miningManager.cancelSessionSilently(player);
        }
        int seconds = config.rerollIntervalSeconds();
        nextRerollAtMillis = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : 0L;
        if (announce) announce();
    }

    /** 다음 재배치까지 남은 초. 재배치를 쓰지 않으면 -1. */
    public long secondsUntilNextReroll() {
        if (nextRerollAtMillis <= 0L) return -1L;
        return Math.max(0L, (nextRerollAtMillis - System.currentTimeMillis()) / 1000L);
    }

    private void announce() {
        Component titleLine = Component.text("경도 재배치", NamedTextColor.RED).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("모든 블럭의 단단함이 뒤바뀌었습니다", NamedTextColor.GRAY);
        Title title = Title.title(titleLine, subtitle,
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)));

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (config.announceTitle()) player.showTitle(title);
            if (config.announceChat()) {
                player.sendMessage(Text.info("블럭 경도가 새로 배치되었습니다. /rm info 로 확인하세요."));
            }
            if (config.announceSound()) {
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.MASTER, 1.0F, 0.7F);
            }
        }
    }
}

package org.chosi123.random_mining.punish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.chosi123.random_mining.config.RandomMiningConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 채굴을 포기했을 때의 벌을 집행한다.
 *
 * <p>포기한 본인이 죽는 게 아니라 남이 죽는다.</p>
 * <ol>
 *   <li>{@code everyone-chance / (대상 인원 + 1)} % 확률로 전원 사망.</li>
 *   <li>아니면 본인을 뺀 나머지 중 한 명이 룰렛으로 뽑혀 죽는다.</li>
 *   <li>서버에 혼자뿐이면 대신 죽을 사람이 없으므로 본인이 죽는다.</li>
 * </ol>
 */
public final class PunishmentService {

    private final Plugin plugin;
    private final Random random = new Random();

    /** 사망 메시지를 갈아끼울 플레이어들. {@link #takeDeathMessage} 로 한 번만 꺼내 쓴다. */
    private final Map<UUID, Component> pendingDeathMessages = new HashMap<>();
    /** 전원 사망처럼 한 줄로 묶어 알릴 때, 개별 사망 메시지를 숨길 플레이어들. */
    private final Set<UUID> silencedDeaths = new HashSet<>();

    private RandomMiningConfig config;
    private boolean spinning;

    public PunishmentService(Plugin plugin, RandomMiningConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void applyConfig(RandomMiningConfig config) {
        this.config = config;
    }

    public void clear() {
        pendingDeathMessages.clear();
        silencedDeaths.clear();
        spinning = false;
    }

    /** 사망 메시지로 쓸 컴포넌트. 없으면 null(바닐라 메시지 유지). */
    public Component takeDeathMessage(Player player) {
        return pendingDeathMessages.remove(player.getUniqueId());
    }

    /** 이 플레이어의 사망 메시지를 숨겨야 하는지. */
    public boolean takeSilenced(Player player) {
        return silencedDeaths.remove(player.getUniqueId());
    }

    /**
     * 벌을 집행한다.
     *
     * @param canceler 채굴을 포기한 사람
     * @param pool     벌 대상이 될 수 있는 온라인 플레이어 전원 (canceler 포함)
     */
    public void punish(Player canceler, List<Player> pool) {
        List<Player> candidates = new ArrayList<>(pool);

        if (candidates.isEmpty()) {
            // 대신 죽어줄 사람이 없다. 원래대로 본인이 죽는다.
            killSelf(canceler);
            return;
        }

        double chancePercent = config.everyoneChance() / (pool.size() + 1);
        if (random.nextDouble() * 100.0D < chancePercent) {
            killEveryone(canceler, pool);
            return;
        }

        Player victim = candidates.get(random.nextInt(candidates.size()));
        if (!config.rouletteEnabled() || spinning) {
            // 룰렛이 이미 돌고 있으면 타이틀이 겹치므로 연출 없이 바로 처리한다.
            killVictim(canceler, victim);
            return;
        }
        spinning = true;
        new Roulette(canceler, candidates, victim).run();
    }

    // ── 집행 ─────────────────────────────────────────────────────

    private void killSelf(Player canceler) {
        kill(canceler, render(config.messageSelf(), Placeholder.component("player", canceler.name())));
    }

    private void killVictim(Player canceler, Player victim) {
        showVerdict(victim.name());
        kill(victim, render(config.messageVictim(),
                Placeholder.component("player", canceler.name()),
                Placeholder.component("victim", victim.name())));
    }

    private void killEveryone(Player canceler, List<Player> pool) {
        Component names = Component.join(JoinConfiguration.commas(true),
                pool.stream().map(Player::name).toList());
        Component announcement = render(config.messageEveryone(),
                Placeholder.component("player", canceler.name()),
                Placeholder.component("victims", names));

        showVerdict(Component.text("전원 사망"));

        // 개별 사망 메시지를 그대로 두면 인원수만큼 같은 줄이 도배된다. 한 줄로 묶어 알린다.
        for (Player player : pool) {
            kill(player, null);
        }
        plugin.getServer().broadcast(announcement);
    }

    private void kill(Player player, Component deathMessage) {
        if (player.isDead() || player.getHealth() <= 0.0D) return;

        if (deathMessage != null) {
            pendingDeathMessages.put(player.getUniqueId(), deathMessage);
        } else {
            silencedDeaths.add(player.getUniqueId());
        }
        if (config.deathLightning()) {
            player.getWorld().strikeLightningEffect(player.getLocation());
        }
        if (config.deathSound()) {
            player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                    SoundCategory.MASTER, 1.0F, 1.2F);
        }
        // setHealth(0) 은 방어구·저항·토템을 전부 무시한다. "즉사"라는 규칙에 맞는 유일한 방법.
        player.setHealth(0.0D);
    }

    private Component render(String miniMessage, TagResolver... placeholders) {
        return MiniMessage.miniMessage().deserialize(miniMessage, placeholders);
    }

    // ── 룰렛 ─────────────────────────────────────────────────────

    /**
     * 후보 이름을 점점 느리게 바꿔가며 보여주다가 멈춘다.
     *
     * <p>{@code BukkitRunnable} 은 한 번밖에 예약할 수 없어서, 매 프레임 스스로를
     * 다시 예약하는 평범한 {@link Runnable} 로 만들었다. 간격이 매 프레임 달라져야
     * 감속이 표현되므로 고정 주기 태스크로는 안 된다.</p>
     */
    private final class Roulette implements Runnable {

        private final Player canceler;
        private final List<Player> candidates;
        private final Player victim;
        private int elapsed;

        private Roulette(Player canceler, List<Player> candidates, Player victim) {
            this.canceler = canceler;
            this.candidates = candidates;
            this.victim = victim;
        }

        @Override
        public void run() {
            if (!plugin.isEnabled()) {
                spinning = false;
                return;
            }
            int spinTicks = config.rouletteSpinTicks();
            if (elapsed >= spinTicks) {
                finish();
                return;
            }

            double progress = elapsed / (double) spinTicks;
            int min = config.rouletteMinIntervalTicks();
            int max = config.rouletteMaxIntervalTicks();
            // progress² 로 감속하면 처음엔 촤르륵 돌다가 끝에서 뚝뚝 끊긴다.
            int interval = Math.max(1, (int) Math.round(min + (max - min) * progress * progress));

            showSpinFrame(candidates.get(random.nextInt(candidates.size())).name(), interval);
            if (config.rouletteSound()) {
                playToAll(Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, (float) (0.8D + progress * 1.0D));
            }

            elapsed += interval;
            plugin.getServer().getScheduler().runTaskLater(plugin, this, interval);
        }

        private void finish() {
            spinning = false;
            Player target = alive(victim);
            if (target == null) {
                // 돌아가는 사이에 후보가 전부 나가버렸다.
                for (Player candidate : candidates) {
                    target = alive(candidate);
                    if (target != null) break;
                }
            }
            if (target == null) {
                if (canceler.isOnline() && !canceler.isDead()) killSelf(canceler);
                return;
            }
            killVictim(canceler, target);
        }

        private Player alive(Player player) {
            return player.isOnline() && !player.isDead() ? player : null;
        }
    }

    // ── 연출 ─────────────────────────────────────────────────────

    private void showSpinFrame(Component name, int intervalTicks) {
        // 프레임이 바뀌는 동안만 떠 있으면 되므로 페이드 없이 딱 그 시간만 유지한다.
        Title title = Title.title(
                name.color(NamedTextColor.YELLOW),
                Component.text("누가 대신 죽을까...", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(intervalTicks * 50L + 200L), Duration.ZERO));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showTitle(title);
        }
    }

    private void showVerdict(Component name) {
        Title title = Title.title(
                name.color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                Component.text("☠", NamedTextColor.DARK_RED),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1800L), Duration.ofMillis(600L)));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showTitle(title);
        }
        playToAll(Sound.BLOCK_ANVIL_LAND, 1.0F, 0.7F);
    }

    private void playToAll(Sound sound, float volume, float pitch) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        }
    }
}

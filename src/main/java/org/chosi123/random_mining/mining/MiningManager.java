package org.chosi123.random_mining.mining;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.chosi123.random_mining.config.RandomMiningConfig;
import org.chosi123.random_mining.hardness.HardnessTable;
import org.chosi123.random_mining.hardness.HardnessTier;
import org.chosi123.random_mining.punish.PunishmentService;
import org.chosi123.random_mining.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 커스텀 채굴의 심장부.
 *
 * <p>바닐라 채굴은 {@link BreakSpeedLock} 으로 완전히 멈춰 세워 두고, 여기서 매 틱
 * 진행도를 직접 굴린다. 진행도가 다 차면 우리가 직접 {@link BlockBreakEvent} 를 발생시키고
 * 블럭을 부순다. 도중에 취소 신호가 오면 {@link PunishmentService} 에 넘긴다.</p>
 */
public final class MiningManager {

    /** 취소 신호를 무시해야 하는 좌표와 만료 틱. */
    private record AbortImmunity(Location location, long expiresAtTick) {}

    /** 이번 틱에 진행도를 다 채운 채굴. 순회가 끝난 뒤에 실제로 부순다. */
    private record CompletedBreak(Player player, Block block) {}

    private final Plugin plugin;
    private final HardnessTable hardnessTable;
    private final BreakSpeedLock lock;
    private final MiningSpeed speed;
    private final PunishmentService punishment;

    private final Map<UUID, MiningSession> sessions = new HashMap<>();
    private final Map<UUID, AbortImmunity> immunities = new HashMap<>();

    private RandomMiningConfig config;
    private BukkitTask tickTask;
    private long currentTick;

    /** 우리가 직접 부수는 중임을 알리는 플래그. 안전망 리스너가 자기 이벤트를 막지 않게 한다. */
    private boolean breakingInternally;

    /** /rm toggle 로 껐다 켤 수 있는 런타임 스위치. config.enabled 와 AND 로 묶인다. */
    private boolean runtimeEnabled = true;

    public MiningManager(Plugin plugin, RandomMiningConfig config, HardnessTable hardnessTable,
                         BreakSpeedLock lock, PunishmentService punishment) {
        this.plugin = plugin;
        this.config = config;
        this.hardnessTable = hardnessTable;
        this.lock = lock;
        this.punishment = punishment;
        this.speed = new MiningSpeed(lock);
    }

    public void applyConfig(RandomMiningConfig config) {
        this.config = config;
        cancelAllSessions();
    }

    public void start() {
        if (tickTask != null) return;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        cancelAllSessions();
        immunities.clear();
    }

    public boolean isBreakingInternally() {
        return breakingInternally;
    }

    public boolean isActive() {
        return config.enabled() && runtimeEnabled;
    }

    /** @return 바뀐 뒤의 상태 */
    public boolean toggle() {
        runtimeEnabled = !runtimeEnabled;
        cancelAllSessions();
        return runtimeEnabled;
    }

    /**
     * 이 도구로 저 블럭을 캐는 데 실제로 걸리는 틱 수. 명령어 안내용.
     *
     * @return 파괴 불가면 {@link MiningSpeed#UNBREAKABLE}, 즉시 파괴면 0
     */
    public int estimateBreakTicks(Player player, Block block, ItemStack tool) {
        int vanillaTicks = speed.vanillaBreakTicks(player, block, tool);
        if (vanillaTicks == MiningSpeed.UNBREAKABLE) return MiningSpeed.UNBREAKABLE;
        if (block.getType().getHardness() <= config.instantBreakMaxHardness()) return 0;
        if (!applies(block)) return Math.max(1, vanillaTicks);

        int required = (int) Math.round(Math.max(1.0D, vanillaTicks * hardnessTable.multiplierOf(block)));
        return Math.max(config.minBreakTicks(), Math.min(config.maxBreakTicks(), required));
    }

    public MiningSession sessionOf(Player player) {
        return sessions.get(player.getUniqueId());
    }

    /** 채굴 포기의 벌을 받을 수 있는 온라인 플레이어. 크리에이티브·bypass·비활성 월드는 빠진다. */
    public List<Player> punishablePlayers() {
        List<Player> out = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isDead() && applies(player)) out.add(player);
        }
        return out;
    }

    // ── 적용 대상 판별 ────────────────────────────────────────────

    /** 이 플레이어가 커스텀 채굴 규칙의 적용 대상인지. */
    public boolean applies(Player player) {
        if (!isActive()) return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        if (!config.isWorldEnabled(player.getWorld().getName())) return false;
        String bypass = config.bypassPermission();
        return bypass == null || bypass.isBlank() || !player.hasPermission(bypass);
    }

    /**
     * 이 블럭에 랜덤 경도와 즉사 판정을 걸어도 되는지.
     * false 여도 채굴 자체는 우리 시스템이 처리한다 — 바닐라 채굴은 어차피 잠겨 있기 때문이다.
     */
    public boolean applies(Block block) {
        return !config.exemptMaterials().contains(block.getType());
    }

    // ── 채굴 시작 / 취소 / 완료 ───────────────────────────────────

    /** 좌클릭으로 채굴을 시작했을 때. */
    public void beginMining(Player player, Block block, ItemStack tool) {
        UUID id = player.getUniqueId();
        MiningSession previous = sessions.remove(id);
        if (previous != null) {
            if (previous.block().equals(block)) {
                sessions.put(id, previous); // 같은 블럭 재시작 패킷 — 진행도를 유지한다.
                return;
            }
            // 크로스헤어를 옆 블럭으로 옮기면 클라이언트는 취소 패킷 없이 곧장 새 채굴을
            // 시작한다. 앞 블럭을 포기한 것이므로 여기서도 똑같이 심판한다.
            endAbandoned(player, previous);
            if (!player.isOnline() || player.isDead()) return;
        }

        int vanillaTicks = speed.vanillaBreakTicks(player, block, tool);
        if (vanillaTicks == MiningSpeed.UNBREAKABLE) {
            return; // 기반암 등 — 아무 일도 일어나지 않는다.
        }

        float vanillaHardness = block.getType().getHardness();
        if (vanillaHardness <= config.instantBreakMaxHardness()) {
            // 풀·횃불처럼 원래 즉시 부서지는 블럭은 그대로 즉시 부수고 즉사 판정에서 뺀다.
            grantAbortImmunity(player, block);
            performBreak(player, block, tool);
            return;
        }

        // exempt 블럭이라도 세션은 만든다. 바닐라 채굴이 잠겨 있어서
        // 우리가 대신 캐주지 않으면 영원히 부술 수 없는 블럭이 되어버린다.
        boolean exempt = !applies(block);
        double multiplier = exempt ? 1.0D : hardnessTable.multiplierOf(block);
        int required;
        if (exempt) {
            required = Math.max(1, vanillaTicks);
        } else {
            required = (int) Math.round(Math.max(1.0D, vanillaTicks * multiplier));
            required = Math.max(config.minBreakTicks(), Math.min(config.maxBreakTicks(), required));
        }

        MiningSession session = new MiningSession(block, required, multiplier, exempt);
        sessions.put(id, session);
        immunities.remove(id);
        render(player, session);
    }

    /**
     * 채굴 취소 신호가 왔을 때. 진짜 포기라면 벌을 집행한다.
     *
     * @return 벌을 집행했으면 true
     */
    public boolean abortMining(Player player, Block block) {
        UUID id = player.getUniqueId();

        AbortImmunity immunity = immunities.get(id);
        if (immunity != null && immunity.location().equals(block.getLocation())) {
            // 방금 우리가 부순 블럭에 대한 뒷북 취소 패킷 — 무시한다.
            return false;
        }

        return endAbandoned(player, sessions.remove(id));
    }

    /**
     * 끝까지 캐지 못한 세션을 정리한다.
     *
     * @return 벌을 집행했으면 true
     */
    private boolean endAbandoned(Player player, MiningSession session) {
        if (session == null) return false;
        clearCrack(player, session);

        if (!config.deathEnabled() || session.exempt()) return false;
        if (player.isDead() || player.getHealth() <= 0.0D) return false;
        if (session.elapsedTicks() < config.graceTicks()) return false;
        if (session.block().getType() != session.material()) {
            // 캐던 블럭이 다른 이유로 사라졌다 — 플레이어 잘못이 아니다.
            return false;
        }
        punishment.punish(player, punishablePlayers());
        return true;
    }

    /** 즉사가 아닌 사유(퇴장, 사망, 리로드 등)로 세션만 정리한다. */
    public void cancelSessionSilently(Player player) {
        MiningSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            clearCrack(player, session);
        }
    }

    public void cancelAllSessions() {
        for (Map.Entry<UUID, MiningSession> entry : new HashMap<>(sessions).entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) clearCrack(player, entry.getValue());
        }
        sessions.clear();
    }

    // ── 매 틱 ────────────────────────────────────────────────────

    private void tick() {
        currentTick++;
        immunities.entrySet().removeIf(e -> e.getValue().expiresAtTick() <= currentTick);
        if (sessions.isEmpty()) return;

        List<CompletedBreak> completed = null;
        for (Iterator<Map.Entry<UUID, MiningSession>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, MiningSession> entry = it.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            MiningSession session = entry.getValue();

            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            if (!applies(player) || !session.stillValid()
                    || !player.getWorld().equals(session.block().getWorld())) {
                clearCrack(player, session);
                it.remove();
                continue;
            }

            session.tick();
            if (session.isComplete()) {
                it.remove();
                clearCrack(player, session);
                grantAbortImmunity(player, session.block());
                // 파괴는 순회 밖으로 미룬다. BlockBreakEvent 를 받은 다른 플러그인이
                // 무슨 짓을 하든 sessions 순회가 깨지지 않게 하려는 것이다.
                if (completed == null) completed = new ArrayList<>(2);
                completed.add(new CompletedBreak(player, session.block()));
                continue;
            }
            render(player, session);
        }

        if (completed == null) return;
        for (CompletedBreak pending : completed) {
            Player player = pending.player();
            if (!player.isOnline() || player.isDead()) continue;
            performBreak(player, pending.block(), player.getInventory().getItemInMainHand());
        }
    }

    // ── 블럭 파괴 ────────────────────────────────────────────────

    private void performBreak(Player player, Block block, ItemStack tool) {
        Material type = block.getType();
        if (type.isAir()) return;

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        breakingInternally = true;
        try {
            plugin.getServer().getPluginManager().callEvent(event);
        } finally {
            breakingInternally = false;
        }
        if (event.isCancelled()) return;

        ItemStack used = tool == null ? new ItemStack(Material.AIR) : tool;
        if (event.isDropItems()) {
            block.breakNaturally(used, true, true);
        } else {
            block.setType(Material.AIR, true);
        }

        int exp = event.getExpToDrop();
        if (exp > 0) {
            player.giveExp(exp, true);
        }
        // 바닐라도 경도 0 인 블럭에는 내구도를 소모하지 않는다.
        if (type.getHardness() > 0.0F) {
            damageTool(player);
        }
    }

    private void damageTool(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getType().getMaxDurability() <= 0) return;
        player.getInventory().setItemInMainHand(held.damage(1, player));
    }

    // ── 표시 ─────────────────────────────────────────────────────

    private void render(Player player, MiningSession session) {
        if (session.stageChanged()) {
            sendCrack(player, session.block(), session.progress());
        }
        if (config.actionBar()) {
            player.sendActionBar(actionBar(session));
        }
    }

    private Component actionBar(MiningSession session) {
        HardnessTier tier = hardnessTable.tierOf(session.multiplier());
        int length = config.actionBarLength();
        int filled = Math.min(length, Math.round(session.progress() * length));

        Component bar = Component.text("▬".repeat(filled), NamedTextColor.GREEN)
                .append(Component.text("▬".repeat(length - filled), NamedTextColor.DARK_GRAY));

        double seconds = (session.requiredTicks() - session.elapsedTicks()) / 20.0D;

        Component label = session.exempt()
                ? Component.text("예외 블럭", NamedTextColor.DARK_GRAY)
                : Component.text(tier.label(), tier.color())
                        .append(Component.text(" ×" + Text.round(session.multiplier(), 2), NamedTextColor.GRAY));

        Component line = Component.text(Text.materialName(session.material()), NamedTextColor.AQUA)
                .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                .append(label)
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(bar)
                .append(Component.text(" " + Text.round(seconds, 1) + "s", NamedTextColor.WHITE));

        if (config.deathEnabled() && !session.exempt()) {
            line = line.append(Component.text("  ☠ 놓으면 누군가 죽는다", NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));
        }
        return line;
    }

    private void sendCrack(Player miner, Block block, float progress) {
        Location location = block.getLocation();
        int sourceId = crackSourceId(miner);
        miner.sendBlockDamage(location, progress, sourceId);
        if (!config.shareCrack()) return;

        double radiusSq = config.crackShareRadius() * config.crackShareRadius();
        for (Player viewer : block.getWorld().getPlayers()) {
            if (viewer.equals(miner)) continue;
            if (viewer.getLocation().distanceSquared(location) > radiusSq) continue;
            viewer.sendBlockDamage(location, progress, sourceId);
            // 서버가 자기 진행도(NaN → 0단계)로 그려둔 균열을 지운다.
            // 채굴자 본인 화면에서는 클라이언트가 매 틱 알아서 지우므로 필요 없다.
            viewer.sendBlockDamage(location, 0.0F, miner.getEntityId());
        }
    }

    private void clearCrack(Player miner, MiningSession session) {
        sendCrack(miner, session.block(), 0.0F);
    }

    /**
     * 균열 애니메이션에 쓸 송신자 ID.
     *
     * <p>채굴자의 엔티티 ID 를 그대로 쓰면 안 된다. 클라이언트가 매 틱 자기 ID 로
     * "균열 없음"을 덮어써서 우리가 그린 균열이 지워지기 때문이다. 엔티티 ID 는 항상
     * 양수이므로 음수 영역을 쓰면 절대 충돌하지 않는다.</p>
     */
    private static int crackSourceId(Player miner) {
        return -miner.getEntityId() - 1;
    }

    private void grantAbortImmunity(Player player, Block block) {
        immunities.put(player.getUniqueId(),
                new AbortImmunity(block.getLocation(), currentTick + config.postBreakImmunityTicks()));
    }

    // ── 잠금 적용 대상 관리 ───────────────────────────────────────

    /** 플레이어 상태에 맞춰 바닐라 채굴 잠금을 켜거나 끈다. */
    public void refreshLock(Player player) {
        if (applies(player)) {
            lock.apply(player);
        } else {
            lock.remove(player);
            cancelSessionSilently(player);
        }
    }

    public void refreshAllLocks() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshLock(player);
        }
    }

    public void removeAllLocks() {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player player : players) {
            lock.remove(player);
        }
    }
}

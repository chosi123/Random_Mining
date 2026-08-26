package org.chosi123.random_mining.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.chosi123.random_mining.mining.MiningManager;
import org.chosi123.random_mining.punish.PunishmentService;

/**
 * 채굴 잠금(어트리뷰트)을 플레이어 상태에 맞춰 계속 유지시켜 준다.
 *
 * <p>transient 어트리뷰트 모디파이어는 리스폰 때 사라지므로 반드시 다시 걸어줘야 한다.
 * 게임모드 변경은 이벤트 시점에 아직 반영되지 않아서 한 틱 뒤에 처리한다.</p>
 */
public final class PlayerStateListener implements Listener {

    private final Plugin plugin;
    private final MiningManager manager;
    private final PunishmentService punishment;

    public PlayerStateListener(Plugin plugin, MiningManager manager, PunishmentService punishment) {
        this.plugin = plugin;
        this.manager = manager;
        this.punishment = punishment;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        manager.refreshLock(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.cancelSessionSilently(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        nextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.cancelSessionSilently(event.getPlayer());
        nextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        nextTick(event.getPlayer());
    }

    /** 채굴 포기로 인한 사망이면 그 사유를 사망 메시지에 반영한다. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        manager.cancelSessionSilently(player);

        if (punishment.takeSilenced(player)) {
            // 전원 사망처럼 한 줄로 묶어 알리는 경우 — 개별 메시지는 지운다.
            event.deathMessage(null);
            return;
        }
        Component message = punishment.takeDeathMessage(player);
        if (message != null) {
            event.deathMessage(message);
        }
    }

    private void nextTick(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) manager.refreshLock(player);
        });
    }
}

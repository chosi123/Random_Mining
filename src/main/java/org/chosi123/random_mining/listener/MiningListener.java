package org.chosi123.random_mining.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.chosi123.random_mining.mining.MiningManager;
import org.chosi123.random_mining.mining.SpawnProtection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 채굴 시작 / 취소 / 완료 신호를 {@link MiningManager} 로 넘겨주는 얇은 층. */
public final class MiningListener implements Listener {

    private static final long WARN_COOLDOWN_MILLIS = 2000L;

    private final MiningManager manager;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    public MiningListener(MiningManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        if (!manager.applies(event.getPlayer())) return;
        // block_break_speed 가 0 이라 여기서 instaBreak 이 true 가 되는 일은 없지만,
        // 잠금이 어떤 이유로든 풀렸을 때 바닐라가 블럭을 날려버리지 않도록 못을 박아 둔다.
        event.setInstaBreak(false);
        manager.beginMining(event.getPlayer(), event.getBlock(), event.getItemInHand());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbort(BlockDamageAbortEvent event) {
        if (!manager.applies(event.getPlayer())) return;
        manager.abortMining(event.getPlayer(), event.getBlock());
    }

    /**
     * 스폰 보호 구역에서는 서버가 {@link BlockDamageEvent} 자체를 보내주지 않는다.
     * 우리 입장에서는 아무 신호도 없이 채굴이 먹통이 되는 셈이라, 이유라도 알려준다.
     *
     * @see SpawnProtection
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!manager.applies(player)) return;
        if (event.getClickedBlock() == null) return;
        if (!SpawnProtection.blocks(player, event.getClickedBlock())) return;

        long now = System.currentTimeMillis();
        Long previous = lastWarn.get(player.getUniqueId());
        if (previous != null && now - previous < WARN_COOLDOWN_MILLIS) return;
        if (lastWarn.size() > 256) {
            lastWarn.entrySet().removeIf(e -> now - e.getValue() > WARN_COOLDOWN_MILLIS);
        }
        lastWarn.put(player.getUniqueId(), now);

        player.sendActionBar(Component.text(
                "스폰 보호 구역이라 캘 수 없습니다 (spawn-protection="
                        + player.getServer().getSpawnRadius() + ")", NamedTextColor.RED));
    }

    /**
     * 안전망. 커스텀 채굴이 적용되는 상황에서 바닐라 경로로 블럭이 부서지는 일은 없어야 한다.
     * 잠금이 실패했더라도 여기서 막히므로 "경도 무시하고 캐기"는 불가능하다.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isBreakingInternally()) return;
        if (!manager.applies(event.getPlayer())) return;
        event.setCancelled(true);
    }
}

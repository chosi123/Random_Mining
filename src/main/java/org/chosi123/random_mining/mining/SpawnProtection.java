package org.chosi123.random_mining.mining;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 바닐라 스폰 보호(server.properties 의 {@code spawn-protection}) 판정.
 *
 * <p>이 플러그인과 스폰 보호는 아주 나쁘게 맞물린다. 보호 구역 안에서 OP 가 아닌 플레이어가
 * 좌클릭하면 서버는 {@code BlockDamageEvent} 를 <b>발생시키지도 않고</b> 곧장 돌아가버린다
 * ({@code ServerPlayerGameMode#handleBlockBreakAction} 의 {@code mayInteract} 분기).
 * 그러면 우리는 채굴이 시작된 줄도 모르고, 한편 {@link BreakSpeedLock} 때문에 바닐라 채굴도
 * 막혀 있다. 결과는 "아무 일도 안 일어나는" 완전한 채굴 불가 상태다.</p>
 *
 * <p>고칠 수는 없다 — 뚫어주면 스폰 보호를 무력화하는 셈이기 때문이다. 대신 왜 안 되는지
 * 알려주기라도 해서 조용한 먹통이 되지 않게 한다.</p>
 *
 * <p>판정식은 {@code DedicatedServer#isUnderSpawnProtection} 을 그대로 옮긴 것이다.</p>
 */
public final class SpawnProtection {

    private SpawnProtection() {}

    /** 이 플레이어가 이 블럭을 스폰 보호 때문에 건드릴 수 없는지. */
    public static boolean blocks(Player player, Block block) {
        Server server = player.getServer();

        if (server.getSpawnRadius() <= 0) return false;
        if (player.isOp()) return false;
        // OP 가 한 명도 없는 서버에서는 스폰 보호가 아예 동작하지 않는다.
        if (server.getOperators().isEmpty()) return false;

        // 스폰 차원(= 첫 번째 월드)에서만 적용된다.
        List<World> worlds = server.getWorlds();
        if (worlds.isEmpty() || !worlds.get(0).equals(block.getWorld())) return false;

        Location spawn = block.getWorld().getSpawnLocation();
        int dx = Math.abs(block.getX() - spawn.getBlockX());
        int dz = Math.abs(block.getZ() - spawn.getBlockZ());
        return Math.max(dx, dz) <= server.getSpawnRadius();
    }
}

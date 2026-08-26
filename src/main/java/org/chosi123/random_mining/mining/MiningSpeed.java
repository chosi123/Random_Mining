package org.chosi123.random_mining.mining;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 바닐라 채굴 시간을 그대로 재현하는 계산기.
 *
 * <p>{@code Player#getDestroySpeed} / {@code BlockStateBase#getDestroyProgress} 와 같은 식을 쓴다.
 * 우리가 걸어둔 채굴 잠금(block_break_speed = 0)은 {@link BreakSpeedLock#unlockedValue}로 걷어내고
 * 계산하므로, 다른 플러그인이 준 채굴 속도 버프는 정상적으로 반영된다.</p>
 */
public final class MiningSpeed {

    /** 파괴 불가능한 블럭(기반암, 배리어 등)을 나타내는 값. */
    public static final int UNBREAKABLE = -1;

    private final BreakSpeedLock lock;

    public MiningSpeed(BreakSpeedLock lock) {
        this.lock = lock;
    }

    /** 도구/포션/자세를 모두 반영한 채굴 속도 배수. */
    @SuppressWarnings("deprecation") // isOnGround() 는 클라이언트 보고값이지만, 바닐라 채굴도 같은 값을 쓴다.
    public double destroySpeed(Player player, Block block, ItemStack tool) {
        double speed = block.getDestroySpeed(tool, true);

        PotionEffect haste = player.getPotionEffect(PotionEffectType.HASTE);
        if (haste != null) {
            speed *= 1.0D + (haste.getAmplifier() + 1) * 0.2D;
        }
        PotionEffect fatigue = player.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        if (fatigue != null) {
            speed *= switch (fatigue.getAmplifier()) {
                case 0 -> 0.3D;
                case 1 -> 0.09D;
                case 2 -> 0.0027D;
                default -> 8.1E-4D;
            };
        }

        speed *= lock.unlockedValue(player, Attribute.BLOCK_BREAK_SPEED, 1.0D);

        if (eyesInWater(player)) {
            // 아쿠아 어피니티는 1.21 부터 이 어트리뷰트 모디파이어로 구현되어 있다.
            speed *= lock.unlockedValue(player, Attribute.SUBMERGED_MINING_SPEED, 0.2D);
        }
        if (!player.isOnGround()) {
            speed /= 5.0D;
        }
        return speed;
    }

    /**
     * 바닐라라면 이 블럭을 몇 틱에 캘 수 있는지.
     *
     * @return 파괴 불가면 {@link #UNBREAKABLE}, 즉시 파괴면 0, 그 외 1 이상
     */
    public int vanillaBreakTicks(Player player, Block block, ItemStack tool) {
        Material type = block.getType();
        if (!type.isBlock()) return UNBREAKABLE;

        float hardness = type.getHardness();
        if (hardness < 0.0F) return UNBREAKABLE;
        if (hardness == 0.0F) return 0;

        double speed = destroySpeed(player, block, tool);
        if (speed <= 0.0D || Double.isNaN(speed)) return UNBREAKABLE;

        int divisor = block.isPreferredTool(tool) ? 30 : 100;
        double progressPerTick = speed / hardness / divisor;
        if (progressPerTick <= 0.0D) return UNBREAKABLE;

        double ticks = Math.ceil(1.0D / progressPerTick);
        if (ticks >= Integer.MAX_VALUE) return UNBREAKABLE;
        return (int) Math.max(1L, (long) ticks);
    }

    private static boolean eyesInWater(Player player) {
        Block eye = player.getEyeLocation().getBlock();
        if (eye.getType() == Material.WATER) return true;
        BlockData data = eye.getBlockData();
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }
}

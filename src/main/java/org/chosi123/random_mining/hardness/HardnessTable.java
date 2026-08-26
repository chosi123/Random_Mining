package org.chosi123.random_mining.hardness;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.chosi123.random_mining.config.RandomMiningConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 블럭별 경도 배율을 들고 있는 테이블.
 *
 * <p>PER_MATERIAL 모드는 {@link Material} 마다 배율을 하나씩 뽑아 캐싱하고,
 * PER_BLOCK 모드는 (시드, 월드, 좌표)를 splitmix64 로 섞어 좌표마다 결정론적으로 계산한다.
 * 후자는 맵을 들고 있지 않으므로 좌표 수와 무관하게 메모리를 쓰지 않는다.</p>
 *
 * <p>배율은 로그 균등 분포로 뽑는다. 균등 분포로 뽑으면 max 가 클수록 "단단한 블럭"만
 * 잔뜩 나오기 때문에, 0.2배와 5배가 비슷한 빈도로 나오도록 지수 축에서 균등하게 뽑는다.</p>
 */
public final class HardnessTable {

    private RandomMiningConfig config;
    private final Random random = new Random();

    private final Map<Material, Double> multipliers = new EnumMap<>(Material.class);
    private long seed;

    public HardnessTable(RandomMiningConfig config) {
        this.config = config;
    }

    public void applyConfig(RandomMiningConfig config) {
        this.config = config;
    }

    /** 경도 테이블을 새로 뽑는다. */
    public void reroll() {
        seed = random.nextLong();
        multipliers.clear();
        if (config.mode() == RandomMiningConfig.HardnessMode.PER_MATERIAL) {
            for (Material material : Material.values()) {
                if (material.isLegacy() || !material.isBlock()) continue;
                multipliers.put(material, rollMultiplier(random.nextDouble()));
            }
        }
    }

    /** 해당 블럭의 현재 경도 배율. 테이블이 아직 준비되지 않았으면 1.0. */
    public double multiplierOf(Block block) {
        if (config.mode() == RandomMiningConfig.HardnessMode.PER_BLOCK) {
            UUID worldId = block.getWorld().getUID();
            long h = seed;
            h = mix(h ^ worldId.getMostSignificantBits());
            h = mix(h ^ worldId.getLeastSignificantBits());
            h = mix(h ^ (block.getX() * 0x9E3779B97F4A7C15L));
            h = mix(h ^ (block.getY() * 0xC2B2AE3D27D4EB4FL));
            h = mix(h ^ (block.getZ() * 0x165667B19E3779F9L));
            return rollMultiplier(unitInterval(h));
        }
        return multiplierOf(block.getType());
    }

    /** 블럭 종류의 현재 경도 배율. PER_BLOCK 모드에서는 참고값(1.0)만 돌려준다. */
    public double multiplierOf(Material material) {
        Double value = multipliers.get(material);
        return value == null ? 1.0D : value;
    }

    /** 0~1 난수를 설정된 범위의 로그 균등 배율로 변환. */
    private double rollMultiplier(double unit) {
        double lnMin = Math.log(config.minMultiplier());
        double lnMax = Math.log(config.maxMultiplier());
        return Math.exp(lnMin + (lnMax - lnMin) * unit);
    }

    /** 배율이 설정 범위 안에서 차지하는 상대 위치(0=가장 무름, 1=가장 단단함). */
    public double normalized(double multiplier) {
        double lnMin = Math.log(config.minMultiplier());
        double lnMax = Math.log(config.maxMultiplier());
        if (lnMax - lnMin <= 1.0E-9D) return 0.5D;
        double t = (Math.log(multiplier) - lnMin) / (lnMax - lnMin);
        return Math.max(0.0D, Math.min(1.0D, t));
    }

    public HardnessTier tierOf(double multiplier) {
        return HardnessTier.of(normalized(multiplier));
    }

    /** PER_MATERIAL 모드에서 배율 순으로 정렬한 목록. hardest=true 면 단단한 순. */
    public List<Map.Entry<Material, Double>> sorted(boolean hardest, int limit) {
        List<Map.Entry<Material, Double>> entries = new ArrayList<>(multipliers.entrySet());
        Comparator<Map.Entry<Material, Double>> byValue = Map.Entry.comparingByValue();
        entries.sort(hardest ? byValue.reversed() : byValue);
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 64비트 해시를 [0,1) 로. 상위 53비트만 써서 double 정밀도에 맞춘다. */
    private static double unitInterval(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }
}

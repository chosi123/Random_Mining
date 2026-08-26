package org.chosi123.random_mining.mining;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 바닐라 채굴을 완전히 정지시키는 장치.
 *
 * <p>{@code generic.block_break_speed} 를 0 으로 만들면 서버뿐 아니라 <b>클라이언트</b>도
 * 채굴 진행도를 쌓지 못한다. 클라이언트가 스스로 파괴를 끝내지 않으므로 블럭이 잠깐
 * 사라졌다 되돌아오는 현상 없이, 마우스를 뗄 때 반드시 취소 패킷만 날아온다.
 * 이 플러그인의 즉사 판정이 성립하는 전제 조건이다.</p>
 *
 * <p>채굴 진행도는 {@link MiningManager} 가 직접 계산해 그린다.</p>
 */
public final class BreakSpeedLock {

    private final NamespacedKey key;

    public BreakSpeedLock(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "vanilla_mining_lock");
    }

    public NamespacedKey key() {
        return key;
    }

    public void apply(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (instance == null) return;
        if (findOurs(instance) != null) return;
        // ADD_SCALAR -1.0 → (base + add) * (1 - 1) = 0. transient 라 플레이어 데이터에 저장되지 않아
        // 플러그인이 죽은 채로 서버가 꺼져도 어트리뷰트가 남지 않는다.
        instance.addTransientModifier(
                new AttributeModifier(key, -1.0D, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
    }

    public void remove(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (instance == null) return;
        for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            if (key.equals(modifier.getKey())) {
                instance.removeModifier(modifier);
            }
        }
    }

    /**
     * 우리가 건 잠금 modifier 를 뺀 상태의 어트리뷰트 값.
     * 채굴 시간을 계산할 때는 "잠그기 전"의 값이 필요하다.
     */
    public double unlockedValue(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return fallback;

        double add = 0.0D;
        double scalar = 0.0D;
        List<Double> multipliers = new ArrayList<>();
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (key.equals(modifier.getKey())) continue;
            switch (modifier.getOperation()) {
                case ADD_NUMBER -> add += modifier.getAmount();
                case ADD_SCALAR -> scalar += modifier.getAmount();
                case MULTIPLY_SCALAR_1 -> multipliers.add(modifier.getAmount());
            }
        }
        double value = (instance.getBaseValue() + add) * (1.0D + scalar);
        for (double m : multipliers) {
            value *= 1.0D + m;
        }
        return value;
    }

    private AttributeModifier findOurs(AttributeInstance instance) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (key.equals(modifier.getKey())) return modifier;
        }
        return null;
    }
}

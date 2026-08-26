package org.chosi123.random_mining.mining;

import org.bukkit.Material;
import org.bukkit.block.Block;

/** 플레이어 한 명이 블럭 하나를 캐고 있는 동안의 상태. */
public final class MiningSession {

    private final Block block;
    private final Material material;
    private final int requiredTicks;
    private final double multiplier;
    private final boolean exempt;

    private int elapsedTicks;
    private int lastSentStage = -1;

    MiningSession(Block block, int requiredTicks, double multiplier, boolean exempt) {
        this.block = block;
        this.material = block.getType();
        this.requiredTicks = Math.max(1, requiredTicks);
        this.multiplier = multiplier;
        this.exempt = exempt;
    }

    public Block block() { return block; }
    public Material material() { return material; }
    public int requiredTicks() { return requiredTicks; }
    public double multiplier() { return multiplier; }
    /** exempt-materials 에 든 블럭 — 바닐라 속도로 캐고 즉사도 없다. */
    public boolean exempt() { return exempt; }
    public int elapsedTicks() { return elapsedTicks; }

    void tick() { elapsedTicks++; }

    public float progress() {
        return Math.min(1.0F, (float) elapsedTicks / requiredTicks);
    }

    public boolean isComplete() {
        return elapsedTicks >= requiredTicks;
    }

    /** 0~9 의 바닐라 균열 단계. 진행도가 0 이면 -1(균열 없음).
     *  Paper 의 sendBlockDamage 와 같은 식을 써야 쓸데없는 패킷이 나가지 않는다. */
    public int crackStage() {
        float p = progress();
        if (p <= 0.0F) return -1;
        return Math.min(9, (int) (9.0F * p));
    }

    boolean stageChanged() {
        int stage = crackStage();
        if (stage == lastSentStage) return false;
        lastSentStage = stage;
        return true;
    }

    /** 같은 블럭을 가리키고 있으며 도중에 바뀌지 않았는지. */
    public boolean stillValid() {
        return block.getType() == material;
    }
}

package org.chosi123.random_mining.hardness;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** 경도 배율을 사람이 읽을 수 있는 5단계로 나눈 것. */
public enum HardnessTier {

    VERY_SOFT("\ubb3c\ub801\ubb3c\ub801", NamedTextColor.GREEN),
    SOFT("\ubb34\ub984", NamedTextColor.YELLOW),
    NORMAL("\ubcf4\ud1b5", NamedTextColor.WHITE),
    HARD("\ub2e8\ub2e8\ud568", NamedTextColor.GOLD),
    VERY_HARD("\ubc14\uc704\ubcf4\ub2e4 \ub2e8\ub2e8\ud568", NamedTextColor.RED);

    private final String label;
    private final TextColor color;

    HardnessTier(String label, TextColor color) {
        this.label = label;
        this.color = color;
    }

    public String label() { return label; }
    public TextColor color() { return color; }

    public static HardnessTier of(double normalized) {
        if (normalized < 0.20D) return VERY_SOFT;
        if (normalized < 0.40D) return SOFT;
        if (normalized < 0.60D) return NORMAL;
        if (normalized < 0.80D) return HARD;
        return VERY_HARD;
    }
}

package org.chosi123.random_mining.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** 메시지 조립용 자잘한 도구들. */
public final class Text {

    public static final Component PREFIX = Component.text("[랜덤채굴] ", NamedTextColor.DARK_AQUA);

    private Text() {}

    /** DEEPSLATE_DIAMOND_ORE → Deepslate Diamond Ore */
    public static String materialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(material.name().length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return sb.toString();
    }

    /** 소수점 자릿수를 잘라 사람이 읽기 좋은 문자열로. */
    public static String round(double value, int decimals) {
        return BigDecimal.valueOf(value)
                .setScale(decimals, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    public static Component info(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GRAY));
    }

    public static Component success(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GREEN));
    }

    public static Component error(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.RED));
    }
}

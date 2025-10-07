package com.bodia.shoptrader.model;

import org.bukkit.ChatColor;

public enum Tier {
    COMMON(ChatColor.WHITE, "Common"),
    UNCOMMON(ChatColor.GREEN, "Uncommon"),
    EPIC(ChatColor.DARK_PURPLE, "Epic"),
    LEGENDARY(ChatColor.GOLD, "Legendary");

    private final ChatColor color;
    private final String display;

    Tier(ChatColor color, String display) {
        this.color = color;
        this.display = display;
    }

    public ChatColor color() { return color; }
    public String display() { return display; }
}

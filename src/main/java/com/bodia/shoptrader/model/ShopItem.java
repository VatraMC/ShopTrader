package com.bodia.shoptrader.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class ShopItem {
    private final Material material;
    private final Tier tier;
    private final Category category;
    private final double price;

    public ShopItem(Material material, Tier tier, Category category, double price) {
        this.material = material;
        this.tier = tier;
        this.category = category;
        this.price = price;
    }

    public Material getMaterial() { return material; }
    public Tier getTier() { return tier; }
    public Category getCategory() { return category; }
    public double getPrice() { return price; }

    public ItemStack toItemStack() {
        return new ItemStack(material);
    }
}

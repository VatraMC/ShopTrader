package com.bodia.shoptrader.shop;

import com.bodia.shoptrader.model.ShopItem;
import com.bodia.shoptrader.model.Tier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class DropManager {

    private final Plugin plugin;
    private final Catalog catalog;
    private final Random rng = new Random();

    private List<ShopItem> currentDrop = new ArrayList<>();
    private int taskId = -1;
    private long nextRollAtMillis = 0L;
    private int rotationMinutes;
    private int cycleSeconds;

    public DropManager(Plugin plugin, Catalog catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
    }

    public void start() {
        this.rotationMinutes = plugin.getConfig().getInt("rotation.minutes", 30);
        this.cycleSeconds = Math.max(1, plugin.getConfig().getInt("rotation.cycle_seconds", 30));
        rollDrop();
        long period = rotationMinutes * 60L * 20L; // ticks
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::rollDrop, period, period);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public void rollDrop() {
        Map<Tier, Integer> counts = new EnumMap<>(Tier.class);
        counts.put(Tier.COMMON, plugin.getConfig().getInt("rotation.counts.common", 4));
        counts.put(Tier.UNCOMMON, plugin.getConfig().getInt("rotation.counts.uncommon", 3));
        counts.put(Tier.EPIC, plugin.getConfig().getInt("rotation.counts.epic", 2));
        counts.put(Tier.LEGENDARY, plugin.getConfig().getInt("rotation.counts.legendary", 1));
        this.currentDrop = catalog.rollDrop(counts, rng);
        plugin.getLogger().info("Rolled new shop drop with " + currentDrop.size() + " items.");
        // Set next roll time
        this.nextRollAtMillis = System.currentTimeMillis() + rotationMinutes * 60_000L;
    }

    public List<ShopItem> getCurrentDrop() {
        return currentDrop;
    }

    public long getSecondsRemaining() {
        long now = System.currentTimeMillis();
        long diff = nextRollAtMillis - now;
        return Math.max(0L, diff / 1000L);
    }

    public int getRotationMinutes() {
        return rotationMinutes;
    }

    public int getCycleSeconds() {
        return cycleSeconds;
    }

    public int getCycleIndex() {
        long total = rotationMinutes * 60L;
        long elapsed = total - getSecondsRemaining();
        if (cycleSeconds <= 0) return 0;
        return (int) (elapsed / cycleSeconds);
    }
}

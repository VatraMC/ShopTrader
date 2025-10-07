package com.bodia.shoptrader;

import com.bodia.shoptrader.gui.TraderGUI;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.UUID;

public class TraderManager {

    public static final String PDC_KEY = "is_shop_trader";

    private final Plugin plugin;
    private final TraderGUI gui;

    private UUID traderUuid;
    private Location traderLocation;

    private final NamespacedKey key;

    public TraderManager(Plugin plugin, TraderGUI gui) {
        this.plugin = plugin;
        this.gui = gui;
        this.key = new NamespacedKey(plugin, PDC_KEY);
    }

    public Optional<WanderingTrader> getTrader() {
        if (traderUuid != null) {
            for (World world : Bukkit.getWorlds()) {
                Entity byId = world.getEntities().stream().filter(e -> traderUuid.equals(e.getUniqueId())).findFirst().orElse(null);
                if (byId instanceof WanderingTrader) return Optional.of((WanderingTrader) byId);
            }
        }
        // Fallback search by PDC mark
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(WanderingTrader.class)) {
                if (isOurTrader(e)) {
                    return Optional.of((WanderingTrader) e);
                }
            }
        }
        return Optional.empty();
    }

    public boolean isOurTrader(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        return pdc.has(key, PersistentDataType.BYTE);
    }

    public WanderingTrader spawnTrader(Location loc) {
        // Remove existing
        getTrader().ifPresent(Entity::remove);

        World world = loc.getWorld();
        WanderingTrader trader = (WanderingTrader) world.spawnEntity(loc, EntityType.WANDERING_TRADER);
        markTrader(trader);

        trader.setCustomName(ChatColor.GOLD + "Shop Trader");
        trader.setCustomNameVisible(true);

        // WanderingTrader is a LivingEntity; avoid instanceof pattern to stay Java 17 compatible
        trader.setAI(false);
        trader.setInvulnerable(true);
        trader.setCollidable(false);
        trader.setRemoveWhenFarAway(false);
        trader.setCanPickupItems(false);
        // Ensure no invisibility effect remains
        trader.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);

        this.traderUuid = trader.getUniqueId();
        this.traderLocation = trader.getLocation().clone();
        saveToConfig();
        plugin.saveConfig();
        return trader;
    }

    private void markTrader(Entity e) {
        e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        e.addScoreboardTag("ShopTrader");
    }

    public boolean rotateTrader(float degrees) {
        Optional<WanderingTrader> opt = getTrader();
        if (opt.isEmpty()) return false;
        WanderingTrader trader = opt.get();

        Location loc = trader.getLocation();
        float newYaw = loc.getYaw() + degrees;
        loc.setYaw(newYaw);
        trader.teleport(loc);
        this.traderLocation = trader.getLocation().clone();
        return true;
    }

    public void openShopTo(org.bukkit.entity.Player player) {
        gui.open(player, TraderGUI.Tab.SHOP);
    }

    public void loadFromConfig() {
        String path = "trader";
        String worldName = plugin.getConfig().getString(path + ".world");
        String uuidStr = plugin.getConfig().getString(path + ".uuid");
        double x = plugin.getConfig().getDouble(path + ".x", Double.NaN);
        double y = plugin.getConfig().getDouble(path + ".y", Double.NaN);
        double z = plugin.getConfig().getDouble(path + ".z", Double.NaN);
        float yaw = (float) plugin.getConfig().getDouble(path + ".yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", 0.0);

        if (uuidStr != null) {
            try {
                this.traderUuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ignored) {}
        }

        if (worldName != null && !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                this.traderLocation = new Location(world, x, y, z, yaw, pitch);
            }
        }

        // If no trader entity exists but we have a location, respawn it
        if (getTrader().isEmpty() && traderLocation != null) {
            spawnTrader(traderLocation);
        }
    }

    public void saveToConfig() {
        String path = "trader";
        if (traderLocation != null) {
            plugin.getConfig().set(path + ".world", traderLocation.getWorld().getName());
            plugin.getConfig().set(path + ".x", traderLocation.getX());
            plugin.getConfig().set(path + ".y", traderLocation.getY());
            plugin.getConfig().set(path + ".z", traderLocation.getZ());
            plugin.getConfig().set(path + ".yaw", traderLocation.getYaw());
            plugin.getConfig().set(path + ".pitch", traderLocation.getPitch());
        }
        if (traderUuid != null) {
            plugin.getConfig().set(path + ".uuid", traderUuid.toString());
        }
    }

    public boolean removeTrader() {
        Optional<WanderingTrader> opt = getTrader();
        opt.ifPresent(Entity::remove);
        boolean existed = opt.isPresent();
        this.traderUuid = null;
        this.traderLocation = null;
        plugin.getConfig().set("trader", null);
        plugin.saveConfig();
        return existed;
    }
}

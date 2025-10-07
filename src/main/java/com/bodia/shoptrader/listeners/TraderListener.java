package com.bodia.shoptrader.listeners;

import com.bodia.shoptrader.TraderManager;
import com.bodia.shoptrader.gui.TraderGUI;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;

public class TraderListener implements Listener {

    private final TraderManager manager;
    private final TraderGUI gui;

    public TraderListener(TraderManager manager, TraderGUI gui) {
        this.manager = manager;
        this.gui = gui;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (clicked instanceof WanderingTrader && manager.isOurTrader(clicked)) {
            e.setCancelled(true);
            Player p = e.getPlayer();
            gui.open(p, TraderGUI.Tab.SHOP);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (gui.isOurInventory(e.getView().getTopInventory())) {
            gui.handleClick(e);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (gui.isOurInventory(e.getView().getTopInventory())) {
            gui.handleDrag(e);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (gui.isOurInventory(e.getView().getTopInventory())) {
            gui.onClose(e);
        }
    }

    @EventHandler
    public void onPotionEffect(EntityPotionEffectEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof WanderingTrader && manager.isOurTrader(entity)) {
            if (e.getNewEffect() != null && e.getNewEffect().getType().equals(PotionEffectType.INVISIBILITY)) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onTraderDamage(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof WanderingTrader && manager.isOurTrader(entity)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onTraderDamagedBy(EntityDamageByEntityEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof WanderingTrader && manager.isOurTrader(entity)) {
            e.setCancelled(true);
        }
    }
}

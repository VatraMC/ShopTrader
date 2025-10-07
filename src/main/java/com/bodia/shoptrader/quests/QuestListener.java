package com.bodia.shoptrader.quests;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import com.bodia.shoptrader.gui.TraderGUI;
import org.bukkit.event.entity.EntityPickupItemEvent;
import com.bodia.shoptrader.quests.QuestDef;

public class QuestListener implements Listener {
    private final QuestManager manager;
    private final TraderGUI gui;

    public QuestListener(QuestManager manager, TraderGUI gui) {
        this.manager = manager;
        this.gui = gui;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity().getKiller() == null) return;
        Player p = e.getEntity().getKiller();
        EntityType type = e.getEntityType();
        // Generalized KILL quests: progress for any active quest matching the killed entity type
        for (QuestDef def : manager.getAll()) {
            if (def.getKind() == QuestDef.Kind.KILL && def.getTargetEntity() == type) {
                manager.addProgress(p, def.getId(), 1);
            }
        }
        gui.refreshQuestsFor(p);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlock().getType();
        // Mining quests removed; no progress added here.
        gui.refreshQuestsFor(p);
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        // Count successful fish catches
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            for (QuestDef def : manager.getAll()) {
                if (def.getKind() == QuestDef.Kind.FISH) {
                    manager.addProgress(p, def.getId(), 1);
                }
            }
        }
        gui.refreshQuestsFor(p);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        // Refresh quests view to reflect inventory-based progress for FETCH quests
        if (gui.isViewingQuests(p)) gui.refreshQuestsFor(p);
    }
}

package com.bodia.shoptrader;

import com.bodia.shoptrader.commands.TraderCommand;
import com.bodia.shoptrader.economy.EconomyService;
import com.bodia.shoptrader.gui.TraderGUI;
import com.bodia.shoptrader.listeners.TraderListener;
import com.bodia.shoptrader.shop.Catalog;
import com.bodia.shoptrader.shop.DropManager;
import com.bodia.shoptrader.quests.QuestListener;
import com.bodia.shoptrader.quests.QuestManager;
import com.bodia.shoptrader.sell.SellRotationManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class ShopTraderPlugin extends JavaPlugin {

    private static ShopTraderPlugin instance;

    private TraderManager traderManager;
    private TraderGUI traderGUI;
    private EconomyService economyService;
    private Catalog catalog;
    private DropManager dropManager;
    private QuestManager questManager;
    private SellRotationManager sellManager;

    public static ShopTraderPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Hook economy
        this.economyService = new EconomyService(this);
        this.economyService.setup();

        // Quests
        this.questManager = new QuestManager(this, economyService);

        // Catalog and rotation
        this.catalog = new Catalog(this);
        this.dropManager = new DropManager(this, catalog);
        this.sellManager = new SellRotationManager(this, catalog);

        // GUI and Trader
        this.traderGUI = new TraderGUI(this, economyService, catalog, dropManager, questManager, sellManager);
        this.traderManager = new TraderManager(this, traderGUI);

        // Load persisted trader if any
        this.traderManager.loadFromConfig();

        // Start rotation tasks
        this.dropManager.start();

        // Periodically tick GUI to refresh timers and cycle content
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (this.sellManager != null) this.sellManager.tick();
            if (this.traderGUI != null) this.traderGUI.tick();
        }, 20L, 20L);

        // Register commands
        TraderCommand traderCommand = new TraderCommand(traderManager, traderGUI, this);
        getCommand("trader").setExecutor(traderCommand);
        getCommand("trader").setTabCompleter(traderCommand);

        // Register listeners
        Bukkit.getPluginManager().registerEvents(new TraderListener(traderManager, traderGUI), this);
        Bukkit.getPluginManager().registerEvents(new QuestListener(questManager, traderGUI), this);

        getLogger().info("ShopTrader увімкнено.");
    }

    @Override
    public void onDisable() {
        // Stop rotation
        if (this.dropManager != null) this.dropManager.stop();
        // Persist trader state
        this.traderManager.saveToConfig();
        // Save quests data
        if (this.questManager != null) this.questManager.saveData();
        saveConfig();
        getLogger().info("ShopTrader вимкнено.");
    }

    public void reloadAll() {
        reloadConfig();
        if (this.catalog != null) this.catalog.reload();
        if (this.sellManager != null) this.sellManager.reloadConfig();
        if (this.dropManager != null) {
            this.dropManager.stop();
            this.dropManager.start();
        }
        if (this.traderGUI != null) this.traderGUI.refreshSellAll();
        // Notify all online players about shop reload
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(org.bukkit.ChatColor.AQUA + "ShopTrader перезавантажено. Ротації та ціни могли змінитися."));
        getLogger().info("Конфігурацію ShopTrader перезавантажено.");
    }

    public QuestManager getQuestManager() {
        return questManager;
    }

    public SellRotationManager getSellManager() {
        return sellManager;
    }

}

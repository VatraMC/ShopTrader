package com.bodia.shoptrader.commands;

import com.bodia.shoptrader.TraderManager;
import com.bodia.shoptrader.gui.TraderGUI;
import com.bodia.shoptrader.ShopTraderPlugin;
import com.bodia.shoptrader.quests.QuestDef;
import com.bodia.shoptrader.quests.QuestManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TraderCommand implements CommandExecutor, TabCompleter {

    private final TraderManager manager;
    private final TraderGUI gui;
    private final ShopTraderPlugin plugin;

    public TraderCommand(TraderManager manager, TraderGUI gui, ShopTraderPlugin plugin) {
        this.manager = manager;
        this.gui = gui;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                gui.open(p, TraderGUI.Tab.SHOP);
                return true;
            }
            sender.sendMessage("Використання: /" + label + " <open|spawn|rotate|remove|reload|quests|deliver|claim|claimall|qregen|sellregen> [аргументи]");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Лише гравці можуть відкривати магазин.");
                    return true;
                }
                gui.open(p, TraderGUI.Tab.SHOP);
                return true;
            }
            case "spawn": {
                if (!sender.hasPermission("shoptrader.admin")) {
                    sender.sendMessage("Вам бракує дозволу: shoptrader.admin");
                    return true;
                }
                Location loc = null;
                if (args.length >= 5) {
                    // /trader spawn <world> <x> <y> <z>
                    World w = Bukkit.getWorld(args[1]);
                    if (w == null) {
                        sender.sendMessage("Невідомий світ: " + args[1]);
                        return true;
                    }
                    try {
                        double x = Double.parseDouble(args[2]);
                        double y = Double.parseDouble(args[3]);
                        double z = Double.parseDouble(args[4]);
                        loc = new Location(w, x, y, z);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("Координати мають бути числами.");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    loc = p.getLocation();
                }
                if (loc == null) {
                    sender.sendMessage("Використання: /" + label + " spawn [світ x y z] (або виконайте як гравець, щоб використати свою локацію)");
                    return true;
                }
                manager.spawnTrader(loc);
                sender.sendMessage("Торговця створено на координатах " + String.format("%.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ()));
                return true;
            }
            case "rotate": {
                if (!sender.hasPermission("shoptrader.admin")) {
                    sender.sendMessage("Вам бракує дозволу: shoptrader.admin");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Використання: /" + label + " rotate <градуси>");
                    return true;
                }
                try {
                    float deg = Float.parseFloat(args[1]);
                    boolean ok = manager.rotateTrader(deg);
                    if (ok) sender.sendMessage("Повернуто торговця на " + deg + " градусів.");
                    else sender.sendMessage("Немає торговця для повороту. Спочатку використайте /" + label + " spawn.");
                } catch (NumberFormatException e) {
                    sender.sendMessage("Градуси мають бути числом");
                }
                return true;
            }
            case "remove": {
                if (!sender.hasPermission("shoptrader.admin")) {
                    sender.sendMessage("Вам бракує дозволу: shoptrader.admin");
                    return true;
                }
                boolean existed = manager.removeTrader();
                sender.sendMessage(existed ? "Торговця видалено." : "Немає торговця для видалення.");
                return true;
            }
            case "reload": {
                if (!sender.hasPermission("shoptrader.admin")) {
                    sender.sendMessage("Вам бракує дозволу: shoptrader.admin");
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage("Конфігурацію ShopTrader перезавантажено.");
                return true;
            }
            case "quests": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Лише гравці можуть переглядати квести.");
                    return true;
                }
                gui.open(p, TraderGUI.Tab.QUESTS);
                return true;
            }
            case "deliver": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Лише гравці можуть здавати предмети для квестів.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Використання: /" + label + " deliver <questId>");
                    return true;
                }
                boolean ok = plugin.getQuestManager().deliverFetch(p, args[1]);
                if (!ok) {
                    sender.sendMessage("Невідомий або не-доставочний квест. Доступні приклади: fetch_logs, fetch_iron");
                } else {
                    if (gui.isViewingQuests(p)) gui.refreshQuestsFor(p);
                }
                return true;
            }
            case "claim": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Лише гравці можуть отримувати нагороди за квести.");
                    return true;
                }
                // Only allow claim from within the Trader GUI on the Quests tab
                if (!gui.isViewingQuests(p)) {
                    sender.sendMessage("Відкрийте торгівця та скористайтеся вкладкою Квести, щоб отримати нагороди.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Використання: /" + label + " claim <questId>");
                    return true;
                }
                int res = plugin.getQuestManager().claim(p, args[1]);
                if (res == -1) sender.sendMessage("Цей квест ще не готовий до отримання.");
                return true;
            }
            case "claimall": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Лише гравці можуть отримувати нагороди за квести.");
                    return true;
                }
                // Only allow claim all from within the Trader GUI on the Quests tab
                if (!gui.isViewingQuests(p)) {
                    sender.sendMessage("Відкрийте торгівця та скористайтеся вкладкою Квести, щоб отримати нагороди.");
                    return true;
                }
                plugin.getQuestManager().claimAll(p);
                return true;
            }
            case "sellregen": {
                if (!sender.hasPermission("shoptrader.admin")) {
                    sender.sendMessage("Вам бракує дозволу: shoptrader.admin");
                    return true;
                }
                var names = plugin.getSellManager().forceRegenerate();
                sender.sendMessage("Оновлено SELL-пропозиції: " + String.join(", ", names));
                gui.refreshSellAll();
                return true;
            }
            case "qregen": {
                if (!sender.hasPermission("shoptrader.admin")) {
                    sender.sendMessage("Вам бракує дозволу: shoptrader.admin");
                    return true;
                }
                var ids = plugin.getQuestManager().forceRegenerate();
                sender.sendMessage("Згенеровано нові щоденні квести: " + String.join(", ", ids));
                // Refresh any viewers currently on the Quests tab
                gui.refreshQuestsAll();
                return true;
            }
            default: {
                sender.sendMessage("Невідома підкоманда. Використання: /" + label + " <open|spawn|rotate|remove|reload|quests|deliver|claim|claimall|qregen|sellregen> [аргументи]");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("open");
            list.add("quests");
            list.add("deliver");
            list.add("claim");
            list.add("claimall");
            if (sender.hasPermission("shoptrader.admin")) {
                list.add("spawn");
                list.add("rotate");
                list.add("remove");
                list.add("reload");
                list.add("qregen");
                list.add("sellregen");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rotate") && sender.hasPermission("shoptrader.admin")) {
            list.add("90");
            list.add("180");
            list.add("270");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn") && sender.hasPermission("shoptrader.admin")) {
            for (World w : Bukkit.getWorlds()) list.add(w.getName());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("deliver") || args[0].equalsIgnoreCase("claim"))) {
            for (QuestDef def : ShopTraderPlugin.getInstance().getQuestManager().getAll()) {
                list.add(def.getId());
            }
        }
        return list;
    }
}

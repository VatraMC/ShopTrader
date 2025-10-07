package com.bodia.shoptrader.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.enchantments.Enchantment;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Base64;
import java.util.UUID;
import java.net.URL;

import com.bodia.shoptrader.economy.EconomyService;
import com.bodia.shoptrader.model.ShopItem;
import com.bodia.shoptrader.model.Category;
import com.bodia.shoptrader.model.Tier;
import com.bodia.shoptrader.shop.Catalog;
import com.bodia.shoptrader.shop.DropManager;
import com.bodia.shoptrader.quests.QuestDef;
import com.bodia.shoptrader.quests.QuestManager;
import com.bodia.shoptrader.sell.SellRotationManager;

public class TraderGUI {

    public enum Tab { SHOP, QUESTS, SELL }

    private final String TITLE_PREFIX = ChatColor.DARK_GREEN + "Торговець: ";

    private final org.bukkit.plugin.Plugin plugin;
    private final EconomyService economy;
    private final Catalog catalog;
    private final DropManager dropManager;
    private final QuestManager questManager;
    private final SellRotationManager sellManager;

    private int lastCycleIndex = -1;
    private int headRefreshCounter = 0;
    private long lastQuestSecs = -1;

    public TraderGUI(org.bukkit.plugin.Plugin plugin, EconomyService economy, Catalog catalog, DropManager dropManager, QuestManager questManager, SellRotationManager sellManager) {
        this.plugin = plugin;
        this.economy = economy;
        this.catalog = catalog;
        this.dropManager = dropManager;
        this.questManager = questManager;
        this.sellManager = sellManager;
    }

    private ItemStack questTimerItem() {
        long secs = questManager.secondsUntilNextReset();
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        String t = String.format("%02d:%02d:%02d", h, m, s);
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Щоденне оновлення через: " + ChatColor.YELLOW + t);
        meta.setLore(List.of(ChatColor.DARK_GRAY + "Часовий пояс: Europe/Kyiv"));
        it.setItemMeta(meta);
        return it;
    }

    private void refreshQuestTimers() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            if (holder.tab != Tab.QUESTS) continue;
            top.setItem(0, questTimerItem());
        }
    }

    private void refreshQuestContent() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            if (holder.tab != Tab.QUESTS) continue;
            fillQuests(top, p);
        }
    }

    private ItemStack sellTimerItem() {
        long secs = sellManager.secondsUntilRegen();
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        String t = String.format("%02d:%02d:%02d", h, m, s);
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "До оновлення продажу: " + ChatColor.YELLOW + t);
        it.setItemMeta(meta);
        return it;
    }

    private void refreshSellTimers() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            if (holder.tab != Tab.SELL) continue;
            top.setItem(0, sellTimerItem());
        }
    }

    private void refreshSellContent() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            if (holder.tab != Tab.SELL) continue;
            fillSellOffers(top);
        }
    }

    public void refreshSellAll() {
        refreshSellContent();
    }

    public void refreshQuestsAll() {
        refreshQuestContent();
    }

    public void refreshQuestsFor(Player p) {
        if (p == null) return;
        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return;
        if (!(top.getHolder() instanceof GUIHolder holder)) return;
        if (holder.tab != Tab.QUESTS) return;
        fillQuests(top, p);
    }

    public boolean isViewingQuests(Player p) {
        if (p == null) return false;
        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return false;
        if (!(top.getHolder() instanceof GUIHolder holder)) return false;
        return holder.tab == Tab.QUESTS;
    }

    private ItemStack playerInfoItem(Player p) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta im = head.getItemMeta();
        if (im instanceof SkullMeta sm) {
            // Prefer using PlayerProfile to avoid triggering remote lookups.
            // Resolve the correct interface type via reflection to be compatible across Bukkit/Paper versions.
            boolean appliedProfile = false;
            try {
                Class<?> profileClass = null;
                try { profileClass = Class.forName("org.bukkit.profile.PlayerProfile"); } catch (Throwable ignore) {}
                if (profileClass == null) {
                    try { profileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile"); } catch (Throwable ignore) {}
                }
                if (profileClass != null) {
                    Method getProfile = p.getClass().getMethod("getPlayerProfile");
                    Object profile = getProfile.invoke(p);
                    if (profile != null && profileClass.isInstance(profile)) {
                        Method setProfile = sm.getClass().getMethod("setPlayerProfile", profileClass);
                        setProfile.invoke(sm, profile);
                        appliedProfile = true;
                    }
                }
            } catch (Throwable ignore) {
                // getPlayerProfile or setPlayerProfile not available; will fall back below
            }
            if (!appliedProfile) {
                sm.setOwningPlayer(p);
            }
            sm.setDisplayName(ChatColor.AQUA + p.getName());
            double bal = economy.getBalance(p);
            sm.setLore(List.of(
                    ChatColor.GRAY + "Баланс: " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", bal)
            ));
            head.setItemMeta(sm);
        } else {
            // Fallback generic item
            im.setDisplayName(ChatColor.AQUA + p.getName());
            double bal = economy.getBalance(p);
            im.setLore(List.of(ChatColor.GRAY + "Баланс: " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", bal)));
            head.setItemMeta(im);
        }
        return head;
    }

    private int countInInventory(Player p, Material m) {
        int total = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != m) continue;
            total += it.getAmount();
        }
        return total;
    }

    private int removeFromInventory(Player p, Material m, int amount) {
        int toRemove = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            int take = Math.min(it.getAmount(), toRemove);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) contents[i] = null;
            toRemove -= take;
        }
        p.getInventory().setContents(contents);
        return amount - toRemove;
    }

    public void open(Player player, Tab tab) {
        Inventory inv = buildInventory(player, tab);
        player.openInventory(inv);
    }

    public boolean isOurInventory(Inventory inv) {
        return inv != null && inv.getHolder() instanceof GUIHolder;
    }

    private Inventory buildInventory(Player viewer, Tab tab) {
        GUIHolder holder = new GUIHolder(tab);
        String title = TITLE_PREFIX + ChatColor.YELLOW + switch (tab) {
            case SHOP -> "Магазин";
            case QUESTS -> "Квести";
            case SELL -> "Продаж";
        };
        Inventory inv = Bukkit.createInventory(holder, 54, title);

        // Fill background
        ItemStack pane = namedItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);

        // Tabs on the very top row, positions 2, 4, 6
        inv.setItem(2, tabItem(Tab.SHOP, tab == Tab.SHOP));
        inv.setItem(4, tabItem(Tab.QUESTS, tab == Tab.QUESTS));
        inv.setItem(6, tabItem(Tab.SELL, tab == Tab.SELL));

        // Content area (3 rows center)
        switch (tab) {
            case SHOP -> fillShop(inv);
            case QUESTS -> fillQuests(inv, viewer);
            case SELL -> fillSellOffers(inv);
        }

        // Player info bottom-left
        inv.setItem(45, playerInfoItem(viewer));

        return inv;
    }

    private void fillShop(Inventory inv) {
        // Show Lucky Blocks on their own centered row, then rotating items centered per row
        GUIHolder holder = (GUIHolder) inv.getHolder();

        // Clear all content slots first
        for (int s : contentSlots()) inv.setItem(s, null);

        List<Entry> lucky = buildLuckyEntries();
        List<Entry> rotating = buildRotatingEntries();

        // Row 0: Lucky blocks (centered)
        int[] row0 = new int[]{19,20,21,22,23,24,25};
        placeCenteredRow(inv, holder, row0, lucky);

        // Rows 1 and 2: Rotating items (centered per row)
        int[] row1 = new int[]{28,29,30,31,32,33,34};
        int[] row2 = new int[]{37,38,39,40,41,42,43};
        int firstRowCount = Math.min(rotating.size(), row1.length);
        placeCenteredRow(inv, holder, row1, rotating.subList(0, firstRowCount));
        int remaining = rotating.size() - firstRowCount;
        if (remaining > 0) {
            int secondRowCount = Math.min(remaining, row2.length);
            placeCenteredRow(inv, holder, row2, rotating.subList(firstRowCount, firstRowCount + secondRowCount));
        }

        // Timer item moved to top-left (slot 0): shows time remaining
        inv.setItem(0, timerItem());
    }

    private List<Entry> buildEntries() {
        List<Entry> entries = new ArrayList<>();
        // Lucky blocks: common, rare, epic (always present)
        entries.add(luckyEntry("common", plugin.getConfig().getDouble("lucky_block.prices.common", plugin.getConfig().getDouble("lucky_block.price", 500.0))));
        entries.add(luckyEntry("rare", plugin.getConfig().getDouble("lucky_block.prices.rare", 1500.0)));
        entries.add(luckyEntry("epic", plugin.getConfig().getDouble("lucky_block.prices.epic", 3000.0)));

        // Rotating drop items
        List<ShopItem> drop = new ArrayList<>(dropManager.getCurrentDrop());
        Set<String> usedGroups = new HashSet<>();
        double coeff = plugin.getConfig().getDouble("shop.enchant.random.coefficient", 0.25);
        double cap = plugin.getConfig().getDouble("shop.enchant.max_multiplier", 3.0);
        for (ShopItem si : drop) {
            String group = groupKeyFor(si.getMaterial());
            if (group != null && usedGroups.contains(group)) continue; // enforce uniqueness for certain families
            ItemStack give = new ItemStack(si.getMaterial());
            // Apply random enchants when appropriate and measure quality
            double quality = 0.0;
            if (isArmor(si.getMaterial()) || isWeapon(si.getMaterial())) {
                quality = applyRandomEnchants(give);
            }
            double base = catalog.dynamicShopPrice(si.getMaterial());
            double mult = Math.min(cap, 1.0 + coeff * quality);
            double price = Math.round(base * mult * 100.0) / 100.0;
            entries.add(new Entry(si, null, price, give));
            if (group != null) usedGroups.add(group);
        }
        return entries;
    }

    private List<Entry> buildLuckyEntries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(luckyEntry("common", plugin.getConfig().getDouble("lucky_block.prices.common", plugin.getConfig().getDouble("lucky_block.price", 500.0))));
        // Support both 'gold' and 'rare' keys for configuration
        double goldPrice = plugin.getConfig().getDouble("lucky_block.prices.gold",
                plugin.getConfig().getDouble("lucky_block.prices.rare", 1500.0));
        entries.add(luckyEntry("gold", goldPrice));
        entries.add(luckyEntry("epic", plugin.getConfig().getDouble("lucky_block.prices.epic", 3000.0)));
        return entries;
    }

    private List<Entry> buildRotatingEntries() {
        List<Entry> entries = new ArrayList<>();
        List<ShopItem> drop = new ArrayList<>(dropManager.getCurrentDrop());
        Set<String> usedGroups = new HashSet<>();
        double coeff = plugin.getConfig().getDouble("shop.enchant.random.coefficient", 0.25);
        double cap = plugin.getConfig().getDouble("shop.enchant.max_multiplier", 3.0);
        for (ShopItem si : drop) {
            String group = groupKeyFor(si.getMaterial());
            if (group != null && usedGroups.contains(group)) continue; // enforce uniqueness for certain families
            ItemStack give = new ItemStack(si.getMaterial());
            double quality = 0.0;
            if (isArmor(si.getMaterial()) || isWeapon(si.getMaterial())) {
                quality = applyRandomEnchants(give);
            }
            double base = catalog.dynamicShopPrice(si.getMaterial());
            double mult = Math.min(cap, 1.0 + coeff * quality);
            double price = Math.round(base * mult * 100.0) / 100.0;
            entries.add(new Entry(si, null, price, give));
            if (group != null) usedGroups.add(group);
        }
        return entries;
    }

    private void placeCenteredRow(Inventory inv, GUIHolder holder, int[] rowSlots, List<Entry> items) {
        int n = Math.min(items.size(), rowSlots.length);
        int offset = (rowSlots.length - n) / 2;
        for (int i = 0; i < n; i++) {
            int slot = rowSlots[offset + i];
            Entry en = items.get(i);
            inv.setItem(slot, toDisplayItem(en));
            holder.entries.put(slot, en);
        }
    }

    private void fillSell(Inventory inv) {
        // Instruction
        inv.setItem(0, namedItem(Material.CLOCK, ChatColor.AQUA + "Наступна ротація через: " + ChatColor.YELLOW + "--:--"));
        // Buttons
        inv.setItem(7, buttonItem(Material.BARRIER, ChatColor.RED + "Очистити"));
        inv.setItem(8, buttonItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Підтвердити продаж (0)"));
        // Content area left empty; players will drag items here
        for (int s : contentSlots()) inv.setItem(s, null);
    }

    private void fillSellOffers(Inventory inv) {
        // Clear content area
        for (int s : contentSlots()) inv.setItem(s, null);
        inv.setItem(0, sellTimerItem());

        GUIHolder holder = (GUIHolder) inv.getHolder();
        holder.sellSlots.clear();

        List<SellRotationManager.Offer> offers = sellManager.getOffers();
        // Place up to 20 offers across rows
        int[] slots = contentSlots();
        int placed = 0;
        for (int i = 0; i < slots.length && placed < 20 && i < offers.size(); i++) {
            int s = slots[i];
            SellRotationManager.Offer o = offers.get(i);
            ItemStack it;
            if (o.disabled || o.currentPrice <= 0.0) {
                it = new ItemStack(Material.BARRIER);
                ItemMeta meta = it.getItemMeta();
                meta.setDisplayName(ChatColor.RED + "Немає попиту");
                meta.setLore(List.of(ChatColor.DARK_GRAY + o.material.name()));
                it.setItemMeta(meta);
            } else {
                it = new ItemStack(o.material);
                ItemMeta meta = it.getItemMeta();
                meta.setDisplayName(ChatColor.YELLOW + prettyName(o.material.name()));
                int maxUnits = sellManager.maxSellableUnits(o);
                int group = Math.max(1, o.groupSize);
                int groupsInStack = Math.max(1, 64 / group);
                double stackPayout = sellManager.previewPayout(o.material, groupsInStack);
                meta.setLore(List.of(
                        ChatColor.DARK_GRAY + "Розмір групи: " + ChatColor.WHITE + group,
                        ChatColor.DARK_GRAY + "Ціна за групу: " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", o.currentPrice),
                        ChatColor.DARK_GRAY + "Ціна за стак (64): " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", stackPayout),
                        ChatColor.DARK_GRAY + "Доступно груп до обнулення: " + ChatColor.WHITE + maxUnits,
                        ChatColor.GRAY + "Натисніть, щоб продати всі доступні групи з інвентарю"
                ));
                it.setItemMeta(meta);
                // Show group size as the stack count (bottom-right number)
                it.setAmount(Math.max(1, group));
            }
            inv.setItem(s, it);
            holder.sellSlots.put(s, o.material);
            placed++;
        }
    }

    private void fillQuests(Inventory inv, Player viewer) {
        // Ensure daily reset for this viewer
        questManager.ensureDailySynced(viewer);
        // Clear content area
        for (int s : contentSlots()) inv.setItem(s, null);
        inv.setItem(0, questTimerItem());

        GUIHolder holder = (GUIHolder) inv.getHolder();
        holder.questSlots.clear();

        List<QuestDef> defs = new ArrayList<>(questManager.getAll());
        // Build quest items
        List<ItemStack> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (QuestDef def : defs) {
            ItemStack it = questDisplayItem(viewer, def);
            items.add(it);
            ids.add(def.getId());
        }
        // Place centered across rows
        int[] row1 = new int[]{19,20,21,22,23,24,25};
        int[] row2 = new int[]{28,29,30,31,32,33,34};
        int[] row3 = new int[]{37,38,39,40,41,42,43};
        List<int[]> rows = List.of(row1, row2, row3);
        int index = 0;
        for (int[] row : rows) {
            int remaining = items.size() - index;
            if (remaining <= 0) break;
            int n = Math.min(remaining, row.length);
            int offset = (row.length - n) / 2;
            for (int i = 0; i < n; i++) {
                int slot = row[offset + i];
                inv.setItem(slot, items.get(index));
                holder.questSlots.put(slot, ids.get(index));
                index++;
            }
        }
    }

    private ItemStack questDisplayItem(Player p, QuestDef def) {
        boolean completed = questManager.isCompleted(p.getUniqueId(), def.getId());
        boolean claimed = questManager.isClaimed(p.getUniqueId(), def.getId());
        int req = def.getRequired();
        int progress = questManager.getProgress(p.getUniqueId(), def.getId());
        // For FETCH quests, show real-time inventory count; also mark as virtually ready if enough in inventory
        boolean virtuallyReady = false;
        if (def.getKind() == QuestDef.Kind.FETCH && !claimed) {
            Material target = def.getTargetMaterial();
            if (target != null) {
                int have = countInInventory(p, target);
                progress = Math.min(have, req);
                if (have >= req) virtuallyReady = true;
            }
        }

        Material icon;
        if (claimed) {
            icon = Material.BEDROCK;
        } else {
            switch (def.getKind()) {
                case FETCH -> icon = Material.CHEST;
                case KILL -> icon = Material.IRON_SWORD;
                case MINE -> icon = Material.IRON_PICKAXE;
                case FISH -> icon = Material.FISHING_ROD;
                default -> icon = Material.BOOK;
            }
        }
        ItemStack it = new ItemStack(icon);
        ItemMeta meta = it.getItemMeta();
        String status;
        ChatColor color;
        if (claimed) { status = "ОТРИМАНО"; color = ChatColor.DARK_GREEN; }
        else if (completed || virtuallyReady) { status = "ГОТОВО"; color = ChatColor.GREEN; }
        else { status = progress + "/" + req; color = ChatColor.YELLOW; }
        meta.setDisplayName(color + def.getName());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "ID: " + def.getId());
        lore.add(ChatColor.DARK_GRAY + "Тип: " + localizeKind(def.getKind()));
        lore.add(ChatColor.DARK_GRAY + "Прогрес: " + (claimed ? ChatColor.DARK_GREEN + "ОТРИМАНО" : ((completed || virtuallyReady) ? ChatColor.GREEN + "ГОТОВО" : ChatColor.YELLOW + (progress + "/" + req))));
        double shownReward = questManager.getDailyReward(def.getId());
        lore.add(ChatColor.DARK_GRAY + "Нагорода: " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", shownReward));
        lore.add( (!claimed && (completed || virtuallyReady)) ? ChatColor.GREEN + "Натисніть, щоб отримати" : (def.getKind() == QuestDef.Kind.FETCH && !claimed ? ChatColor.AQUA + "Натисніть, щоб здати предмети" : ChatColor.GRAY + "Продовжуйте виконання...") );
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private String localizeKind(QuestDef.Kind kind) {
        return switch (kind) {
            case FETCH -> "ДОСТАВКА";
            case KILL -> "ВБИВСТВА";
            case MINE -> "ШАХТУВАННЯ";
            case FISH -> "РИБАЛКА";
        };
    }

    private int[] contentSlots() {
        // Use rows 3-5 (indexes 18..44) excluding borders
        return new int[]{
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43
        };
    }

    // --- Sell helpers ---
    private boolean isSellable(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        Material m = stack.getType();
        // Hard-block certain types
        if (m.name().endsWith("SPAWN_EGG")) return false;
        if (catalog.categorize(m) == Category.LUCKY_BLOCKS) return false;
        // Block by blacklist
        List<String> bl = plugin.getConfig().getStringList("sell.blacklist");
        if (bl != null && bl.stream().map(String::toUpperCase).anyMatch(s -> s.equals(m.name()))) return false;
        // Optionally block garbage category entirely
        boolean blockGarbage = plugin.getConfig().getBoolean("sell.block_garbage_category", false);
        if (blockGarbage && catalog.categorize(m) == Category.GARBAGE) return false;
        return true;
    }

    private double sellPrice(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return 0.0;
        double sellMult = plugin.getConfig().getDouble("sell.base_multiplier", 0.45);
        Material m = stack.getType();
        // Determine the current buy price for this material (what the player would pay)
        double shopUnit = m.name().endsWith("SPAWN_EGG") ? plugin.getConfig().getDouble("spawn_egg.price", 2500.0) : catalog.dynamicShopPrice(m);
        // Apply actual enchant-based multiplier for the sold item
        double enchantMult = computeEnchantMultiplier(stack);
        double total = shopUnit * enchantMult * sellMult * stack.getAmount();
        return Math.round(total * 100.0) / 100.0;
    }

    private double computeEnchantMultiplier(ItemStack stack) {
        boolean enabled = plugin.getConfig().getBoolean("sell.enchant.enabled", true);
        if (!enabled) return 1.0;
        Map<org.bukkit.enchantments.Enchantment, Integer> ench = stack.getEnchantments();
        if (ench == null || ench.isEmpty()) return 1.0;
        boolean includeCurses = plugin.getConfig().getBoolean("sell.enchant.include_curses", false);
        double perLevel = plugin.getConfig().getDouble("sell.enchant.per_level_bonus", 0.05); // +5% per level by default
        int totalLevels = 0;
        for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> e : ench.entrySet()) {
            org.bukkit.enchantments.Enchantment enchType = e.getKey();
            int lvl = Math.max(0, e.getValue());
            boolean cursed;
            try {
                cursed = (boolean) org.bukkit.enchantments.Enchantment.class.getMethod("isCursed").invoke(enchType);
            } catch (Throwable ignore) {
                cursed = enchType.getKey().getKey().toUpperCase(Locale.ROOT).contains("CURSE");
            }
            if (!includeCurses && cursed) continue;
            totalLevels += lvl;
        }
        double mult = 1.0 + perLevel * totalLevels;
        double cap = plugin.getConfig().getDouble("sell.enchant.max_multiplier", 3.0);
        if (mult > cap) mult = cap;
        return mult;
    }

    private void updateSellPreview(Inventory inv) {
        // total and per-slot highlighting
        double total = 0.0;
        for (int s : contentSlots()) {
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType() == Material.AIR) continue;
            if (!isSellable(it)) {
                // remove illegal items from sell area
                inv.setItem(s, null);
                continue;
            }
            // Remove previous preview line to avoid duplication
            sanitizeSellMeta(it);
            double price = sellPrice(it);
            total += price;
            // add price lore (temporary while in sell UI)
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                List<String> orig = meta.getLore();
                if (orig != null) lore.addAll(orig);
                lore.add(ChatColor.GREEN + "Продається" + ChatColor.DARK_GRAY + " | Ціна: " + ChatColor.GOLD + price);
                meta.setLore(lore);
                it.setItemMeta(meta);
            }
        }
        // Update confirm button with total
        ItemStack confirm = buttonItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Підтвердити продаж (" + ChatColor.GOLD + total + ChatColor.GREEN + ")");
        inv.setItem(8, confirm);
    }

    private void clearSellArea(Inventory inv, Player p) {
        for (int s : contentSlots()) {
            ItemStack it = inv.getItem(s);
            if (it != null && it.getType() != Material.AIR) {
                sanitizeSellMeta(it);
                Map<Integer, ItemStack> left = p.getInventory().addItem(it);
                if (!left.isEmpty()) {
                    // drop leftovers
                    left.values().forEach(rem -> p.getWorld().dropItemNaturally(p.getLocation(), rem));
                }
                inv.setItem(s, null);
            }
        }

    }

    private void refreshPlayerInfos() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            // Any of our tabs shows player info in slot 45
            top.setItem(45, playerInfoItem(p));
        }
    }

    private void confirmSell(Inventory inv, Player p) {
        double total = 0.0;
        Map<Material, Integer> soldCounts = new HashMap<>();
        for (int s : contentSlots()) {
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType() == Material.AIR) continue;
            if (!isSellable(it)) {
                // shouldn't happen due to preview, skip
                continue;
            }
            total += sellPrice(it);
            soldCounts.merge(it.getType(), it.getAmount(), Integer::sum);
            sanitizeSellMeta(it);
            inv.setItem(s, null);
        }
        if (total <= 0.0) {
            p.sendMessage(ChatColor.RED + "Немає предметів для продажу.");
            return;
        }
        if (!economy.isEnabled()) {
            p.sendMessage(ChatColor.RED + "Економіка недоступна. Встановіть Vault.");
            return;
        }
        if (economy.deposit(p, total)) {
            p.sendMessage(ChatColor.GREEN + "Продано предмети на " + ChatColor.GOLD + total + ChatColor.GREEN + ".");
            // Record dynamic sales after successful payout, then ping a sound
            for (Map.Entry<Material, Integer> en : soldCounts.entrySet()) {
                catalog.recordSale(en.getKey(), en.getValue());
            }
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.0f);
        } else {
            p.sendMessage(ChatColor.RED + "Транзакція не вдалася.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1.0f);
        }
    }

    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        // No special handling needed since SELL tab is removed
    }

    private void sanitizeSellMeta(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty()) return;
        String strippedPrefix = ChatColor.stripColor(lore.get(0) != null ? lore.get(0) : "");
        lore.removeIf(line -> {
            String s = ChatColor.stripColor(line);
            return s.startsWith("Sellable") || s.startsWith("Sell Price:") || s.startsWith("Продається") || s.startsWith("Ціна:");
        });
        meta.setLore(lore.isEmpty() ? null : lore);
        it.setItemMeta(meta);
    }

    private boolean isSimilarIgnoringPreview(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        ItemStack ca = a.clone();
        ItemStack cb = b.clone();
        // remove our temporary preview lore
        sanitizeSellMeta(ca);
        sanitizeSellMeta(cb);
        ca.setAmount(1);
        cb.setAmount(1);
        return ca.isSimilar(cb);
    }

    private ItemStack tabItem(Tab tab, boolean selected) {
        Material mat = switch (tab) {
            case SHOP -> Material.EMERALD;
            case QUESTS -> Material.BOOK;
            case SELL -> Material.CHEST;
        };
        String name = (selected ? ChatColor.GREEN + "» " : ChatColor.GRAY + "") + switch (tab) {
            case SHOP -> "Магазин";
            case QUESTS -> "Квести";
            case SELL -> "Продаж";
        } + (selected ? ChatColor.GREEN + " «" : "");
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(ChatColor.DARK_GRAY + "Натисніть, щоб перемкнути"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack namedItem(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buttonItem(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    public void handleClick(InventoryClickEvent e) {
        Inventory topInv = e.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof GUIHolder holder)) return;
        // We'll cancel selectively: default cancel only for clicks in the top inventory (our GUI),
        // allow bottom inventory unless we intercept to add to sell buffer.
        int topSize = e.getView().getTopInventory().getSize();
        boolean inTop = e.getRawSlot() < topSize;
        e.setCancelled(inTop);

        int slot = e.getRawSlot();
        Player p = (Player) e.getWhoClicked();

        // Top-level tabs
        if (slot == 2) {
            p.openInventory(buildInventory(p, Tab.SHOP));
            return;
        } else if (slot == 4) {
            p.openInventory(buildInventory(p, Tab.QUESTS));
            return;
        } else if (slot == 6) {
            p.openInventory(buildInventory(p, Tab.SELL));
            return;
        }

        if (holder.tab == Tab.SHOP) {
            // Purchasing in content area
            int[] slots = contentSlots();
            for (int s : slots) {
                if (slot == s) {
                    ItemStack clicked = e.getCurrentItem();
                    if (clicked == null || clicked.getType() == Material.AIR) return;
                    Entry en = holder.entries.get(s);
                    if (en == null) return;
                    // Charge the precomputed entry price so random enchant scaling remains consistent
                    double price = en.price;
                    if (!economy.isEnabled()) {
                        p.sendMessage(ChatColor.RED + "Економіка недоступна. Встановіть Vault.");
                        return;
                    }
                    if (!economy.withdraw(p, price)) {
                        p.sendMessage(ChatColor.RED + "Недостатньо грошей. Ціна: " + price);
                        return;
                    }
                    if (en.luckyTier != null) {
                        // Execute command to give lucky block, map 'rare' to 'gold' for external command compatibility
                        String tierStr = en.luckyTier.toLowerCase(Locale.ROOT);
                        if ("rare".equals(tierStr)) tierStr = "gold";
                        String cmd = String.format("luckyblocks give %s %s 1", p.getName(), tierStr);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        p.sendMessage(ChatColor.GREEN + "Придбано Лакі Блок (" + tierStr + ") за " + ChatColor.GOLD + price);
                    } else {
                        ItemStack give = en.give;
                        p.getInventory().addItem(give);
                        p.sendMessage(ChatColor.GREEN + "Придбано " + ChatColor.YELLOW + en.item.getMaterial().name() + ChatColor.GREEN + " за " + ChatColor.GOLD + price);
                        // Record dynamic pricing purchase and refresh the shop UI
                        catalog.recordPurchase(en.item.getMaterial());
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);
                        Bukkit.getScheduler().runTask(plugin, this::refreshShopContent);
                    }
                    return;
                }
            }
        }

        if (holder.tab == Tab.SELL) {
            int[] slots = contentSlots();
            for (int s : slots) {
                if (slot == s) {
                    Material mat = holder.sellSlots.get(s);
                    if (mat == null) return;
                    if (!economy.isEnabled()) {
                        p.sendMessage(ChatColor.RED + "Економіка недоступна. Встановіть Vault.");
                        return;
                    }
                    // Count in inventory and compute possible groups
                    int haveItems = countInInventory(p, mat);
                    if (haveItems <= 0) {
                        p.sendMessage(ChatColor.YELLOW + "У вас немає цього предмета в інвентарі.");
                        return;
                    }
                    SellRotationManager.Offer offer = sellManager.getOffer(mat);
                    if (offer == null || offer.disabled || offer.currentPrice <= 0.0) {
                        p.sendMessage(ChatColor.RED + "Цей товар більше не купується.");
                        Bukkit.getScheduler().runTask(plugin, () -> fillSellOffers(topInv));
                        return;
                    }
                    int groupSize = Math.max(1, offer.groupSize);
                    int haveGroups = haveItems / groupSize;
                    int maxUnits = sellManager.maxSellableUnits(offer);
                    int units = Math.min(haveGroups, maxUnits);
                    if (units <= 0) {
                        p.sendMessage(ChatColor.YELLOW + "Попит вичерпано.");
                        return;
                    }
                    SellRotationManager.SellResult res = sellManager.transactSell(mat, units);
                    if (!res.success || res.units <= 0 || res.payout <= 0.0) {
                        p.sendMessage(ChatColor.RED + "Продаж не вдався.");
                        return;
                    }
                    // Remove sold groups from inventory
                    removeFromInventory(p, mat, res.units * groupSize);
                    // Pay out
                    if (!economy.deposit(p, res.payout)) {
                        p.sendMessage(ChatColor.RED + "Транзакція не вдалася.");
                        return;
                    }
                    p.sendMessage(ChatColor.GREEN + "Продано " + ChatColor.YELLOW + mat.name() + ChatColor.GREEN + " " + (res.units * groupSize) + " шт. (" + res.units + " груп по " + groupSize + ") за " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", res.payout));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.1f);
                    Bukkit.getScheduler().runTask(plugin, () -> fillSellOffers(topInv));
                    return;
                }
            }
            return;
        }
        
        if (holder.tab == Tab.QUESTS) {
            int[] slots = contentSlots();
            for (int s : slots) {
                if (slot == s) {
                    String qid = holder.questSlots.get(s);
                    if (qid == null) return;
                    // Try claim first if ready
                    if (!questManager.isClaimed(p.getUniqueId(), qid)) {
                        if (questManager.isCompleted(p.getUniqueId(), qid)) {
                            int res = questManager.claim(p, qid);
                            if (res != 0) Bukkit.getScheduler().runTask(plugin, () -> fillQuests(topInv, p));
                            return;
                        }
                    }
                    // If fetch and not completed/claimed, attempt delivery and auto-claim on success
                    QuestDef def = questManager.get(qid);
                    if (def != null && def.getKind() == QuestDef.Kind.FETCH && !questManager.isClaimed(p.getUniqueId(), qid)) {
                        boolean ok = questManager.deliverFetch(p, qid);
                        if (ok) {
                            int res = questManager.claim(p, qid);
                            Bukkit.getScheduler().runTask(plugin, () -> fillQuests(topInv, p));
                            return;
                        }
                    }
                    // Otherwise show progress info
                    int prog = questManager.getProgress(p.getUniqueId(), qid);
                    int req = def != null ? def.getRequired() : 0;
                    p.sendMessage(ChatColor.YELLOW + "Прогрес: " + prog + "/" + req);
                    return;
                }
            }
            return;
        }
    }

    public void handleDrag(InventoryDragEvent e) {
        // No special drag handling
    }

    private ItemStack toDisplayItem(Entry en) {
        return toDisplayItem(en.item, en.luckyTier, en.price, en.give);
    }

    private ItemStack toDisplayItem(ShopItem si, String luckyTier) {
        return toDisplayItem(si, luckyTier, si.getPrice(), new ItemStack(si.getMaterial()));
    }

    private ItemStack toDisplayItem(ShopItem si, String luckyTier, double price, ItemStack base) {
        // Lucky Blocks now use block icons; normal rotating items use their actual material
        ItemStack it = luckyTier != null ? new ItemStack(materialForLuckyTier(luckyTier)) : base.clone();
        ItemMeta meta = it.getItemMeta();
        String tierName = luckyTier != null ? luckyDisplayName(luckyTier) : si.getTier().display();
        ChatColor tierColor = luckyTier != null ? luckyColor(luckyTier) : si.getTier().color();
        String baseName = luckyTier != null ? "Lucky Block" : prettyName(si.getMaterial().name());
        String displayName = tierColor + tierName + ChatColor.GRAY + " | " + ChatColor.YELLOW + baseName;
        meta.setDisplayName(displayName);
        meta.setLore(List.of(
                ChatColor.DARK_GRAY + (luckyTier != null ? "Type: Lucky Block" : "Category: " + ChatColor.WHITE + si.getCategory().name()),
                ChatColor.DARK_GRAY + "Price: " + ChatColor.GOLD + price,
                ChatColor.DARK_GRAY + "Click to buy"
        ));
        // Add enchant glint for Tier icons
        if (luckyTier != null || si.getTier() == Tier.EPIC || si.getTier() == Tier.LEGENDARY) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true); // use UNBREAKING for compatibility
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private String prettyName(String name) {
        String[] parts = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private ChatColor luckyColor(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "common" -> ChatColor.WHITE;
            case "rare", "gold" -> ChatColor.GOLD;
            case "epic" -> ChatColor.DARK_PURPLE;
            default -> ChatColor.GOLD;
        };
    }

    private String luckyDisplayName(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "common" -> "Common";
            case "rare", "gold" -> "Gold";
            case "epic" -> "Epic";
            default -> capitalize(tier);
        };
    }

    private Entry luckyEntry(String tier, double price) {
        // Represent Lucky Blocks with block icons based on tier
        Material mat = materialForLuckyTier(tier);
        ShopItem si = new ShopItem(mat, Tier.EPIC, com.bodia.shoptrader.model.Category.LUCKY_BLOCKS, price);
        return new Entry(si, tier, price, null);
    }

    private Material materialForLuckyTier(String tier) {
        String t = tier == null ? "" : tier.toLowerCase(Locale.ROOT);
        switch (t) {
            case "epic":
                return Material.NETHERITE_BLOCK;
            case "rare":
            case "gold":
                return Material.GOLD_BLOCK;
            case "common":
            default:
                return Material.IRON_BLOCK;
        }
    }

    // --- Lucky Block custom heads ---
    // COMMON
    private static final String LUCKY_TEX_COMMON = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGIxODdlNDE1NjQwZGEwNTcyNjBkMTMwMDk5ODBjMDcyOTRmYzJkNzI0MGNlYzZmOWUzOTA3OTZjYmIxOTQ4NCJ9fX0=";
    // GOLD (alias: RARE)
    private static final String LUCKY_TEX_RARE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmRiODhmMjZjN2ZjYTQ1MGE1NGI2OTM5YjZmNzRkMzI0Yzg0ZWYyMTM4OTY3MDQ2ZTA5Y2U5OTZiNGE0ODkyMyJ9fX0=";
    // EPIC
    private static final String LUCKY_TEX_EPIC = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzcyNWRhODJhYTBhZGU1ZDUyYmQyMDI0ZjRiYzFkMDE5ZmMwMzBlOWVjNWUwZWMxNThjN2Y5YTZhYTBjNDNiYSJ9fX0=";

    private ItemStack luckyHead(String tier) {
        String b64 = switch (tier.toLowerCase(Locale.ROOT)) {
            case "common" -> LUCKY_TEX_COMMON;
            case "rare", "gold" -> LUCKY_TEX_RARE;
            case "epic" -> LUCKY_TEX_EPIC;
            default -> LUCKY_TEX_COMMON;
        };

        // 1) Try direct GameProfile injection first (no network, most reliable across Spigot/Paper)
        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Object gp = gameProfileClass.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), null);
            Object props = gameProfileClass.getMethod("getProperties").invoke(gp);
            Object prop = propertyClass.getConstructor(String.class, String.class).newInstance("textures", b64);
            Method put = props.getClass().getMethod("put", String.class, propertyClass);
            put.invoke(props, "textures", prop);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            // Assign to any field named 'profile' or of GameProfile type
            Field target = null;
            for (Field f : sm.getClass().getDeclaredFields()) {
                if (f.getName().equalsIgnoreCase("profile") || f.getType().getName().endsWith("GameProfile")) {
                    target = f; break;
                }
            }
            if (target != null) {
                target.setAccessible(true);
                target.set(sm, gp);
                head.setItemMeta(sm);
                return head;
            }
        } catch (Throwable ignored) {
            // Continue to PlayerProfile path
        }

        // 2) Fallback: Paper PlayerProfile; try direct textures property first, then URL
        try {
            Object profile = null;
            try {
                Method m = Bukkit.class.getMethod("createProfile", UUID.class, String.class);
                profile = m.invoke(null, UUID.randomUUID(), null);
            } catch (NoSuchMethodException ignored) {
                try {
                    Method m = Bukkit.class.getMethod("createProfile", UUID.class);
                    profile = m.invoke(null, UUID.randomUUID());
                } catch (NoSuchMethodException ignored2) {}
            }
            if (profile != null) {
                // Try adding a ProfileProperty("textures", b64) if available
                try {
                    Object props = profile.getClass().getMethod("getProperties").invoke(profile);
                    // Try org.bukkit.profile.ProfileProperty first
                    Class<?> propCls = null;
                    try { propCls = Class.forName("org.bukkit.profile.ProfileProperty"); } catch (Throwable t) {}
                    if (propCls == null) {
                        try { propCls = Class.forName("com.destroystokyo.paper.profile.ProfileProperty"); } catch (Throwable t) {}
                    }
                    if (propCls != null) {
                        Object propObj;
                        try {
                            propObj = propCls.getConstructor(String.class, String.class).newInstance("textures", b64);
                        } catch (NoSuchMethodException e) {
                            // Some variants need (name, value, signature)
                            propObj = propCls.getConstructor(String.class, String.class, String.class).newInstance("textures", b64, null);
                        }
                        // props is a collection-like type with add
                        Method add = props.getClass().getMethod("add", Object.class);
                        add.invoke(props, propObj);
                    }
                } catch (Throwable ignoredProps) {
                    // Fall back to URL setSkin
                    try {
                        String url = extractSkinUrl(b64);
                        if (url != null) {
                            Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
                            textures.getClass().getMethod("setSkin", URL.class).invoke(textures, new URL(url));
                            try { profile.getClass().getMethod("setTextures", textures.getClass()).invoke(profile, textures); } catch (NoSuchMethodException ignored) {}
                        }
                    } catch (Throwable ignored2) {}
                }

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                boolean assigned = false;
                for (Method m : sm.getClass().getMethods()) {
                    if ((m.getName().equals("setPlayerProfile") || m.getName().equals("setOwnerProfile"))
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].getSimpleName().contains("PlayerProfile")) {
                        m.invoke(sm, profile);
                        assigned = true;
                        break;
                    }
                }
                if (assigned) {
                    head.setItemMeta(sm);
                    return head;
                }
            }
        } catch (Throwable ignored) {
            // Give up; return a generic player head so it's not a sponge
        }

        return new ItemStack(Material.PLAYER_HEAD);
    }

    private String extractSkinUrl(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64));
            String marker = "\"url\":\"";
            int idx = json.indexOf(marker);
            if (idx == -1) return null;
            int start = idx + marker.length();
            int end = json.indexOf('"', start);
            if (end == -1) return null;
            return json.substring(start, end);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static class GUIHolder implements InventoryHolder {
        private final Tab tab;
        private final Map<Integer, Entry> entries = new HashMap<>();
        private final Map<Integer, String> questSlots = new HashMap<>();
        private final Map<Integer, Material> sellSlots = new HashMap<>();
        private GUIHolder(Tab tab) { this.tab = tab; }
        public Tab tab() { return tab; }
        @Override public Inventory getInventory() { return Bukkit.createInventory(null, 9); }
    }

    private static class Entry {
        final ShopItem item;
        final String luckyTier; // null if normal item
        final double price;
        final ItemStack give; // null for lucky blocks (command-based)
        Entry(ShopItem item, String luckyTier, double price, ItemStack give) { this.item = item; this.luckyTier = luckyTier; this.price = price; this.give = give; }
    }

    private ItemStack timerItem() {
        long secs = dropManager.getSecondsRemaining();
        long m = secs / 60;
        long s = secs % 60;
        String t = String.format("%02d:%02d", m, s);
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Наступна ротація через: " + ChatColor.YELLOW + t);
        meta.setLore(List.of(ChatColor.DARK_GRAY + "Магазин оновлюється автоматично"));
        it.setItemMeta(meta);
        return it;
    }

    private boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("ELYTRA");
    }

    private boolean isWeapon(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("SHIELD");
    }

    private boolean isShulkerBox(Material m) {
        String n = m.name();
        return n.endsWith("_SHULKER_BOX");
    }

    private String groupKeyFor(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD")) return "SWORD";
        if (n.endsWith("_AXE")) return "AXE";
        if (n.equals("BOW")) return "BOW";
        if (n.equals("CROSSBOW")) return "CROSSBOW";
        if (n.equals("TRIDENT")) return "TRIDENT";
        if (n.equals("SHIELD")) return "SHIELD";
        if (isShulkerBox(m)) return "SHULKER_BOX";
        return null; // other items may repeat
    }

    // Apply random enchantments appropriate for the item and return a quality score
    private double applyRandomEnchants(ItemStack it) {
        Material m = it.getType();
        List<Enchantment> pool = new ArrayList<>();
        Map<Enchantment, Integer> max = new HashMap<>();

        String n = m.name();
        if (n.endsWith("_SWORD")) {
            add(pool, max, Enchantment.SHARPNESS, 5);
            add(pool, max, Enchantment.SMITE, 5);
            add(pool, max, Enchantment.BANE_OF_ARTHROPODS, 5);
            add(pool, max, Enchantment.LOOTING, 3);
            add(pool, max, Enchantment.SWEEPING_EDGE, 3);
            add(pool, max, Enchantment.FIRE_ASPECT, 2);
            add(pool, max, Enchantment.KNOCKBACK, 2);
            add(pool, max, Enchantment.UNBREAKING, 3);
            add(pool, max, Enchantment.MENDING, 1);
        } else if (n.endsWith("_AXE")) {
            add(pool, max, Enchantment.EFFICIENCY, 5);
            add(pool, max, Enchantment.FORTUNE, 3);
            add(pool, max, Enchantment.SILK_TOUCH, 1);
            add(pool, max, Enchantment.SHARPNESS, 5);
            add(pool, max, Enchantment.UNBREAKING, 3);
            add(pool, max, Enchantment.MENDING, 1);
        } else if (n.equals("BOW")) {
            add(pool, max, Enchantment.POWER, 5);
            add(pool, max, Enchantment.PUNCH, 2);
            add(pool, max, Enchantment.FLAME, 1);
            add(pool, max, Enchantment.INFINITY, 1);
            add(pool, max, Enchantment.UNBREAKING, 3);
            add(pool, max, Enchantment.MENDING, 1);
        } else if (n.equals("CROSSBOW")) {
            add(pool, max, Enchantment.QUICK_CHARGE, 3);
            add(pool, max, Enchantment.MULTISHOT, 1);
            add(pool, max, Enchantment.PIERCING, 4);
            add(pool, max, Enchantment.UNBREAKING, 3);
            add(pool, max, Enchantment.MENDING, 1);
        } else if (n.equals("TRIDENT")) {
            add(pool, max, Enchantment.IMPALING, 5);
            add(pool, max, Enchantment.LOYALTY, 3);
            add(pool, max, Enchantment.RIPTIDE, 3);
            add(pool, max, Enchantment.CHANNELING, 1);
            add(pool, max, Enchantment.UNBREAKING, 3);
            add(pool, max, Enchantment.MENDING, 1);
        } else if (isArmor(m)) {
            add(pool, max, Enchantment.PROTECTION, 4);
            add(pool, max, Enchantment.BLAST_PROTECTION, 4);
            add(pool, max, Enchantment.FIRE_PROTECTION, 4);
            add(pool, max, Enchantment.PROJECTILE_PROTECTION, 4);
            add(pool, max, Enchantment.THORNS, 3);
            add(pool, max, Enchantment.UNBREAKING, 3);
            add(pool, max, Enchantment.MENDING, 1);
            add(pool, max, Enchantment.FEATHER_FALLING, 4);
            add(pool, max, Enchantment.RESPIRATION, 3);
            add(pool, max, Enchantment.AQUA_AFFINITY, 1);
        }

        if (pool.isEmpty()) return 0.0;
        Collections.shuffle(pool);
        int maxCount = Math.min(3, pool.size());
        int count = 1 + new Random().nextInt(maxCount); // 1..maxCount
        double quality = 0.0;
        for (int i = 0; i < count; i++) {
            Enchantment e = pool.get(i);
            int ml = max.getOrDefault(e, 1);
            int lvl = 1 + new Random().nextInt(ml);
            try { it.addUnsafeEnchantment(e, lvl); } catch (Throwable ignore) {}
            quality += (double) lvl / (double) ml;
        }
        return quality; // higher = better
    }

    private void add(List<Enchantment> pool, Map<Enchantment, Integer> max, Enchantment e, int maxLevel) {
        pool.add(e);
        max.put(e, maxLevel);
    }

    private void addArmorEnchants(ItemStack it) {
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
    }

    private void addWeaponEnchants(ItemStack it) {
        String n = it.getType().name();
        if (n.endsWith("_SWORD")) {
            it.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        } else if (n.endsWith("_AXE")) {
            it.addUnsafeEnchantment(Enchantment.SHARPNESS, 4);
        } else if (n.equals("BOW")) {
            it.addUnsafeEnchantment(Enchantment.POWER, 5);
        } else if (n.equals("CROSSBOW")) {
            it.addUnsafeEnchantment(Enchantment.QUICK_CHARGE, 3);
        } else if (n.equals("TRIDENT")) {
            it.addUnsafeEnchantment(Enchantment.IMPALING, 5);
        }
        it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
    }

    public void refreshTimers() {
        // Update the timer item (slot 4) for any player currently viewing the Shop tab
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            if (holder.tab != Tab.SHOP) continue;
            top.setItem(0, timerItem());
        }
    }

    public void tick() {
        // Called every second by plugin scheduler: refresh timer and cycle content when cycle index changes
        refreshTimers();
        // Quests: update countdown timer and detect daily reset to refresh content
        long qs = questManager.secondsUntilNextReset();
        refreshQuestTimers();
        if (lastQuestSecs != -1 && qs > lastQuestSecs) {
            // Reset happened (counter wrapped). Regenerate daily quests and refresh.
            boolean changed = questManager.ensureDailyQuests();
            if (changed) refreshQuestContent();
        }
        lastQuestSecs = qs;
        // Sell: update timer and refresh offers when regen happens
        refreshSellTimers();
        // If timer wrapped, offers were regenerated by SellRotationManager.tick() in plugin; rebuild content
        // We detect wrap by checking if remaining time increased
        // Note: For simplicity, rebuild content every 60 ticks to keep it fresh
        if (System.currentTimeMillis() % 60000L < 50L) {
            refreshSellContent();
        }
        int ci = dropManager.getCycleIndex();
        if (ci != lastCycleIndex) {
            lastCycleIndex = ci;
            refreshShopContent();
        }
        // Player heads: keep balances fresh (rate-limited to once every 5 seconds)
        headRefreshCounter = (headRefreshCounter + 1) % 5;
        if (headRefreshCounter == 0) {
            refreshPlayerInfos();
        }
    }

    private void refreshShopContent() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top == null) continue;
            if (!(top.getHolder() instanceof GUIHolder holder)) continue;
            if (holder.tab != Tab.SHOP) continue;

            // Clear current mappings and items in content area
            holder.entries.clear();
            for (int s : contentSlots()) top.setItem(s, null);

            List<Entry> lucky = buildLuckyEntries();
            List<Entry> rotating = buildRotatingEntries();

            int[] row0 = new int[]{19,20,21,22,23,24,25};
            int[] row1 = new int[]{28,29,30,31,32,33,34};
            int[] row2 = new int[]{37,38,39,40,41,42,43};

            // Place lucky row centered
            placeCenteredRow(top, holder, row0, lucky);

            // Place rotating rows centered per row
            int firstRowCount = Math.min(rotating.size(), row1.length);
            placeCenteredRow(top, holder, row1, rotating.subList(0, firstRowCount));
            int remaining = rotating.size() - firstRowCount;
            if (remaining > 0) {
                int secondRowCount = Math.min(remaining, row2.length);
                placeCenteredRow(top, holder, row2, rotating.subList(firstRowCount, firstRowCount + secondRowCount));
            }
        }
    }
}

package com.bodia.shoptrader.shop;

import com.bodia.shoptrader.model.Category;
import com.bodia.shoptrader.model.ShopItem;
import com.bodia.shoptrader.model.Tier;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

public class Catalog {

    private final Plugin plugin;
    private final Map<Tier, Double> tierPrices = new EnumMap<>(Tier.class);
    private final Map<Category, List<Material>> categoryMaterials = new EnumMap<>(Category.class);
    private final Map<Tier, List<Material>> tierMaterials = new EnumMap<>(Tier.class);
    private final Map<String, Double> yieldFactors = new HashMap<>(); // per-material name -> factor
    private double garbagePrice = 2.0; // configurable price for garbage category
    // Dynamic pricing state
    private final Map<Material, Integer> buysSinceAdjust = new EnumMap<>(Material.class);
    private final Map<Material, Double> shopMultipliers = new EnumMap<>(Material.class); // affects what player pays
    private final Map<Material, Double> sellMultipliers = new EnumMap<>(Material.class); // affects what shop pays player

    // Dynamic pricing parameters (loaded from config with sane defaults)
    private int shopIncreaseEveryNBuys;
    private double shopIncreaseFactor;     // multiplicative, e.g., 0.10 => x1.10
    private double maxShopMultiplier;
    private double sellDecreasePerItem;    // per unit sold, e.g., 0.005 => -0.5% each
    private double minSellMultiplier;
    private double shopDecreasePerItemOnSell; // per unit sold, reduces shop (buy) multiplier
    private double minShopMultiplier;         // floor for shop (buy) multiplier

    public Catalog(Plugin plugin) {
        this.plugin = plugin;
        loadPrices();
        loadYieldFactors();
        loadDynamicParams();
        buildCategoryMaterials();
        buildTierClassification();
    }

    private void loadPrices() {
        tierPrices.put(Tier.COMMON, plugin.getConfig().getDouble("prices.common", 100.0));
        tierPrices.put(Tier.UNCOMMON, plugin.getConfig().getDouble("prices.uncommon", 300.0));
        tierPrices.put(Tier.EPIC, plugin.getConfig().getDouble("prices.epic", 1000.0));
        tierPrices.put(Tier.LEGENDARY, plugin.getConfig().getDouble("prices.legendary", 5000.0));
        this.garbagePrice = plugin.getConfig().getDouble("prices.garbage", 2.0);
    }

    private void loadYieldFactors() {
        yieldFactors.clear();
        // pricing.yield.by_material: map of MATERIAL_NAME -> double factor
        if (plugin.getConfig().isConfigurationSection("pricing.yield.by_material")) {
            var sec = plugin.getConfig().getConfigurationSection("pricing.yield.by_material");
            for (String key : sec.getKeys(false)) {
                double f = sec.getDouble(key, 1.0);
                yieldFactors.put(key.toUpperCase(Locale.ROOT), f);
            }
        }
    }

    public void reload() {
        loadPrices();
        loadYieldFactors();
        loadDynamicParams();
        buildCategoryMaterials();
        buildTierClassification();
    }

    private void buildCategoryMaterials() {
        categoryMaterials.clear();
        categoryMaterials.put(Category.ARMORS, new ArrayList<>());
        categoryMaterials.put(Category.TOOLS_WEAPONS, new ArrayList<>());
        categoryMaterials.put(Category.BLOCKS, new ArrayList<>());
        categoryMaterials.put(Category.SPAWN_EGGS, new ArrayList<>());
        categoryMaterials.put(Category.LUCKY_BLOCKS, new ArrayList<>());
        categoryMaterials.put(Category.GARBAGE, new ArrayList<>());

        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            String name = m.name();
            if (name.endsWith("SPAWN_EGG")) {
                categoryMaterials.get(Category.SPAWN_EGGS).add(m);
                continue;
            }
            if (!isUseful(m)) continue;
            if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("ELYTRA")) {
                categoryMaterials.get(Category.ARMORS).add(m);
            } else if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT") || name.equals("SHIELD")) {
                categoryMaterials.get(Category.TOOLS_WEAPONS).add(m);
            } else if (m.isBlock()) {
                // Useful functional blocks only; non-useful filtered above
                categoryMaterials.get(Category.BLOCKS).add(m);
            }
        }

        // Lucky blocks: we'll represent as a custom item, not a vanilla material classification here.
        categoryMaterials.get(Category.LUCKY_BLOCKS).add(Material.SPONGE);
    }

    private void buildTierClassification() {
        tierMaterials.clear();
        for (Tier t : Tier.values()) tierMaterials.put(t, new ArrayList<>());

        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            String name = m.name();
            if (name.endsWith("SPAWN_EGG")) continue; // exclude spawn eggs from tiering
            if (!isUseful(m)) continue; // exclude misc/garbage

            Tier t = tierFor(m);
            tierMaterials.get(t).add(m);
        }
    }

    public Tier tierFor(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE") || n.contains("NETHERITE") || n.equals("ENCHANTED_GOLDEN_APPLE") || n.equals("ELYTRA") ) return Tier.LEGENDARY;
        if (n.startsWith("DIAMOND") || n.contains("DIAMOND") || n.equals("TRIDENT") || n.contains("SHULKER") || n.equals("GOLDEN_APPLE") || n.contains("ENDER")) return Tier.EPIC;
        if (n.startsWith("IRON") || n.startsWith("GOLDEN") || n.contains("GOLD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("SHIELD")) return Tier.UNCOMMON;
        if (n.startsWith("CHAINMAIL")) return Tier.UNCOMMON;
        if (n.startsWith("WOODEN") || n.startsWith("LEATHER")) return Tier.COMMON;
        // Blocks: classify some special
        if (m == Material.NETHERITE_BLOCK || m == Material.BEACON || m == Material.ENCHANTING_TABLE || m == Material.BEDROCK || m == Material.BEACON || m ==  Material.ANCIENT_DEBRIS) return Tier.LEGENDARY;
        if (m == Material.DIAMOND_BLOCK ) return Tier.EPIC;
        if (m == Material.IRON_BLOCK || m == Material.ANVIL) return Tier.UNCOMMON;
        return Tier.GARBAGE;
    }

    public double priceFor(Tier tier) {
        return tierPrices.getOrDefault(tier, 100.0);
    }

    public List<ShopItem> allItemsByCategory(Category cat) {
        List<Material> list = categoryMaterials.getOrDefault(cat, Collections.emptyList());
        return list.stream().map(this::toShopItem).collect(Collectors.toList());
    }

    public List<Material> materialsForTier(Tier tier) {
        return tierMaterials.getOrDefault(tier, Collections.emptyList());
    }

    public List<ShopItem> rollDrop(Map<Tier, Integer> counts, Random rng) {
        List<ShopItem> result = new ArrayList<>();
        for (Map.Entry<Tier, Integer> e : counts.entrySet()) {
            Tier tier = e.getKey();
            int count = e.getValue();
            List<Material> pool = new ArrayList<>(materialsForTier(tier));
            // remove spawn eggs (already excluded) and avoid too large pools
            Collections.shuffle(pool, rng);
            for (int i = 0; i < count && i < pool.size(); i++) {
                Material m = pool.get(i);
                Category cat = categorize(m);
                double price = unitShopPrice(m);
                result.add(new ShopItem(m, tier, cat, price));
            }
        }
        Collections.shuffle(result, rng);
        return result;
    }

    public Category categorize(Material m) {
        String name = m.name();
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("ELYTRA")) return Category.ARMORS;
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT") || name.equals("SHIELD")) return Category.TOOLS_WEAPONS;
        if (name.endsWith("SPAWN_EGG")) return Category.SPAWN_EGGS;
        if (m.isBlock()) return Category.BLOCKS;
        return Category.GARBAGE;
    }

    public ShopItem toShopItem(Material m) {
        Category cat = categorize(m);
        if (cat == Category.SPAWN_EGGS) {
            double price = plugin.getConfig().getDouble("spawn_egg.price", 2500.0);
            return new ShopItem(m, Tier.COMMON, cat, price); // tier unused in display for eggs
        }
        if (cat == Category.LUCKY_BLOCKS) {
            double price = plugin.getConfig().getDouble("lucky_block.price", 500.0);
            return new ShopItem(m, Tier.EPIC, cat, price);
        }
        if (cat == Category.GARBAGE) {
            return new ShopItem(m, Tier.GARBAGE, cat, garbagePrice);
        }
        Tier t = tierFor(m);
        double price = unitShopPrice(m);
        return new ShopItem(m, t, cat, price);
    }

    // Compute the unit shop price for a material, including yield adjustments for multi-drop items
    public double unitShopPrice(Material m) {
        Category cat = categorize(m);
        if (cat == Category.SPAWN_EGGS) return plugin.getConfig().getDouble("spawn_egg.price", 2500.0);
        if (cat == Category.LUCKY_BLOCKS) return plugin.getConfig().getDouble("lucky_block.price", 500.0);
        if (cat == Category.GARBAGE) return garbagePrice;
        Tier t = tierFor(m);
        double base = priceFor(t);
        double factor = yieldFactors.getOrDefault(m.name().toUpperCase(Locale.ROOT), 1.0);
        double adjusted = Math.max(0.01, base * factor);
        return Math.round(adjusted * 100.0) / 100.0;
    }

    // --- Dynamic pricing ---
    private void loadDynamicParams() {
        var cfg = plugin.getConfig();
        this.shopIncreaseEveryNBuys = Math.max(1, cfg.getInt("pricing.dynamic.shop_increase_every_n_buys", 3));
        this.shopIncreaseFactor = Math.max(0.0, cfg.getDouble("pricing.dynamic.shop_increase_factor", 0.10));
        this.maxShopMultiplier = Math.max(1.0, cfg.getDouble("pricing.dynamic.max_shop_multiplier", 3.0));
        this.sellDecreasePerItem = Math.max(0.0, cfg.getDouble("pricing.dynamic.sell_decrease_per_item", 0.005));
        this.minSellMultiplier = Math.max(0.0, cfg.getDouble("pricing.dynamic.min_sell_multiplier", 0.25));
        this.shopDecreasePerItemOnSell = Math.max(0.0, cfg.getDouble("pricing.dynamic.shop_decrease_per_item_on_sell", 0.02));
        this.minShopMultiplier = Math.max(0.1, cfg.getDouble("pricing.dynamic.min_shop_multiplier", 0.8));
    }

    private double getShopMult(Material m) {
        return shopMultipliers.getOrDefault(m, 1.0);
    }

    private double getSellMult(Material m) {
        return sellMultipliers.getOrDefault(m, 1.0);
    }

    public double dynamicShopPrice(Material m) {
        double base = unitShopPrice(m);
        double mult = getShopMult(m);
        return Math.round(base * mult * 100.0) / 100.0;
    }

    public double dynamicSellUnitPrice(Material m) {
        double base = unitShopPrice(m);
        double mult = getSellMult(m);
        return Math.round(base * mult * 100.0) / 100.0;
    }

    public void recordPurchase(Material m) {
        int cnt = buysSinceAdjust.getOrDefault(m, 0) + 1;
        buysSinceAdjust.put(m, cnt);
        if (cnt % shopIncreaseEveryNBuys == 0) {
            double cur = getShopMult(m);
            double next = cur * (1.0 + shopIncreaseFactor);
            if (next > maxShopMultiplier) next = maxShopMultiplier;
            shopMultipliers.put(m, next);
        }
    }

    public void recordSale(Material m, int amount) {
        if (amount <= 0) return;
        // Reduce sell multiplier (legacy, used if sell price is based on sell multiplier)
        double curSell = getSellMult(m);
        double decSell = sellDecreasePerItem * amount;
        double nextSell = curSell * Math.max(0.0, 1.0 - decSell);
        if (nextSell < minSellMultiplier) nextSell = minSellMultiplier;
        sellMultipliers.put(m, nextSell);

        // Also reduce the shop (buy) multiplier to reflect market supply; affects buy prices and thus sell=0.45*buy
        double curShop = getShopMult(m);
        double decShop = shopDecreasePerItemOnSell * amount;
        double nextShop = curShop * Math.max(0.0, 1.0 - decShop);
        if (nextShop < minShopMultiplier) nextShop = minShopMultiplier;
        shopMultipliers.put(m, nextShop);
    }

    // Heuristic filter for useful items: include equipment and functional blocks, exclude misc/garbage
    private boolean isUseful(Material m) {
        String n = m.name();
        if (n.endsWith("SPAWN_EGG")) return false;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("ELYTRA")) return true;
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("SHIELD") || n.equals("FISHING_ROD") || n.equals("SHEARS")) return true;
        if (m.isBlock()) {
            // Whitelist functional blocks
            return switch (m) {
                case ENCHANTING_TABLE, ANVIL, GRINDSTONE, SMITHING_TABLE, CARTOGRAPHY_TABLE, STONECUTTER, LOOM,
                     FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, BEACON, ENDER_CHEST,
                     SHULKER_BOX, WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX, LIGHT_BLUE_SHULKER_BOX,
                     YELLOW_SHULKER_BOX, LIME_SHULKER_BOX, PINK_SHULKER_BOX, GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX,
                     CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX, BROWN_SHULKER_BOX, GREEN_SHULKER_BOX,
                     RED_SHULKER_BOX, BLACK_SHULKER_BOX, HOPPER, GOLDEN_APPLE, ENCHANTED_GOLDEN_APPLE  -> true;
                default -> false;
            };
        }
        return false;
    }
}

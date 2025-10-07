package com.bodia.shoptrader.sell;

import com.bodia.shoptrader.shop.Catalog;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages a rotating list of SELL offers (what the shop will buy from players).
 * - Generates 20 materials balanced by tier (common/uncommon/epic/legendary)
 * - Initial unit price = 0.7 * current dynamic shop unit price
 * - Each unit sold reduces the current unit price by step = 10% of initial price until price reaches 0, then disabled
 * - Regenerates every 2 hours
 */
public class SellRotationManager {
    public static class SellResult {
        public final Material material;
        public final int units;
        public final double payout;
        public final boolean success;
        public final Offer offerSnapshot;

        public SellResult(Material material, int units, double payout, boolean success, Offer snapshot) {
            this.material = material;
            this.units = units;
            this.payout = payout;
            this.success = success;
            this.offerSnapshot = snapshot;
        }
    }
    public static class Offer {
        public Material material;
        public double initialPrice;
        public double currentPrice;
        public double step;
        public boolean disabled;
        public int groupSize; // 8 or 16

        public Offer() {}
        public Offer(Material m, double init, double step, int groupSize) {
            this.material = m;
            this.initialPrice = round2(init);
            this.currentPrice = round2(init);
            this.step = round2(step);
            this.disabled = this.currentPrice <= 0.0;
            this.groupSize = groupSize;
        }
    }

    private final Plugin plugin;
    private final Catalog catalog;
    private final File dataFile;
    private YamlConfiguration data;
    private double baseMultiplier;

    private static final int OFFER_COUNT = 20;
    private static final long REGEN_SECONDS = 2L * 60L * 60L; // 2 hours

    private Instant lastGeneratedAt = Instant.EPOCH;
    private final List<Offer> offers = new ArrayList<>();
    private final Random rng = new Random();

    public SellRotationManager(Plugin plugin, Catalog catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.dataFile = new File(plugin.getDataFolder(), "sell.yml");
        if (!this.dataFile.getParentFile().exists()) this.dataFile.getParentFile().mkdirs();
        this.baseMultiplier = readBaseMultiplier();
        load();
        ensureActive();
    }

    public synchronized void ensureActive() {
        if (offers.isEmpty() || secondsUntilRegen() <= 0) regenerate();
    }

    public synchronized long secondsUntilRegen() {
        long elapsed = Duration.between(lastGeneratedAt, Instant.now()).getSeconds();
        long remain = REGEN_SECONDS - elapsed;
        return Math.max(0, remain);
    }

    public synchronized List<Offer> getOffers() {
        return new ArrayList<>(offers);
    }

    public synchronized SellResult transactSell(Material m, int amount) {
        if (amount <= 0) return new SellResult(m, 0, 0.0, false, null);
        for (Offer o : offers) {
            if (o.material == m && !o.disabled) {
                int possible = maxSellableUnits(o);
                int units = Math.min(amount, possible);
                if (units <= 0) return new SellResult(m, 0, 0.0, false, cloneOffer(o));
                double last = o.currentPrice - (units - 1) * o.step;
                double payout = units * (o.currentPrice + Math.max(0, last)) / 2.0;
                o.currentPrice = round2(o.currentPrice - units * o.step);
                if (o.currentPrice <= 0.0) {
                    o.currentPrice = 0.0;
                    o.disabled = true;
                }
                save();
                catalog.recordSale(m, units);
                return new SellResult(m, units, round2(payout), true, cloneOffer(o));
            }
        }
        return new SellResult(m, 0, 0.0, false, null);
    }

    public synchronized boolean sell(Material m, int amount) {
        return transactSell(m, amount).success;
    }

    public synchronized double previewPayout(Material m, int amount) {
        for (Offer o : offers) {
            if (o.material == m && !o.disabled) {
                int possible = maxSellableUnits(o);
                int units = Math.min(amount, possible);
                if (units <= 0) return 0.0;
                double last = o.currentPrice - (units - 1) * o.step;
                double payout = units * (o.currentPrice + Math.max(0, last)) / 2.0;
                return round2(payout);
            }
        }
        return 0.0;
    }

    public synchronized int maxSellableUnits(Material m) {
        for (Offer o : offers) {
            if (o.material == m) return maxSellableUnits(o);
        }
        return 0;
    }

    public synchronized Offer getOffer(Material m) {
        for (Offer o : offers) {
            if (o.material == m) return cloneOffer(o);
        }
        return null;
    }

    public synchronized int maxSellableUnits(Offer o) {
        if (o.disabled || o.step <= 0.0) return 0;
        int n = (int) Math.floor(o.currentPrice / o.step);
        return Math.max(0, n == 0 ? (o.currentPrice > 0 ? 1 : 0) : n);
    }

    public synchronized void tick() {
        if (secondsUntilRegen() <= 0) regenerate();
    }

    private synchronized void regenerate() {
        offers.clear();
        Set<Material> picked = new HashSet<>();

        // Build pools from all materials to have full control over categories
        List<Material> weapons = new ArrayList<>();
        List<Material> tools = new ArrayList<>();
        List<Material> blocks = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        List<Material> miscellany = new ArrayList<>();

        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            if (disallowedHard(m)) continue;
            String n = m.name();
            boolean isBlock = m.isBlock();
            boolean isWeapon = n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT");
            boolean isTool = n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS") || n.equals("FISHING_ROD");
            boolean isArmor = n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("ELYTRA") || n.startsWith("CHAINMAIL");
            boolean stackable = m.getMaxStackSize() > 1;

            if (isWeapon) {
                weapons.add(m);
            } else if (isTool) {
                tools.add(m);
            } else if (isBlock) {
                blocks.add(m);
            } else if (stackable && isMaterialItem(n)) {
                materials.add(m);
            } else if (stackable && !isArmor) {
                miscellany.add(m);
            }
        }

        Collections.shuffle(weapons, rng);
        Collections.shuffle(tools, rng);
        Collections.shuffle(blocks, rng);
        Collections.shuffle(materials, rng);
        Collections.shuffle(miscellany, rng);

        pickMany(picked, weapons, 2);
        pickMany(picked, tools, 3);
        pickMany(picked, blocks, 5);
        pickMany(picked, materials, 5);
        pickMany(picked, miscellany, 5);

        // Fallback: if any category lacked items, fill to 20 from blocks/materials/misc pools
        List<Material> filler = new ArrayList<>();
        filler.addAll(blocks); filler.addAll(materials); filler.addAll(miscellany);
        Collections.shuffle(filler, rng);
        for (Material m : filler) {
            if (picked.size() >= OFFER_COUNT) break;
            if (!picked.contains(m)) picked.add(m);
        }

        for (Material m : picked) {
            double base = catalog.dynamicShopPrice(m);
            // Rarer => higher price
            com.bodia.shoptrader.model.Tier tier = catalog.tierFor(m);
            double init = round2(base * baseMultiplier * tierSellMultiplier(tier));
            double step = round2(init * 0.10); // each sale reduces by 10% of initial price
            // Rarer => smaller group size (non-stackables always 1)
            int group = groupSizeFor(m, tier);
            offers.add(new Offer(m, init, step, group));
        }
        lastGeneratedAt = Instant.now();
        save();
    }

    public synchronized List<String> forceRegenerate() {
        regenerate();
        List<String> out = new ArrayList<>();
        for (Offer o : offers) out.add(o.material.name());
        return out;
    }

    private boolean disallowedForSell(Material m) {
        String n = m.name();
        if (n.endsWith("SPAWN_EGG")) return true;
        if (n.contains("SHULKER_BOX")) return true;
        if (n.contains("ORE")) return true; // ores as blocks; players sell ingots instead
        if (n.equals("BEACON") || n.equals("BEDROCK")) return true;
        return false;
    }

    // Hard blacklist used during pool building as well
    private boolean disallowedHard(Material m) {
        String n = m.name();
        if (n.endsWith("SPAWN_EGG")) return true;
        if (n.contains("SHULKER_BOX")) return true;
        if (n.contains("ORE")) return true; // ores as blocks; players sell ingots instead
        if (n.equals("BEACON") || n.equals("BEDROCK")) return true;
        return false;
    }

    private boolean isMaterialItem(String n) {
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.endsWith("_DUST") || n.endsWith("_SHARD") || n.endsWith("_CRYSTALS") || n.endsWith("_SHARD") || n.endsWith("_PEARL") || n.endsWith("_BALL") || n.endsWith("_POWDER")) return true;
        return n.equals("REDSTONE") || n.equals("LAPIS_LAZULI") || n.equals("QUARTZ") || n.equals("PRISMARINE_SHARD") || n.equals("PRISMARINE_CRYSTALS") || n.equals("DIAMOND") || n.equals("EMERALD") || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("AMETHYST_SHARD") || n.equals("GLOWSTONE_DUST");
    }

    private void pickMany(Set<Material> out, List<Material> pool, int count) {
        for (Material m : pool) {
            if (out.size() >= OFFER_COUNT) break;
            if (out.contains(m)) continue;
            out.add(m);
            if (--count <= 0) break;
        }
    }

    private double tierSellMultiplier(com.bodia.shoptrader.model.Tier tier) {
        if (tier == null) return 1.0;
        return switch (tier) {
            case COMMON -> 1.0;
            case UNCOMMON -> 1.15;
            case EPIC -> 1.5;
            case LEGENDARY -> 2.0;
        };
    }

    private int groupSizeFor(Material m, com.bodia.shoptrader.model.Tier tier) {
        if (m.getMaxStackSize() <= 1) return 1; // non-stackables
        if (tier == null) return 8;
        return switch (tier) {
            case COMMON -> 16;
            case UNCOMMON -> 12;
            case EPIC -> 8;
            case LEGENDARY -> 4;
        };
    }

    private void load() {
        data = new YamlConfiguration();
        if (dataFile.exists()) {
            try { data.load(dataFile); } catch (Exception ignored) {}
        }
        long ts = data.getLong("lastGeneratedAt", 0L);
        if (ts > 0L) lastGeneratedAt = Instant.ofEpochSecond(ts);
        offers.clear();
        List<Map<String, Object>> raw = (List<Map<String, Object>>) data.getList("offers", Collections.emptyList());
        for (Map<String, Object> m : raw) {
            try {
                Material mat = Material.valueOf((String) m.get("material"));
                double init = ((Number) m.get("initialPrice")).doubleValue();
                double curr = ((Number) m.get("currentPrice")).doubleValue();
                double step = ((Number) m.get("step")).doubleValue();
                boolean disabled = (Boolean) m.getOrDefault("disabled", false);
                Offer o = new Offer();
                o.material = mat; o.initialPrice = init; o.currentPrice = curr; o.step = step; o.disabled = disabled; o.groupSize = ((Number) m.getOrDefault("groupSize", 8)).intValue();
                offers.add(o);
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        data.set("lastGeneratedAt", lastGeneratedAt.getEpochSecond());
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Offer o : offers) {
            Map<String, Object> m = new HashMap<>();
            m.put("material", o.material.name());
            m.put("initialPrice", o.initialPrice);
            m.put("currentPrice", o.currentPrice);
            m.put("step", o.step);
            m.put("disabled", o.disabled);
            m.put("groupSize", o.groupSize);
            raw.add(m);
        }
        data.set("offers", raw);
        try { data.save(dataFile); } catch (IOException ignored) {}
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private Offer cloneOffer(Offer o) {
        Offer c = new Offer();
        c.material = o.material;
        c.initialPrice = o.initialPrice;
        c.currentPrice = o.currentPrice;
        c.step = o.step;
        c.disabled = o.disabled;
        c.groupSize = o.groupSize;
        return c;
    }

    private double readBaseMultiplier() {
        try {
            return plugin.getConfig().getDouble("sell.base_multiplier", 0.7);
        } catch (Exception e) {
            return 0.7;
        }
    }

    public synchronized void reloadConfig() {
        double old = this.baseMultiplier;
        double now = readBaseMultiplier();
        this.baseMultiplier = now;
        if (offers.isEmpty()) return;
        if (old <= 0) return; // avoid divide by zero; skip scaling
        double ratio = now / old;
        if (Math.abs(ratio - 1.0) < 1e-9) return;
        for (Offer o : offers) {
            o.initialPrice = round2(o.initialPrice * ratio);
            o.currentPrice = round2(o.currentPrice * ratio);
            o.step = round2(o.step * ratio);
            if (o.currentPrice <= 0.0) {
                o.currentPrice = 0.0;
                o.disabled = true;
            }
        }
        save();
    }
}

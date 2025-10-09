package com.bodia.shoptrader.quests;

import com.bodia.shoptrader.economy.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.time.*;

public class QuestManager {
    private final JavaPlugin plugin;
    private final EconomyService economy;

    // Pool of all possible quests to choose from
    private final Map<String, QuestDef> pool = new LinkedHashMap<>();
    // Currently active quests for the day (6)
    private final Map<String, QuestDef> quests = new LinkedHashMap<>();

    private File dataFile;
    private YamlConfiguration dataCfg;

    public QuestManager(JavaPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.dataFile = new File(plugin.getDataFolder(), "quests.yml");
        if (!this.dataFile.getParentFile().exists()) this.dataFile.getParentFile().mkdirs();
        loadData();
        definePool();
        // Load today's active quests or generate if missing/stale
        ensureDailyQuests();
    }

    private void definePool() {
        pool.clear();
        // Fetch pool
        poolAdd(new QuestDef("fetch_logs", "Поставка деревини: 32 дубових колод", QuestDef.Kind.FETCH, Material.OAK_LOG, null, 32, 450.0));
        // Birch is explicitly excluded per requirements
        poolAdd(new QuestDef("fetch_iron", "Промислова поставка: 16 залізних злитків", QuestDef.Kind.FETCH, Material.IRON_INGOT, null, 16, 900.0));
        poolAdd(new QuestDef("fetch_coal", "Паливо коваля: 64 вугілля", QuestDef.Kind.FETCH, Material.COAL, null, 64, 800.0));
        poolAdd(new QuestDef("fetch_wheat", "Пекарський аврал: 64 пшениці", QuestDef.Kind.FETCH, Material.WHEAT, null, 64, 600.0));
        // Replacements for former collect quests as fetch deliveries
        poolAdd(new QuestDef("fetch_stone", "Поставка каменю: 64 каменю", QuestDef.Kind.FETCH, Material.STONE, null, 64, 550.0));
        poolAdd(new QuestDef("fetch_deepslate", "Поставка глибосланцю: 48 глибосланцю", QuestDef.Kind.FETCH, Material.DEEPSLATE, null, 48, 650.0));
        // Kill pool
        poolAdd(new QuestDef("kill_skeletons", "Мисливець на кістяків: 10 скелетів", QuestDef.Kind.KILL, null, EntityType.SKELETON, 10, 750.0));
        poolAdd(new QuestDef("kill_zombies", "Нічна варта: 15 зомбі", QuestDef.Kind.KILL, null, EntityType.ZOMBIE, 15, 650.0));
        poolAdd(new QuestDef("kill_creepers", "Бригада підривників: 7 кріперів", QuestDef.Kind.KILL, null, EntityType.CREEPER, 7, 800.0));
        poolAdd(new QuestDef("kill_endermen", "Ендер-мисливець: 3 ендермени", QuestDef.Kind.KILL, null, EntityType.ENDERMAN, 3, 1200.0));
        // Do pool (fish only)
        poolAdd(new QuestDef("fish_catches", "Рибалка: спіймай 10 риб", QuestDef.Kind.FISH, null, null, 10, 800.0));
        poolAdd(new QuestDef("fish_master", "Майстер рибалка: спіймай 15 риб", QuestDef.Kind.FISH, null, null, 15, 1000.0));
    }

    private void poolAdd(QuestDef def) { pool.put(def.getId(), def); }

    private void setActiveByIds(List<String> ids) {
        quests.clear();
        for (String id : ids) {
            QuestDef def = pool.get(id);
            if (def != null) quests.put(id, def);
        }
    }

    public boolean ensureDailyQuests() {
        // Returns true if active set changed (e.g., regenerated for a new day)
        String today = todayKey();
        String cur = dataCfg.getString("daily.date", null);
        List<String> ids = dataCfg.getStringList("daily.ids");
        if (today.equals(cur) && ids != null && !ids.isEmpty()) {
            if (quests.isEmpty()) setActiveByIds(ids);
            // Ensure rewards exist for the loaded set
            assignDailyRewards(ids);
            return false;
        }
        // Need to generate a fresh set for today
        List<String> newIds = generateIds();
        dataCfg.set("daily.date", today);
        dataCfg.set("daily.ids", newIds);
        setActiveByIds(newIds);
        assignDailyRewards(newIds);
        saveData();
        // Reset all players' quest progress/completions/claims for new set
        dataCfg.set("players", null);
        saveData();
        return true;
    }

    private List<String> generateIds() {
        // Pick 7 quests with a balanced distribution if possible: 3 FETCH, 2 KILL, 2 DO (FISH)
        List<String> fetch = new ArrayList<>();
        List<String> kill = new ArrayList<>();
        List<String> doq = new ArrayList<>();
        for (QuestDef q : pool.values()) {
            switch (q.getKind()) {
                case FETCH -> fetch.add(q.getId());
                case KILL -> kill.add(q.getId());
                case MINE, FISH -> doq.add(q.getId());
            }
        }
        Collections.shuffle(fetch);
        Collections.shuffle(kill);
        Collections.shuffle(doq);
        List<String> out = new ArrayList<>();
        addSome(out, fetch, 3);
        addSome(out, kill, 2);
        addSome(out, doq, 2);
        // If pool is small, fill remaining from any
        if (out.size() < 7) {
            List<String> all = new ArrayList<>(pool.keySet());
            Collections.shuffle(all);
            for (String id : all) {
                if (out.size() >= 7) break;
                if (!out.contains(id)) out.add(id);
            }
        }
        return out.subList(0, Math.min(7, out.size()));
    }

    private void addSome(List<String> out, List<String> src, int n) {
        for (int i = 0; i < n && i < src.size(); i++) out.add(src.get(i));
    }

    public Collection<QuestDef> getAll() { return quests.values(); }
    public QuestDef get(String id) { return quests.get(id); }

    // Force regenerate a new set immediately (admin command). Returns the selected IDs.
    public List<String> forceRegenerate() {
        List<String> ids = generateIds();
        dataCfg.set("daily.date", todayKey());
        dataCfg.set("daily.ids", ids);
        setActiveByIds(ids);
        // Reset all players' data because quest set changed
        dataCfg.set("players", null);
        saveData();
        return ids;
    }

    private void loadData() {
        dataCfg = new YamlConfiguration();
        if (dataFile.exists()) {
            try { dataCfg.load(dataFile); } catch (Exception e) { plugin.getLogger().warning("Failed to load quests.yml: " + e.getMessage()); }
        }
    }

    public void saveData() {
        try { dataCfg.save(dataFile); } catch (IOException e) { plugin.getLogger().warning("Failed to save quests.yml: " + e.getMessage()); }
    }

    private String basePath(UUID uuid) { return "players." + uuid.toString(); }

    // --- Daily reset (Ukraine time) ---
    public ZoneId getUkraineZone() {
        try {
            return ZoneId.of("Europe/Kyiv");
        } catch (Exception e) {
            return ZoneId.of("Europe/Kiev");
        }
    }

    private String todayKey() {
        LocalDate today = LocalDate.now(getUkraineZone());
        return today.toString();
    }

    public void ensureDailySynced(Player p) {
        ensureDailySynced(p.getUniqueId());
    }

    public void ensureDailySynced(UUID uuid) {
        String path = basePath(uuid) + ".date";
        String cur = dataCfg.getString(path, null);
        String today = todayKey();
        if (!today.equals(cur)) {
            // Reset this player's quest progress
            dataCfg.set(basePath(uuid) + ".progress", null);
            dataCfg.set(basePath(uuid) + ".completed", null);
            dataCfg.set(basePath(uuid) + ".claimed", null);
            dataCfg.set(path, today);
            saveData();
        }
    }

    public long secondsUntilNextReset() {
        ZoneId zone = getUkraineZone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(zone);
        return Duration.between(now, next).getSeconds();
    }

    public int getProgress(UUID uuid, String questId) {
        ensureDailySynced(uuid);
        return dataCfg.getInt(basePath(uuid) + ".progress." + questId, 0);
    }

    public void setProgress(UUID uuid, String questId, int value) {
        dataCfg.set(basePath(uuid) + ".progress." + questId, value);
    }

    public boolean isCompleted(UUID uuid, String questId) {
        ensureDailySynced(uuid);
        return dataCfg.getBoolean(basePath(uuid) + ".completed." + questId, false);
    }

    public void setCompleted(UUID uuid, String questId, boolean val) {
        dataCfg.set(basePath(uuid) + ".completed." + questId, val);
    }

    public boolean isClaimed(UUID uuid, String questId) {
        ensureDailySynced(uuid);
        return dataCfg.getBoolean(basePath(uuid) + ".claimed." + questId, false);
    }

    public void setClaimed(UUID uuid, String questId, boolean val) {
        dataCfg.set(basePath(uuid) + ".claimed." + questId, val);
    }

    public void addProgress(Player p, String questId, int delta) {
        QuestDef def = quests.get(questId);
        if (def == null) return;
        UUID u = p.getUniqueId();
        if (isClaimed(u, questId)) return; // already done
        int cur = getProgress(u, questId);
        int next = cur + Math.max(0, delta);
        if (next > def.getRequired()) next = def.getRequired();
        setProgress(u, questId, next);
        // complete check
        if (!isCompleted(u, questId) && next >= def.getRequired()) {
            setCompleted(u, questId, true);
            saveData();
            p.sendMessage(ChatColor.GREEN + "Квест готовий до отримання: " + ChatColor.YELLOW + def.getName() + ChatColor.GREEN + ". Зайдіть до трейдера щоб забрати нагороду.");
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    public boolean deliverFetch(Player p, String questId) {
        QuestDef def = quests.get(questId);
        if (def == null || def.getKind() != QuestDef.Kind.FETCH) return false;
        UUID u = p.getUniqueId();
        if (isClaimed(u, questId)) {
            p.sendMessage(ChatColor.YELLOW + "Ви вже отримали нагороду за цей квест.");
            return true;
        }
        if (isCompleted(u, questId)) {
            p.sendMessage(ChatColor.GREEN + "Доставку вже виконано. Використайте /trader claim " + questId + " щоб отримати.");
            return true;
        }
        int have = countInInventory(p, def.getTargetMaterial());
        if (have < def.getRequired()) {
            p.sendMessage(ChatColor.RED + "Недостатньо предметів. Потрібно " + def.getRequired() + "x " + def.getTargetMaterial().name());
            return false;
        }
        removeFromInventory(p, def.getTargetMaterial(), def.getRequired());
        setProgress(u, questId, def.getRequired());
        setCompleted(u, questId, true);
        saveData();
        // Suppress verbose delivery message; GUI handles ready/claim flow
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_YES, 1f, 1.1f);
        return true;
    }

    public int claim(Player p, String questId) {
        QuestDef def = quests.get(questId);
        if (def == null) return 0;
        UUID u = p.getUniqueId();
        if (isClaimed(u, questId)) return 0;
        if (!isCompleted(u, questId)) return -1; // not yet ready
        if (!economy.isEnabled()) {
            p.sendMessage(ChatColor.RED + "Економіка недоступна. Встановіть Vault.");
            return 0;
        }
        double reward = getDailyReward(questId);
        boolean ok = economy.deposit(p, reward);
        if (ok) {
            setClaimed(u, questId, true);
            saveData();
            p.sendMessage(ChatColor.GREEN + "Отримано нагороду: " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", reward) + ChatColor.GREEN + " за \"" + def.getName() + "\"");
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.0f);
            return 1;
        } else {
            p.sendMessage(ChatColor.RED + "Транзакція не вдалася.");
            return 0;
        }
    }

    public int claimAll(Player p) {
        int count = 0;
        for (QuestDef def : quests.values()) {
            UUID u = p.getUniqueId();
            if (isCompleted(u, def.getId()) && !isClaimed(u, def.getId())) {
                int res = claim(p, def.getId());
                if (res > 0) count += res;
            }
        }
        if (count == 0) p.sendMessage(ChatColor.YELLOW + "Немає винагород для отримання.");
        return count;
    }

    public void showQuests(Player p) {
        p.sendMessage(ChatColor.DARK_AQUA + "-- Квести --");
        for (QuestDef def : quests.values()) {
            int prog = getProgress(p.getUniqueId(), def.getId());
            String status;
            if (isClaimed(p.getUniqueId(), def.getId())) status = ChatColor.DARK_GREEN + "[ОТРИМАНО]";
            else if (isCompleted(p.getUniqueId(), def.getId())) status = ChatColor.GREEN + "[ГОТОВО]";
            else status = ChatColor.YELLOW + "[" + prog + "/" + def.getRequired() + "]";
            double dailyReward = getDailyReward(def.getId());
            p.sendMessage(" " + status + ChatColor.GRAY + " | " + ChatColor.AQUA + def.getId() + ChatColor.GRAY + ": " + ChatColor.WHITE + def.getName() + ChatColor.GRAY + " | Нагорода: " + ChatColor.GOLD + String.format(Locale.ROOT, "%.2f", dailyReward));
        }
        p.sendMessage(ChatColor.GRAY + "Використовуйте /trader deliver <id> для квестів на доставку та /trader claim <id> щоб отримати нагороду (або /trader claimall).");
    }

    // --- Daily rewards (unique in [100,300]) ---
    private void assignDailyRewards(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        Map<String, Object> existing = dataCfg.getConfigurationSection("daily.rewards") != null ? dataCfg.getConfigurationSection("daily.rewards").getValues(false) : Collections.emptyMap();
        // If all ids already present and unique within range, keep
        boolean ok = true;
        Set<Double> seen = new HashSet<>();
        for (String id : ids) {
            double v = dataCfg.getDouble("daily.rewards." + id, -1.0);
            if (v < 100.0 || v > 300.0 || seen.contains(v)) { ok = false; break; }
            seen.add(v);
        }
        if (ok && existing.size() >= ids.size()) return;

        // Generate unique integer rewards within [100,300]
        List<Integer> poolVals = new ArrayList<>();
        for (int i = 100; i <= 300; i++) poolVals.add(i);
        Collections.shuffle(poolVals);
        Iterator<Integer> it = poolVals.iterator();
        for (String id : ids) {
            if (!it.hasNext()) break;
            int val = it.next();
            dataCfg.set("daily.rewards." + id, (double) val);
        }
        saveData();
    }

    public double getDailyReward(String questId) {
        double v = dataCfg.getDouble("daily.rewards." + questId, Double.NaN);
        if (!Double.isNaN(v) && v >= 100.0 && v <= 300.0) return v;
        QuestDef def = quests.get(questId);
        double fallback = def != null ? def.getRewardMoney() : 100.0;
        if (fallback < 100.0) fallback = 100.0;
        if (fallback > 300.0) fallback = 300.0;
        return fallback;
    }

    private int countInInventory(Player p, Material m) {
        int total = 0;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != m) continue;
            total += it.getAmount();
        }
        return total;
    }

    private int removeFromInventory(Player p, Material m, int amount) {
        int toRemove = amount;
        org.bukkit.inventory.ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            org.bukkit.inventory.ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            int take = Math.min(it.getAmount(), toRemove);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) contents[i] = null;
            toRemove -= take;
        }
        p.getInventory().setContents(contents);
        return amount - toRemove;
    }
}

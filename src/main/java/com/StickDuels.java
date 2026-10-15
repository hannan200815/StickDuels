package com.stickduels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class StickDuels extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final long REQUEST_TIMEOUT_MS = 60_000L;
    private static final long VIEW_TIMEOUT_MS = 600_000L;
    private static final long WARN_COOLDOWN_MS = 2_000L;
    private static final int COUNTDOWN_SECONDS = 5;
    private static final double ARENA_RADIUS = 80.0;     // lock area for non-duelists
    private static final double BARRIER_RADIUS = 28.0;   // the white pillar line
    private static final int CONTENTS_SIZE = 41;
    private static final int ARENA_R = 55;
    private static final int SKY_Y = 200;
    private static final int ARENA_SPACING = 260;
    private static final int ARENA_SPAWN_OFFSET = 22;
    private static final int BLOCKS_PER_TICK = 5000;
    private static final int MAX_Y_DELTA = 60;

    // ---------- Data ----------

    private static final class Arena {
        String name;
        Location spawn1;
        Location spawn2;
        Location centre;     // world, x, floor level y, z
        boolean sky;
        boolean generated;   // generated arenas can be rebuilt and deleted by the plugin
        boolean building;

        boolean complete() {
            return spawn1 != null && spawn2 != null && centre != null;
        }
    }

    private static final class Request {
        final UUID a;
        final UUID b;
        final String kit;
        final long expires;
        boolean aAccepted;
        boolean bAccepted;

        Request(UUID a, UUID b, String kit, long expires) {
            this.a = a;
            this.b = b;
            this.kit = kit;
            this.expires = expires;
        }

        boolean involves(UUID id) {
            return a.equals(id) || b.equals(id);
        }
    }

    private static final class Duel {
        final UUID a;
        final UUID b;
        final String kit;
        final Arena arena;
        final Map<UUID, Snapshot> saved = new HashMap<>();
        boolean started;

        Duel(UUID a, UUID b, String kit, Arena arena) {
            this.a = a;
            this.b = b;
            this.kit = kit;
            this.arena = arena;
        }

        UUID opponentOf(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    private record Snapshot(ItemStack[] contents, Location origin) {}

    private record ViewData(String ownerName, ItemStack[] items, long expires) {}

    /** One block to place. Blocks are queued, then placed in batches so the server doesn't freeze. */
    private record Op(World w, int x, int y, int z, Material m, BlockFace facing) {}

    /** Marks the read-only inventory view so clicks can be cancelled. */
    private static final class ViewHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<String, ItemStack[]> kits = new LinkedHashMap<>();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<Duel> duels = new ArrayList<>();
    private final Map<UUID, Duel> duelOf = new HashMap<>();
    private final Map<UUID, Snapshot> respawnRestore = new HashMap<>();
    private final Set<UUID> lockBypass = new HashSet<>();
    private final Map<UUID, ViewData> viewable = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private List<Op> buildQueue = new ArrayList<>();
    private Location lobby;

    private File kitFile;
    private File arenaFile;
    private File restoreFile;
    private YamlConfiguration restores;

    // ---------- Setup ----------

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        kitFile = new File(getDataFolder(), "kits.yml");
        arenaFile = new File(getDataFolder(), "arenas.yml");
        restoreFile = new File(getDataFolder(), "restores.yml");
        loadKits();
        loadArenas();
        restores = YamlConfiguration.loadConfiguration(restoreFile);
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("adminduelrequest", "duelaccept", "dueldeny", "adminduel", "duelview")) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
                getCommand(name).setTabCompleter(this);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Duel d : new ArrayList<>(duels)) endDuel(d, null, null);
    }

    private void loadKits() {
        kits.clear();
        YamlConfiguration k = YamlConfiguration.loadConfiguration(kitFile);
        for (String name : k.getKeys(false)) {
            kits.put(name, toContents(k.getList(name + ".contents", List.of())));
        }
        if (kits.isEmpty()) {
            kits.putAll(defaultKits());
            saveKits();
        }
    }

    private void saveKits() {
        YamlConfiguration k = new YamlConfiguration();
        for (Map.Entry<String, ItemStack[]> en : kits.entrySet()) {
            k.set(en.getKey() + ".contents", Arrays.asList(en.getValue()));
        }
        try {
            k.save(kitFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save kits.yml", ex);
        }
    }

    private void loadArenas() {
        arenas.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arenaFile);
        for (String name : y.getKeys(false)) {
            if (name.equals("lobby")) {
                lobby = y.getLocation("lobby");
                continue;
            }
            Arena a = new Arena();
            a.name = name;
            a.spawn1 = y.getLocation(name + ".spawn1");
            a.spawn2 = y.getLocation(name + ".spawn2");
            a.centre = y.getLocation(name + ".centre");
            a.sky = y.getBoolean(name + ".sky", false);
            a.generated = y.getBoolean(name + ".generated", false);
            if (a.centre == null && a.spawn1 != null && a.spawn2 != null) refreshCentre(a);
            arenas.put(name, a);
        }
    }

    private void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        if (lobby != null) y.set("lobby", lobby);
        for (Arena a : arenas.values()) {
            if (a.spawn1 != null) y.set(a.name + ".spawn1", a.spawn1);
            if (a.spawn2 != null) y.set(a.name + ".spawn2", a.spawn2);
            if (a.centre != null) y.set(a.name + ".centre", a.centre);
            y.set(a.name + ".sky", a.sky);
            y.set(a.name + ".generated", a.generated);
        }
        try {
            y.save(arenaFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save arenas.yml", ex);
        }
    }

    private void saveRestores() {
        try {
            restores.save(restoreFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save restores.yml", ex);
        }
    }

    /** Manual arenas: the centre is the midpoint of the two spawns. */
    private void refreshCentre(Arena a) {
        if (a.centre != null || a.spawn1 == null || a.spawn2 == null) return;
        a.centre = new Location(a.spawn1.getWorld(),
                (a.spawn1.getX() + a.spawn2.getX()) / 2.0,
                a.spawn1.getY() - 1,
                (a.spawn1.getZ() + a.spawn2.getZ()) / 2.0);
    }

    private static ItemStack[] toContents(List<?> raw) {
        ItemStack[] c = new ItemStack[CONTENTS_SIZE];
        for (int i = 0; i < Math.min(CONTENTS_SIZE, raw.size()); i++) {
            Object o = raw.get(i);
            if (o instanceof ItemStack is) c[i] = is;
        }
        return c;
    }

    // ---------- Kits ----------

    /** Gear with enchantments, given as pairs: Enchantment, level. Unsafe levels are allowed on purpose. */
    private static ItemStack gear(Material m, Object... pairs) {
        ItemStack it = new ItemStack(m);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            it.addUnsafeEnchantment((Enchantment) pairs[i], (Integer) pairs[i + 1]);
        }
        return it;
    }

    private static ItemStack armor(Material m) {
        return gear(m, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
    }

    private static ItemStack[] netherite() {
        return new ItemStack[]{armor(Material.NETHERITE_BOOTS), armor(Material.NETHERITE_LEGGINGS),
                armor(Material.NETHERITE_CHESTPLATE), armor(Material.NETHERITE_HELMET)};
    }

    private static ItemStack[] diamond() {
        return new ItemStack[]{armor(Material.DIAMOND_BOOTS), armor(Material.DIAMOND_LEGGINGS),
                armor(Material.DIAMOND_CHESTPLATE), armor(Material.DIAMOND_HELMET)};
    }

    /** Builds a 41-slot kit: hotbar and main inventory, then boots, leggings, chest, helmet, offhand. */
    private static ItemStack[] kit(List<ItemStack> items, ItemStack[] armorPieces, ItemStack offhand) {
        ItemStack[] c = new ItemStack[CONTENTS_SIZE];
        for (int i = 0; i < items.size() && i < 36; i++) c[i] = items.get(i);
        if (armorPieces != null) {
            for (int i = 0; i < 4; i++) c[36 + i] = armorPieces[i];
        }
        c[40] = offhand;
        return c;
    }

    private Map<String, ItemStack[]> defaultKits() {
        Material spearMat = Material.matchMaterial("NETHERITE_SPEAR");
        if (spearMat == null) spearMat = Material.NETHERITE_SWORD;
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING);

        Map<String, ItemStack[]> m = new LinkedHashMap<>();
        m.put("Sword", kit(List.of(
                gear(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.ENDER_PEARL, 4)), netherite(), shield));
        m.put("Axe", kit(List.of(
                gear(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.ENDER_PEARL, 2)), netherite(), shield));
        m.put("Mace", kit(List.of(
                gear(Material.MACE, Enchantment.DENSITY, 5, Enchantment.BREACH, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8),
                new ItemStack(Material.ENDER_PEARL, 2)), netherite(), shield));
        m.put("Spear", kit(List.of(
                gear(spearMat, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8)), netherite(), totem));
        m.put("Archer", kit(List.of(
                gear(Material.BOW, Enchantment.POWER, 5, Enchantment.INFINITY, 1, Enchantment.PUNCH, 2, Enchantment.UNBREAKING, 3),
                gear(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4, Enchantment.UNBREAKING, 3),
                new ItemStack(Material.ARROW, 1),
                new ItemStack(Material.GOLDEN_APPLE, 8)), diamond(), null));
        m.put("Sumo", kit(List.of(
                gear(Material.STICK, Enchantment.KNOCKBACK, 3)), null, null));
        return m;
    }

    private String matchKit(String name) {
        for (String k : kits.keySet()) if (k.equalsIgnoreCase(name)) return k;
        return null;
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "adminduelrequest" -> adminRequest(sender, args);
            case "duelaccept" -> accept(sender);
            case "dueldeny" -> deny(sender);
            case "adminduel" -> adminDuel(sender, args);
            case "duelview" -> openView(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean isAdmin(CommandSender s) {
        return s.isOp() || s.hasPermission("stickduels.admin");
    }

    private void adminRequest(CommandSender s, String[] args) {
        if (!isAdmin(s)) {
            s.sendMessage(ChatColor.RED + "Operators only.");
            return;
        }
        if (args.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage: /adminduelrequest <player> <player2> [kit]");
            return;
        }
        Player a = Bukkit.getPlayerExact(args[0]);
        Player b = Bukkit.getPlayerExact(args[1]);
        if (a == null || b == null) {
            s.sendMessage(ChatColor.RED + "Both players must be online.");
            return;
        }
        if (a.equals(b)) {
            s.sendMessage(ChatColor.RED + "Pick two different players.");
            return;
        }
        String kit = args.length >= 3 ? matchKit(args[2]) : (kits.isEmpty() ? null : kits.keySet().iterator().next());
        if (kit == null) {
            s.sendMessage(ChatColor.RED + "Unknown kit. Use /adminduel listkits.");
            return;
        }
        if (busy(a.getUniqueId()) || busy(b.getUniqueId())) {
            s.sendMessage(ChatColor.RED + "One of them is already in a duel or has a pending request.");
            return;
        }
        if (freeArena() == null) {
            s.sendMessage(ChatColor.RED + "No arena is ready. Build one with /adminduel arena generate, or set spawns with setspawn1 and setspawn2.");
            return;
        }
        requests.add(new Request(a.getUniqueId(), b.getUniqueId(), kit, System.currentTimeMillis() + REQUEST_TIMEOUT_MS));
        String msg = ChatColor.GOLD + "Duel request with kit " + ChatColor.WHITE + kit + ChatColor.GOLD + ". Type "
                + ChatColor.WHITE + "/duelaccept" + ChatColor.GOLD + " or " + ChatColor.WHITE + "/dueldeny"
                + ChatColor.GOLD + ". You have 60 seconds.";
        a.sendMessage(msg);
        b.sendMessage(msg);
        s.sendMessage(ChatColor.GREEN + "Duel request sent to " + a.getName() + " and " + b.getName() + ".");
    }

    private void accept(CommandSender s) {
        if (!(s instanceof Player p)) return;
        expireRequests();
        Request r = findRequest(p.getUniqueId());
        if (r == null) {
            p.sendMessage(ChatColor.RED + "You have no duel request.");
            return;
        }
        if (r.a.equals(p.getUniqueId())) r.aAccepted = true;
        else r.bAccepted = true;
        if (!(r.aAccepted && r.bAccepted)) {
            p.sendMessage(ChatColor.GRAY + "Accepted. Waiting for the other player.");
            return;
        }
        requests.remove(r);
        startDuel(r);
    }

    private void deny(CommandSender s) {
        if (!(s instanceof Player p)) return;
        expireRequests();
        Request r = findRequest(p.getUniqueId());
        if (r == null) {
            p.sendMessage(ChatColor.RED + "You have no duel request.");
            return;
        }
        requests.remove(r);
        notifyPlayer(r.a, ChatColor.RED + "The duel request was declined.");
        notifyPlayer(r.b, ChatColor.RED + "The duel request was declined.");
    }

    private void adminDuel(CommandSender s, String[] args) {
        if (!isAdmin(s)) {
            s.sendMessage(ChatColor.RED + "Operators only.");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(s);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "addkit" -> {
                if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return; }
                if (args.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /adminduel addkit <name> (saves your current inventory as the kit)"); return; }
                kits.put(args[1], p.getInventory().getContents());
                saveKits();
                s.sendMessage(ChatColor.GREEN + "Kit " + args[1] + " saved from your inventory.");
            }
            case "removekit" -> {
                if (args.length < 2 || kits.remove(args[1]) == null) { s.sendMessage(ChatColor.RED + "No kit with that name."); return; }
                saveKits();
                s.sendMessage(ChatColor.GREEN + "Kit removed.");
            }
            case "listkits" -> s.sendMessage(ChatColor.GOLD + "Kits: " + ChatColor.WHITE + (kits.isEmpty() ? "none" : String.join(", ", kits.keySet())));
            case "arena" -> arenaCommand(s, args);
            case "setlobby" -> {
                if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return; }
                lobby = p.getLocation().clone();
                saveArenas();
                s.sendMessage(ChatColor.GREEN + "Lobby set to where you stand.");
            }
            case "forceend" -> {
                if (args.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /adminduel forceend <player>"); return; }
                Player p = Bukkit.getPlayerExact(args[1]);
                Duel d = p == null ? null : duelOf.get(p.getUniqueId());
                if (d == null) { s.sendMessage(ChatColor.RED + "That player is not in a duel."); return; }
                endDuel(d, null, null);
                s.sendMessage(ChatColor.GREEN + "Duel ended. Both players were restored and the arena is resetting.");
            }
            default -> s.sendMessage(ChatColor.RED + "Unknown subcommand. Use /adminduel help.");
        }
    }

    private void arenaCommand(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage: /adminduel arena <generate|setspawn1|setspawn2|delete|list> [name]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (arenas.isEmpty()) { s.sendMessage(ChatColor.GRAY + "No arenas yet."); return; }
                for (Arena a : arenas.values()) {
                    String state = a.building ? " building" : a.complete() ? " ready" : " incomplete";
                    s.sendMessage(ChatColor.GOLD + a.name + ChatColor.GRAY + state + (a.generated ? " (generated)" : ""));
                }
            }
            case "setspawn1", "setspawn2" -> {
                if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return; }
                if (args.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /adminduel arena " + args[1] + " <name>"); return; }
                Arena a = arenas.get(args[2]);
                if (a == null) {
                    a = new Arena();
                    a.name = args[2];
                    arenas.put(args[2], a);
                }
                if (args[1].equalsIgnoreCase("setspawn1")) a.spawn1 = p.getLocation().clone();
                else a.spawn2 = p.getLocation().clone();
                if (!a.generated) {
                    a.centre = null;
                    refreshCentre(a);
                }
                saveArenas();
                s.sendMessage(ChatColor.GREEN + "Arena " + args[2] + " " + args[1] + " set." + (a.complete() ? " The arena is ready." : ""));
            }
            case "delete" -> {
                if (args.length < 3 || !arenas.containsKey(args[2])) { s.sendMessage(ChatColor.RED + "No arena with that name."); return; }
                Arena a = arenas.get(args[2]);
                if (isInDuel(a)) { s.sendMessage(ChatColor.RED + "That arena is in a duel. End the duel first."); return; }
                arenas.remove(args[2]);
                if (a.generated && a.centre != null) {
                    buildQueue = new ArrayList<>();
                    queueClear(a);
                    List<Op> ops = buildQueue;
                    buildQueue = new ArrayList<>();
                    startBuild(null, ops, List.of(), List.of(a.name));
                }
                saveArenas();
                s.sendMessage(ChatColor.GREEN + "Arena removed. Its blocks are being cleared.");
            }
            case "generate" -> {
                if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return; }
                int count = 1;
                boolean sky = false;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("sky")) {
                        sky = true;
                        continue;
                    }
                    try {
                        count = Math.max(1, Math.min(10, Integer.parseInt(args[i])));
                    } catch (NumberFormatException ex) {
                        s.sendMessage(ChatColor.RED + "Usage: /adminduel arena generate [count] [sky]");
                        return;
                    }
                }
                generateArenas(p, count, sky);
            }
            default -> s.sendMessage(ChatColor.RED + "Unknown arena subcommand.");
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "--- StickDuels ---");
        s.sendMessage(ChatColor.WHITE + "/adminduelrequest <player> <player2> [kit]" + ChatColor.GRAY + " - challenge two players (both must accept)");
        s.sendMessage(ChatColor.WHITE + "/duelaccept" + ChatColor.GRAY + " / " + ChatColor.WHITE + "/dueldeny" + ChatColor.GRAY + " - answer a request");
        s.sendMessage(ChatColor.WHITE + "/duelview" + ChatColor.GRAY + " - view the opponent's items after a duel");
        s.sendMessage(ChatColor.WHITE + "/adminduel addkit <name>" + ChatColor.GRAY + " - save your current inventory as a kit");
        s.sendMessage(ChatColor.WHITE + "/adminduel removekit <name>" + ChatColor.GRAY + " - delete a kit");
        s.sendMessage(ChatColor.WHITE + "/adminduel listkits" + ChatColor.GRAY + " - list kits");
        s.sendMessage(ChatColor.WHITE + "/adminduel arena generate [count] [sky]" + ChatColor.GRAY + " - build arenas (replaces old generated ones)");
        s.sendMessage(ChatColor.WHITE + "/adminduel arena setspawn1|setspawn2 <name>" + ChatColor.GRAY + " - set a manual arena's spawns");
        s.sendMessage(ChatColor.WHITE + "/adminduel arena list|delete <name>" + ChatColor.GRAY + " - manage arenas");
        s.sendMessage(ChatColor.WHITE + "/adminduel setlobby" + ChatColor.GRAY + " - where non-operators are sent if they wander into an arena");
        s.sendMessage(ChatColor.WHITE + "/adminduel forceend <player>" + ChatColor.GRAY + " - end a duel and restore both players");
    }

    // ---------- Tab suggestions ----------

    private List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (!isAdmin(s)) return List.of();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("adminduelrequest")) {
            if (args.length == 1 || args.length == 2) return filter(onlineNames(), args[args.length - 1]);
            if (args.length == 3) return filter(new ArrayList<>(kits.keySet()), args[2]);
            return List.of();
        }
        if (name.equals("adminduel")) {
            if (args.length == 1) return filter(List.of("help", "addkit", "removekit", "listkits", "arena", "setlobby", "forceend"), args[0]);
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("removekit") && args.length == 2) return filter(new ArrayList<>(kits.keySet()), args[1]);
            if (sub.equals("forceend") && args.length == 2) return filter(onlineNames(), args[1]);
            if (sub.equals("arena")) {
                if (args.length == 2) return filter(List.of("generate", "setspawn1", "setspawn2", "list", "delete"), args[1]);
                if (args.length == 3) return filter(new ArrayList<>(arenas.keySet()), args[2]);
                if (args.length == 4 && args[1].equalsIgnoreCase("generate")) return filter(List.of("sky"), args[3]);
            }
        }
        return List.of();
    }

    // ---------- Requests ----------

    private void expireRequests() {
        long now = System.currentTimeMillis();
        requests.removeIf(r -> {
            if (now < r.expires) return false;
            notifyPlayer(r.a, ChatColor.GRAY + "The duel request expired.");
            notifyPlayer(r.b, ChatColor.GRAY + "The duel request expired.");
            return true;
        });
    }

    private Request findRequest(UUID id) {
        for (Request r : requests) if (r.involves(id)) return r;
        return null;
    }

    private boolean busy(UUID id) {
        if (duelOf.containsKey(id)) return true;
        long now = System.currentTimeMillis();
        for (Request r : requests) if (r.involves(id) && now < r.expires) return true;
        return false;
    }

    private void notifyPlayer(UUID id, String msg) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.sendMessage(msg);
    }

    // ---------- Arena geometry ----------

    private boolean isInDuel(Arena a) {
        for (Duel d : duels) if (d.arena == a) return true;
        return false;
    }

    private Arena freeArena() {
        for (Arena a : arenas.values()) {
            if (!a.complete() || a.building || isInDuel(a)) continue;
            return a;
        }
        return null;
    }

    /** True if the location is within radius of the arena centre, close enough in height. */
    private static boolean within(Arena a, Location l, double radius) {
        if (!a.complete() || l == null || l.getWorld() == null || !l.getWorld().equals(a.centre.getWorld())) return false;
        if (Math.abs(l.getY() - a.centre.getY()) > MAX_Y_DELTA) return false;
        return Math.hypot(l.getX() - a.centre.getX(), l.getZ() - a.centre.getZ()) <= radius;
    }

    private boolean insideAnyArena(Location l) {
        for (Arena a : arenas.values()) if (within(a, l, ARENA_RADIUS)) return true;
        return false;
    }

    private Location lobbyLocation() {
        return lobby != null ? lobby : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /** A location at the same height, pulled to the given radius from the arena centre along the same direction. */
    private static Location clampTo(Arena a, Location l, double radius, float yaw, float pitch) {
        double dx = l.getX() - a.centre.getX();
        double dz = l.getZ() - a.centre.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.001) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new Location(l.getWorld(),
                a.centre.getX() + dx / len * radius,
                l.getY(),
                a.centre.getZ() + dz / len * radius,
                yaw, pitch);
    }

    private void warn(Player p, String msg) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < WARN_COOLDOWN_MS) return;
        lastWarn.put(p.getUniqueId(), now);
        p.sendMessage(msg);
    }

    // ---------- Building (batched) ----------

    private void put(World w, int x, int y, int z, Material m) {
        buildQueue.add(new Op(w, x, y, z, m, null));
    }

    private void putStair(World w, int x, int y, int z, Material stair, BlockFace facing) {
        buildQueue.add(new Op(w, x, y, z, stair, facing));
    }

    private static BlockFace inward(int x, int z) {
        if (Math.abs(x) >= Math.abs(z)) return x > 0 ? BlockFace.WEST : BlockFace.EAST;
        return z > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    /** Queues a floating rock underside below the arena floor, tapering as it goes down. */
    private void buildIsland(World w, int cx, int by, int cz) {
        int R = ARENA_R;
        for (int k = 1; k <= 14; k++) {
            int radius = R - k * 3;
            if (radius <= 0) break;
            Material m = k <= 2 ? Material.POLISHED_ANDESITE : (k <= 7 ? Material.STONE : Material.DEEPSLATE);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z > radius * radius) continue;
                    put(w, cx + x, by - k, cz + z, m);
                }
            }
        }
    }

    /** Queues removal of everything a generated arena placed, so it can be deleted. */
    private void queueClear(Arena a) {
        if (!a.generated || a.centre == null) return;
        World w = a.centre.getWorld();
        int cx = a.centre.getBlockX(), by = a.centre.getBlockY(), cz = a.centre.getBlockZ();
        int R = ARENA_R + 6;
        int low = a.sky ? -16 : -1;
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (x * x + z * z > R * R) continue;
                for (int y = low; y <= 34; y++) put(w, cx + x, by + y, cz + z, Material.AIR);
            }
        }
    }

    /** Queues one large themed arena centred on (cx, by, cz). */
    private void buildArena(World w, int cx, int by, int cz, boolean sky) {
        int R = ARENA_R;

        if (sky) buildIsland(w, cx, by, cz);

        // Clear the airspace.
        for (int x = -R - 5; x <= R + 5; x++) {
            for (int z = -R - 5; z <= R + 5; z++) {
                if (x * x + z * z > (R + 5) * (R + 5)) continue;
                for (int y = 1; y <= 26; y++) put(w, cx + x, by + y, cz + z, Material.AIR);
            }
        }

        // Moat: a water ring outside the wall on a stone bed.
        for (int x = -R - 5; x <= R + 5; x++) {
            for (int z = -R - 5; z <= R + 5; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= R + 2 || d > R + 4) continue;
                put(w, cx + x, by - 1, cz + z, Material.STONE_BRICKS);
                put(w, cx + x, by, cz + z, Material.WATER);
            }
        }

        // Floor: inner polished checker, gold inlay ring, outer prismarine checker, chiselled rim, sea lantern grid.
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > R) continue;
                Material m;
                if (d > R - 1.5) m = Material.CHISELED_STONE_BRICKS;
                else if (d >= 20 && d < 21) m = Material.GOLD_BLOCK;
                else if (d < 20) m = ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
                else m = ((x + z) & 1) == 0 ? Material.PRISMARINE_BRICKS : Material.DARK_PRISMARINE;
                if (m != Material.GOLD_BLOCK && x % 12 == 0 && z % 12 == 0 && d < R - 3) m = Material.SEA_LANTERN;
                put(w, cx + x, by, cz + z, m);
            }
        }

        // Amethyst clusters around the rim.
        for (int k = 0; k < 24; k++) {
            double ang = Math.toRadians(7.5 + 15 * k);
            int ax = (int) Math.round((R - 3) * Math.cos(ang));
            int az = (int) Math.round((R - 3) * Math.sin(ang));
            put(w, cx + ax, by + 1, cz + az, Material.AMETHYST_CLUSTER);
        }

        // Crenellated outer wall, with lanterns on the tall sections.
        for (int x = -R - 2; x <= R + 2; x++) {
            for (int z = -R - 2; z <= R + 2; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= R || d > R + 1.2) continue;
                boolean tall = (Math.abs(x) + Math.abs(z)) % 2 == 0;
                int h = tall ? 5 : 2;
                for (int y = 1; y <= h; y++) put(w, cx + x, by + y, cz + z, Material.STONE_BRICK_WALL);
                if (tall && (Math.abs(x) + Math.abs(z)) % 12 == 0) put(w, cx + x, by + 6, cz + z, Material.LANTERN);
            }
        }

        // Four gates: a stone bridge over the moat, a gap in the wall, two pillars, a lintel and a lantern.
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            int dx = dir[0], dz = dir[1];
            int lx = -dz, lz = dx;
            for (int s = R + 2; s <= R + 4; s++) {
                for (int l = -2; l <= 2; l++) {
                    put(w, cx + dx * s + lx * l, by, cz + dz * s + lz * l, Material.STONE_BRICKS);
                }
            }
            int sx = dx * (R + 1), sz = dz * (R + 1);
            for (int l = -2; l <= 2; l++) {
                int gx = cx + sx + lx * l, gz = cz + sz + lz * l;
                if (Math.abs(l) <= 1) {
                    for (int y = 1; y <= 4; y++) put(w, gx, by + y, gz, Material.AIR);
                    put(w, gx, by + 5, gz, Material.STONE_BRICKS);
                } else {
                    for (int y = 1; y <= 6; y++) put(w, gx, by + y, gz, Material.POLISHED_DEEPSLATE);
                }
            }
            put(w, cx + sx, by + 4, cz + sz, Material.LANTERN);
        }

        // Cover: twelve quartz pillars with lanterns on the barrier line.
        for (int k = 0; k < 12; k++) {
            double ang = Math.toRadians(15 + 30 * k);
            int px = (int) Math.round(BARRIER_RADIUS * Math.cos(ang));
            int pz = (int) Math.round(BARRIER_RADIUS * Math.sin(ang));
            for (int y = 1; y <= 6; y++) put(w, cx + px, by + y, cz + pz, Material.QUARTZ_PILLAR);
            put(w, cx + px, by + 7, cz + pz, Material.LANTERN);
        }

        // Tier one: polished platform with stairs all round.
        for (int x = -13; x <= 13; x++) {
            for (int z = -13; z <= 13; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 12) put(w, cx + x, by + 1, cz + z, Material.POLISHED_ANDESITE);
                else if (d <= 13) putStair(w, cx + x, by + 1, cz + z, Material.STONE_BRICK_STAIRS, inward(x, z));
            }
        }
        // Tier two: quartz platform with stairs, a light on top.
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 7) put(w, cx + x, by + 2, cz + z, Material.QUARTZ_BLOCK);
                else if (d <= 8) putStair(w, cx + x, by + 2, cz + z, Material.QUARTZ_STAIRS, inward(x, z));
            }
        }
        put(w, cx, by + 3, cz, Material.SEA_LANTERN);

        // Four tall corner towers with balconies, crenellated tops, lanterns and coloured banners.
        int tower = (int) Math.round(R * 0.72);
        Material[] banners = {Material.RED_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL, Material.GREEN_WOOL};
        int bi = 0;
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                int tx = cx + sx * tower;
                int tz = cz + sz * tower;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = 1; y <= 26; y++) put(w, tx + dx, by + y, tz + dz, Material.DEEPSLATE_TILES);
                        put(w, tx + dx, by + 14, tz + dz, Material.POLISHED_DEEPSLATE);
                        put(w, tx + dx, by + 27, tz + dz, Material.POLISHED_DEEPSLATE);
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        put(w, tx + dx, by + 28, tz + dz, Material.COBBLESTONE_WALL);
                    }
                }
                put(w, tx, by + 28, tz, Material.LANTERN);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) == 2 || Math.abs(dz) == 2) put(w, tx + dx, by + 15, tz + dz, Material.STONE_BRICK_SLAB);
                    }
                }
                for (int y = 29; y <= 31; y++) put(w, tx, by + y, tz, Material.OAK_FENCE);
                put(w, tx, by + 32, tz, banners[bi++ % banners.length]);
            }
        }
    }

    /** Places queued blocks in batches over several ticks, then marks the arenas ready. */
    private void startBuild(Player admin, List<Op> ops, List<Arena> built, List<String> names) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(ops.size(), i + BLOCKS_PER_TICK);
                for (; i < end; i++) {
                    Op op = ops.get(i);
                    if (op.facing() == null) {
                        op.w().getBlockAt(op.x(), op.y(), op.z()).setType(op.m(), false);
                    } else {
                        BlockData bd = op.m().createBlockData();
                        if (bd instanceof Stairs st) st.setFacing(op.facing());
                        op.w().getBlockAt(op.x(), op.y(), op.z()).setBlockData(bd, false);
                    }
                }
                if (i >= ops.size()) {
                    for (Arena a : built) a.building = false;
                    if (admin != null && admin.isOnline() && !names.isEmpty()) {
                        admin.sendMessage(ChatColor.GREEN + "Finished building " + String.join(", ", names) + ". They are ready for duels.");
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** Deletes every generated arena and builds new ones starting where the admin stands. */
    private void generateArenas(Player p, int count, boolean sky) {
        if (!duels.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Wait until every duel has finished before generating arenas.");
            return;
        }
        World w = p.getWorld();
        int baseX = p.getLocation().getBlockX();
        int baseZ = p.getLocation().getBlockZ();
        int baseY = sky ? SKY_Y : p.getLocation().getBlockY() - 1;

        buildQueue = new ArrayList<>();
        for (Arena old : arenas.values()) queueClear(old);
        arenas.clear();

        int next = 1;
        List<String> names = new ArrayList<>();
        List<Arena> built = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "arena" + next++;
            int cx = baseX + i * ARENA_SPACING;
            buildArena(w, cx, baseY, baseZ, sky);
            Arena a = new Arena();
            a.name = name;
            a.sky = sky;
            a.generated = true;
            a.centre = new Location(w, cx, baseY, baseZ);
            a.spawn1 = new Location(w, cx - ARENA_SPAWN_OFFSET + 0.5, baseY + 1, baseZ + 0.5, 90f, 0f);
            a.spawn2 = new Location(w, cx + ARENA_SPAWN_OFFSET + 0.5, baseY + 1, baseZ + 0.5, -90f, 0f);
            a.building = true;
            arenas.put(name, a);
            names.add(name);
            built.add(a);
        }
        List<Op> ops = buildQueue;
        buildQueue = new ArrayList<>();
        saveArenas();
        startBuild(p, ops, built, names);
        p.sendMessage(ChatColor.GOLD + "Old generated arenas are being deleted. Building " + names.size() + " arena(s): "
                + String.join(", ", names) + (sky ? " high in the sky" : "") + ". Duels can start once it finishes.");
    }

    /** Rebuilds a generated arena from scratch, which clears cobwebs, dropped items and any damage. */
    private void resetArena(Arena a) {
        if (!a.generated || a.centre == null) return;
        World w = a.centre.getWorld();
        a.building = true;
        Location c = a.centre;
        for (Entity en : w.getNearbyEntities(c, ARENA_R + 10, 90, ARENA_R + 10)) {
            if (en instanceof Item || en instanceof Projectile) en.remove();
        }
        buildQueue = new ArrayList<>();
        buildArena(w, a.centre.getBlockX(), a.centre.getBlockY(), a.centre.getBlockZ(), a.sky);
        List<Op> ops = buildQueue;
        buildQueue = new ArrayList<>();
        startBuild(null, ops, List.of(a), List.of(a.name));
    }

    // ---------- Duels ----------

    private void startDuel(Request r) {
        Player a = Bukkit.getPlayer(r.a);
        Player b = Bukkit.getPlayer(r.b);
        Arena arena = freeArena();
        ItemStack[] kit = kits.get(r.kit);
        if (a == null || b == null || arena == null || kit == null) {
            notifyPlayer(r.a, ChatColor.RED + "The duel could not start.");
            notifyPlayer(r.b, ChatColor.RED + "The duel could not start.");
            return;
        }
        Duel d = new Duel(r.a, r.b, r.kit, arena);
        d.saved.put(r.a, new Snapshot(a.getInventory().getContents(), a.getLocation().clone()));
        d.saved.put(r.b, new Snapshot(b.getInventory().getContents(), b.getLocation().clone()));
        duels.add(d);
        duelOf.put(r.a, d);
        duelOf.put(r.b, d);

        prepare(a, kit);
        prepare(b, kit);
        a.teleport(arena.spawn1);
        b.teleport(arena.spawn2);
        a.sendMessage(ChatColor.GOLD + "Duel vs " + b.getName() + " with " + r.kit + " starts in " + COUNTDOWN_SECONDS + " seconds.");
        b.sendMessage(ChatColor.GOLD + "Duel vs " + a.getName() + " with " + r.kit + " starts in " + COUNTDOWN_SECONDS + " seconds.");
        countdown(d);
    }

    private void prepare(Player p, ItemStack[] kit) {
        p.getInventory().clear();
        p.getInventory().setContents(Arrays.stream(kit).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
        for (PotionEffect eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
    }

    private void countdown(Duel d) {
        new BukkitRunnable() {
            int n = COUNTDOWN_SECONDS;

            @Override
            public void run() {
                if (!duels.contains(d)) {
                    cancel();
                    return;
                }
                if (n == 0) {
                    d.started = true;
                    title(d, ChatColor.GREEN + "FIGHT!", "");
                    cancel();
                    return;
                }
                title(d, ChatColor.YELLOW + String.valueOf(n), "");
                n--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void title(Duel d, String top, String bottom) {
        for (UUID id : List.of(d.a, d.b)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendTitle(top, bottom, 5, 15, 5);
        }
    }

    private void endDuel(Duel d, UUID winnerId, UUID loserId) {
        duels.remove(d);
        duelOf.remove(d.a);
        duelOf.remove(d.b);

        // Remember each duelist's final inventory so the opponent can look at it.
        Map<UUID, ItemStack[]> finals = new HashMap<>();
        for (UUID id : List.of(d.a, d.b)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) finals.put(id, p.getInventory().getContents());
        }

        for (UUID id : List.of(d.a, d.b)) {
            Snapshot s = d.saved.get(id);
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                if (s != null) saveRestore(id, s);
                continue;
            }
            if (p.getHealth() <= 0) continue;   // restored on respawn
            if (s != null) restoreSnapshot(p, s);
            if (winnerId == null) p.sendMessage(ChatColor.GRAY + "The duel was ended by an admin.");
            else if (id.equals(winnerId)) p.sendMessage(ChatColor.GREEN + "You won the duel!");
            else p.sendMessage(ChatColor.RED + "You lost the duel.");
        }

        for (UUID id : List.of(d.a, d.b)) {
            UUID opp = d.opponentOf(id);
            ItemStack[] oppItems = finals.get(opp);
            if (oppItems == null) continue;
            String oppName = Bukkit.getOfflinePlayer(opp).getName();
            viewable.put(id, new ViewData(oppName == null ? "opponent" : oppName, oppItems, System.currentTimeMillis() + VIEW_TIMEOUT_MS));
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Component.text("[Click to view " + (oppName == null ? "your opponent" : oppName) + "'s items and durability]", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/duelview"))
                        .hoverEvent(HoverEvent.showText(Component.text("Opens a read-only view of their final inventory"))));
            }
        }

        resetArena(d.arena);
    }

    private void restoreSnapshot(Player p, Snapshot s) {
        if (s.contents() != null) {
            p.getInventory().clear();
            p.getInventory().setContents(s.contents().clone());
        }
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        if (s.origin() != null) {
            lockBypass.add(p.getUniqueId());
            p.teleport(s.origin());
            lockBypass.remove(p.getUniqueId());
        }
    }

    private void saveRestore(UUID id, Snapshot s) {
        restores.set(id + ".contents", Arrays.asList(s.contents()));
        restores.set(id + ".origin", s.origin());
        saveRestores();
    }

    // ---------- Post-duel inventory view ----------

    private void openView(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        ViewData v = viewable.get(p.getUniqueId());
        if (v == null || System.currentTimeMillis() > v.expires()) {
            p.sendMessage(ChatColor.RED + "There is nothing to view. Finish a duel first.");
            return;
        }
        Inventory inv = Bukkit.createInventory(new ViewHolder(), 54, Component.text(v.ownerName() + "'s items"));
        ItemStack[] items = v.items();
        for (int i = 0; i < Math.min(items.length, 54); i++) {
            ItemStack it = items[i];
            if (it == null) continue;
            ItemStack copy = it.clone();
            ItemMeta meta = copy.getItemMeta();
            if (meta instanceof Damageable dm && copy.getType().getMaxDurability() > 0) {
                int max = copy.getType().getMaxDurability();
                int left = max - dm.getDamage();
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add(ChatColor.GRAY + "Durability: " + ChatColor.WHITE + left + "/" + max);
                meta.setLore(lore);
                copy.setItemMeta(meta);
            }
            inv.setItem(i, copy);
        }
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onViewClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ViewHolder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onViewDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ViewHolder) e.setCancelled(true);
    }

    // ---------- Duel rules ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Player p = e.getPlayer();
        Duel d = duelOf.get(p.getUniqueId());

        if (d != null) {
            Arena a = d.arena;
            if (!d.started && (e.getFrom().getX() != to.getX() || e.getFrom().getY() != to.getY() || e.getFrom().getZ() != to.getZ())) {
                Location f = e.getFrom().clone();
                f.setYaw(to.getYaw());
                f.setPitch(to.getPitch());
                e.setTo(f);
                return;
            }
            // Duelists can't leave the arena: they are pushed back in from the barrier.
            if (within(a, to, BARRIER_RADIUS)) return;
            Location back = clampTo(a, to, BARRIER_RADIUS - 2, to.getYaw(), to.getPitch());
            Vector in = back.toVector().subtract(to.toVector()).setY(0);
            e.setTo(back);
            p.setVelocity(new Vector(0, 0.2, 0).add(in.normalize().multiply(0.4)));
            warn(p, ChatColor.RED + "You cannot leave the arena during a duel.");
            return;
        }

        if (p.isOp() || lockBypass.contains(p.getUniqueId())) return;

        for (Arena a : arenas.values()) {
            if (!a.complete()) continue;
            if (isInDuel(a) && within(a, to, BARRIER_RADIUS)) {
                // Outsiders are pushed out past the white pillars.
                Location out = clampTo(a, to, BARRIER_RADIUS + 2, to.getYaw(), to.getPitch());
                Vector outward = out.toVector().subtract(to.toVector()).setY(0);
                e.setTo(out);
                p.setVelocity(new Vector(0, 0.2, 0).add(outward.normalize().multiply(0.6)));
                warn(p, ChatColor.YELLOW + "A duel is happening, kindly step behind.");
                return;
            }
            if (!isInDuel(a) && within(a, to, ARENA_RADIUS)) {
                e.setTo(lobbyLocation());
                p.sendMessage(ChatColor.RED + "That arena is closed to players.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (p.isOp() || lockBypass.contains(p.getUniqueId()) || to == null) return;
        Duel d = duelOf.get(p.getUniqueId());
        if (d != null) {
            if (!within(d.arena, to, BARRIER_RADIUS)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You cannot leave the arena during a duel.");
            }
            return;
        }
        for (Arena a : arenas.values()) {
            if (isInDuel(a) && within(a, to, BARRIER_RADIUS)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.YELLOW + "A duel is happening, kindly step behind.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player dp) attacker = dp;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp) attacker = sp;
        Duel vd = duelOf.get(victim.getUniqueId());
        Duel ad = attacker == null ? null : duelOf.get(attacker.getUniqueId());
        if (vd == null && ad == null) return;
        if (vd != null && (ad != vd || !vd.started)) {
            e.setCancelled(true);
            return;
        }
        if (vd == null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Duel d = duelOf.get(p.getUniqueId());
        if (d == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        Snapshot s = d.saved.get(p.getUniqueId());
        if (s != null) respawnRestore.put(p.getUniqueId(), s);
        endDuel(d, d.opponentOf(p.getUniqueId()), p.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Snapshot s = respawnRestore.remove(e.getPlayer().getUniqueId());
        if (s == null) return;
        if (s.origin() != null) e.setRespawnLocation(s.origin());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) restoreSnapshot(p, s);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (duelOf.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You cannot drop items during a duel.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDuelInteract(PlayerInteractEvent e) {
        if (!duelOf.containsKey(e.getPlayer().getUniqueId())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() == Material.ENDER_CHEST) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Ender chests are disabled during a duel.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDuelInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!duelOf.containsKey(p.getUniqueId())) return;
        if (e.getInventory().getType() == InventoryType.ENDER_CHEST) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Ender chests are disabled during a duel.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDuelCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (p.isOp() || !duelOf.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        p.sendMessage(ChatColor.RED + "Commands are disabled during a duel.");
    }

    // The arena can't be changed by players.

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaBreak(BlockBreakEvent e) {
        if (insideAnyArena(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaPlace(BlockPlaceEvent e) {
        if (insideAnyArena(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaBucket(PlayerBucketEmptyEvent e) {
        if (insideAnyArena(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> insideAnyArena(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> insideAnyArena(b.getLocation()));
    }

    // ---------- Quit and join ----------

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player leaver = e.getPlayer();
        Duel d = duelOf.get(leaver.getUniqueId());
        if (d == null) return;
        UUID winner = d.opponentOf(leaver.getUniqueId());
        Snapshot own = d.saved.get(leaver.getUniqueId());
        duels.remove(d);
        duelOf.remove(d.a);
        duelOf.remove(d.b);
        if (own != null) saveRestore(leaver.getUniqueId(), own);
        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            Snapshot ws = d.saved.get(winner);
            if (ws != null) restoreSnapshot(w, ws);
            w.sendMessage(ChatColor.GREEN + "Your opponent left. You win the duel!");
        }
        resetArena(d.arena);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String path = p.getUniqueId().toString();
        if (!restores.contains(path)) return;
        ItemStack[] contents = toContents(restores.getList(path + ".contents", List.of()));
        Location origin = restores.getLocation(path + ".origin");
        restores.set(path, null);
        saveRestores();
        if (origin == null) return;
        Snapshot s = new Snapshot(contents, origin);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            restoreSnapshot(p, s);
            p.sendMessage(ChatColor.YELLOW + "Your inventory was restored after an unfinished duel.");
        }, 20L);
    }
}

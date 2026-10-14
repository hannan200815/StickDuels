package com.stickduels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class StickDuels extends JavaPlugin implements Listener, CommandExecutor {

    private static final long REQUEST_TIMEOUT_MS = 60_000L;
    private static final int COUNTDOWN_SECONDS = 5;
    private static final double ARENA_RADIUS = 40.0;
    private static final int CONTENTS_SIZE = 41;

    private static final class Arena {
        Location spawn1;
        Location spawn2;

        boolean complete() {
            return spawn1 != null && spawn2 != null;
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

    private final Map<String, ItemStack[]> kits = new LinkedHashMap<>();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<Duel> duels = new ArrayList<>();
    private final Map<UUID, Duel> duelOf = new HashMap<>();
    private final Map<UUID, Snapshot> respawnRestore = new HashMap<>();
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
        for (String name : List.of("adminduelrequest", "duelaccept", "dueldeny", "adminduel")) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
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
            a.spawn1 = y.getLocation(name + ".spawn1");
            a.spawn2 = y.getLocation(name + ".spawn2");
            arenas.put(name, a);
        }
    }

    private void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        if (lobby != null) y.set("lobby", lobby);
        for (Map.Entry<String, Arena> en : arenas.entrySet()) {
            if (en.getValue().spawn1 != null) y.set(en.getKey() + ".spawn1", en.getValue().spawn1);
            if (en.getValue().spawn2 != null) y.set(en.getKey() + ".spawn2", en.getValue().spawn2);
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

    private static ItemStack[] toContents(List<?> raw) {
        ItemStack[] c = new ItemStack[CONTENTS_SIZE];
        for (int i = 0; i < Math.min(CONTENTS_SIZE, raw.size()); i++) {
            Object o = raw.get(i);
            if (o instanceof ItemStack is) c[i] = is;
        }
        return c;
    }

    // ---------- Default kits (standard PvP loadouts, replace with /adminduel addkit) ----------

    private static ItemStack piece(Material m) {
        ItemStack it = new ItemStack(m);
        it.addEnchantment(Enchantment.PROTECTION, 4);
        return it;
    }

    private static ItemStack weapon(Material m, Enchantment e, int lvl) {
        ItemStack it = new ItemStack(m);
        if (e != null) it.addEnchantment(e, lvl);
        return it;
    }

    private static ItemStack[] buildKit(List<ItemStack> hotbar, Material[] armor, boolean shield) {
        ItemStack[] c = new ItemStack[CONTENTS_SIZE];
        for (int i = 0; i < hotbar.size() && i < 36; i++) c[i] = hotbar.get(i);
        if (armor != null) {
            c[39] = piece(armor[0]);
            c[38] = piece(armor[1]);
            c[37] = piece(armor[2]);
            c[36] = piece(armor[3]);
        }
        if (shield) c[40] = new ItemStack(Material.SHIELD);
        return c;
    }

    private Map<String, ItemStack[]> defaultKits() {
        Material netherite[] = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
        Material diamond[] = {Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS};
        Material spearMat = Material.matchMaterial("NETHERITE_SPEAR");
        if (spearMat == null) spearMat = Material.NETHERITE_SWORD;

        Map<String, ItemStack[]> m = new LinkedHashMap<>();
        m.put("Sword", buildKit(List.of(weapon(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5), new ItemStack(Material.GOLDEN_APPLE, 8)), netherite, true));
        m.put("Axe", buildKit(List.of(weapon(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5), new ItemStack(Material.GOLDEN_APPLE, 8)), netherite, true));
        m.put("Mace", buildKit(List.of(new ItemStack(Material.MACE), new ItemStack(Material.GOLDEN_APPLE, 8)), netherite, true));
        m.put("Spear", buildKit(List.of(weapon(spearMat, Enchantment.SHARPNESS, 5), new ItemStack(Material.GOLDEN_APPLE, 8)), netherite, true));
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addEnchantment(Enchantment.POWER, 5);
        bow.addEnchantment(Enchantment.INFINITY, 1);
        m.put("Archer", buildKit(List.of(bow, weapon(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4), new ItemStack(Material.ARROW, 1), new ItemStack(Material.GOLDEN_APPLE, 8)), diamond, false));
        m.put("Sumo", buildKit(List.of(weapon(Material.STICK, Enchantment.KNOCKBACK, 2)), null, false));
        return m;
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "adminduelrequest" -> adminRequest(sender, args);
            case "duelaccept" -> accept(sender);
            case "dueldeny" -> deny(sender);
            case "adminduel" -> adminDuel(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean isAdmin(CommandSender s) {
        return s.isOp() || s.hasPermission("stickduels.admin");
    }

    private String matchKit(String name) {
        for (String k : kits.keySet()) if (k.equalsIgnoreCase(name)) return k;
        return null;
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
            s.sendMessage(ChatColor.RED + "No arena is ready. Set both spawns with /adminduel arena setspawn1 <name> and setspawn2 <name>.");
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
                s.sendMessage(ChatColor.GREEN + "Duel ended. Both players were restored.");
            }
            default -> s.sendMessage(ChatColor.RED + "Unknown subcommand. Use /adminduel help.");
        }
    }

    private void arenaCommand(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(ChatColor.RED + "Usage: /adminduel arena <setspawn1|setspawn2|delete|list> [name]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (arenas.isEmpty()) { s.sendMessage(ChatColor.GRAY + "No arenas yet."); return; }
                for (Map.Entry<String, Arena> en : arenas.entrySet()) {
                    s.sendMessage(ChatColor.GOLD + en.getKey() + ChatColor.GRAY + (en.getValue().complete() ? " ready" : " incomplete"));
                }
            }
            case "setspawn1", "setspawn2" -> {
                if (!(s instanceof Player p)) { s.sendMessage(ChatColor.RED + "Players only."); return; }
                if (args.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /adminduel arena " + args[1] + " <name>"); return; }
                Arena a = arenas.computeIfAbsent(args[2], k -> new Arena());
                if (args[1].equalsIgnoreCase("setspawn1")) a.spawn1 = p.getLocation().clone();
                else a.spawn2 = p.getLocation().clone();
                saveArenas();
                s.sendMessage(ChatColor.GREEN + "Arena " + args[2] + " " + args[1] + " set." + (a.complete() ? " The arena is ready." : ""));
            }
            case "delete" -> {
                if (args.length < 3 || arenas.remove(args[2]) == null) { s.sendMessage(ChatColor.RED + "No arena with that name."); return; }
                saveArenas();
                s.sendMessage(ChatColor.GREEN + "Arena removed.");
            }
            default -> s.sendMessage(ChatColor.RED + "Unknown arena subcommand.");
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "--- StickDuels ---");
        s.sendMessage(ChatColor.WHITE + "/adminduelrequest <player> <player2> [kit]" + ChatColor.GRAY + " - challenge two players (both must accept)");
        s.sendMessage(ChatColor.WHITE + "/duelaccept" + ChatColor.GRAY + " / " + ChatColor.WHITE + "/dueldeny" + ChatColor.GRAY + " - answer a request");
        s.sendMessage(ChatColor.WHITE + "/adminduel addkit <name>" + ChatColor.GRAY + " - save your current inventory as a kit");
        s.sendMessage(ChatColor.WHITE + "/adminduel removekit <name>" + ChatColor.GRAY + " - delete a kit");
        s.sendMessage(ChatColor.WHITE + "/adminduel listkits" + ChatColor.GRAY + " - list kits");
        s.sendMessage(ChatColor.WHITE + "/adminduel arena setspawn1|setspawn2 <name>" + ChatColor.GRAY + " - set arena spawns where you stand");
        s.sendMessage(ChatColor.WHITE + "/adminduel arena list|delete <name>" + ChatColor.GRAY + " - manage arenas");
        s.sendMessage(ChatColor.WHITE + "/adminduel setlobby" + ChatColor.GRAY + " - where non-operators are sent if they wander into an arena");
        s.sendMessage(ChatColor.WHITE + "/adminduel forceend <player>" + ChatColor.GRAY + " - end a duel and restore both players");
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

    // ---------- Duels ----------

    private Arena freeArena() {
        for (Arena a : arenas.values()) {
            if (!a.complete()) continue;
            boolean used = false;
            for (Duel d : duels) if (d.arena == a) used = true;
            if (!used) return a;
        }
        return null;
    }

    private void startDuel(Request r) {
        Player a = Bukkit.getPlayer(r.a);
        Player b = Bukkit.getPlayer(r.b);
        Arena arena = freeArena();
        if (a == null || b == null || arena == null) {
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

        ItemStack[] kit = kits.get(r.kit);
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
        p.getInventory().setContents(kit.clone());
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
        for (UUID id : List.of(d.a, d.b)) {
            Snapshot s = d.saved.get(id);
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                if (s != null) saveRestore(id, s);
                continue;
            }
            if (p.getHealth() <= 0) continue;
            if (s != null) restoreSnapshot(p, s);
            if (winnerId == null) p.sendMessage(ChatColor.GRAY + "The duel was ended by an admin.");
            else if (id.equals(winnerId)) p.sendMessage(ChatColor.GREEN + "You won the duel!");
            else p.sendMessage(ChatColor.RED + "You lost the duel.");
        }
        if (winnerId != null && loserId != null && Bukkit.getPlayer(loserId) != null && Bukkit.getPlayer(loserId).getHealth() <= 0) {
            notifyPlayer(loserId, ChatColor.RED + "You lost the duel.");
        }
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
        if (s.origin() != null) p.teleport(s.origin());
    }

    private void saveRestore(UUID id, Snapshot s) {
        restores.set(id + ".contents", Arrays.asList(s.contents()));
        restores.set(id + ".origin", s.origin());
        saveRestores();
    }

    // ---------- Arena lock: only duelists and operators may be inside ----------

    private static boolean inside(Arena a, Location l) {
        if (!a.complete() || l == null || l.getWorld() == null || !l.getWorld().equals(a.spawn1.getWorld())) return false;
        double cx = (a.spawn1.getX() + a.spawn2.getX()) / 2.0;
        double cz = (a.spawn1.getZ() + a.spawn2.getZ()) / 2.0;
        return Math.hypot(l.getX() - cx, l.getZ() - cz) <= ARENA_RADIUS;
    }

    private boolean insideAnyArena(Location l) {
        for (Arena a : arenas.values()) if (inside(a, l)) return true;
        return false;
    }

    private Location lobbyLocation() {
        return lobby != null ? lobby : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        Duel d = duelOf.get(p.getUniqueId());
        if (d != null) {
            if (!d.started && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ())) {
                Location f = e.getFrom().clone();
                f.setYaw(e.getTo().getYaw());
                f.setPitch(e.getTo().getPitch());
                e.setTo(f);
            }
            return;
        }
        if (p.isOp() || !insideAnyArena(e.getTo())) return;
        e.setTo(lobbyLocation());
        p.sendMessage(ChatColor.RED + "That arena is in use by a duel.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (p.isOp() || duelOf.containsKey(p.getUniqueId()) || e.getTo() == null) return;
        if (insideAnyArena(e.getTo())) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot enter a duel arena.");
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
        UUID winner = d.opponentOf(p.getUniqueId());
        endDuel(d, winner, p.getUniqueId());
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
        Snapshot s = new Snapshot(contents, origin);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            restoreSnapshot(p, s);
            p.sendMessage(ChatColor.YELLOW + "Your inventory was restored after an unfinished duel.");
        }, 20L);
    }
}

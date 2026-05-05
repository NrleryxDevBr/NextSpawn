package me.Nrleryx;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NextSpawn extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private final Map<UUID, CountdownTp> pendingTp = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVoidRescueMs = new ConcurrentHashMap<>();

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile Location spawnLocation;
    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask pendingConfigSave;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAll();

        getCommand("spawn").setExecutor(this);
        getCommand("spawn").setTabCompleter(this);
        getCommand("setspawn").setExecutor(this);
        getCommand("setspawn").setTabCompleter(this);
        getCommand("nextspawn").setExecutor(this);
        getCommand("nextspawn").setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);
    }

    private void reloadAll() {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask t = pendingConfigSave;
        if (t != null) {
            t.cancel();
            pendingConfigSave = null;
        }
        reloadConfig();
        settings = Settings.from(getConfig());
        messages = Messages.from(getConfig(), settings.teleportDelaySeconds);
        spawnLocation = loadSpawn();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("nextspawn")) {
            if (!sender.hasPermission("nextspawn.admin")) {
                sender.sendMessage(messages.noPermissionLegacy);
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadAll();
                sender.sendMessage(messages.reloadedLegacy);
                if (sender instanceof Player p) {
                    playSoundIf(p, settings.reloadSound, 0.9f, 1.2f);
                }
                return true;
            }
            return false;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.playerOnlyLegacy);
            return true;
        }

        if (cmd.equals("setspawn")) {
            if (!player.hasPermission("nextspawn.admin")) {
                player.sendMessage(messages.noPermissionLegacy);
                return true;
            }
            Location now = player.getLocation(settings.tmpLoc());
            setSpawn(now);
            spawnLocation = new Location(now.getWorld(), now.getX(), now.getY(), now.getZ(), now.getYaw(), now.getPitch());
            String msg = messages.spawnSetLegacy
                .replace("{world}", player.getWorld().getName())
                .replace("{x}", format1(now.getX()))
                .replace("{y}", format1(now.getY()))
                .replace("{z}", format1(now.getZ()));
            player.sendMessage(msg);
            playSoundIf(player, settings.spawnSetSound, 0.4f, 1.2f);
            return true;
        }

        if (cmd.equals("spawn")) {
            if (!player.hasPermission("nextspawn.use")) {
                player.sendMessage(messages.noPermissionLegacy);
                return true;
            }
            Location loc = spawnLocation;
            if (loc == null) {
                player.sendMessage(messages.spawnNotSetLegacy);
                playSoundIf(player, settings.spawnNotSetSound, 0.6f, 1.0f);
                return true;
            }
            startTeleport(player, loc);
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("nextspawn")) {
            if (args.length == 1) {
                String p = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
                if ("reload".startsWith(p)) {
                    return List.of("reload");
                }
            }
        }
        return Collections.emptyList();
    }

    private void startTeleport(Player player, Location loc) {
        UUID uuid = player.getUniqueId();
        CountdownTp prev = pendingTp.remove(uuid);
        if (prev != null) {
            prev.cancel();
        }

        int delay = settings.teleportDelaySeconds;
        if (delay <= 0) {
            doTeleport(player, loc);
            return;
        }

        CountdownTp tp = new CountdownTp(this, player, loc, delay);
        pendingTp.put(uuid, tp);
        tp.start();
    }

    private void doTeleport(Player player, Location loc) {
        Bukkit.getRegionScheduler().run(this, loc, task -> player.teleportAsync(loc).thenAccept(ok -> {
            if (ok) {
                player.sendActionBar(messages.teleportSuccess);
                emitTeleportSuccessEffects(player);
                playSoundIf(player, settings.teleportSound, settings.teleportSoundVolume, settings.teleportSoundPitch);
            } else {
                player.sendActionBar(messages.teleportFailed);
                playSoundIf(player, settings.teleportFailedSound, 0.8f, 0.8f);
            }
            pendingTp.remove(player.getUniqueId());
        }));
    }

    private void emitTeleportSuccessEffects(Player player) {
        Settings s = settings;
        if (s.teleportSuccessParticle == null || s.teleportSuccessParticleCount <= 0) {
            return;
        }
        Location loc = player.getLocation(s.tmpLoc());
        loc.add(0, 1.0, 0);
        player.spawnParticle(s.teleportSuccessParticle, loc, s.teleportSuccessParticleCount, 0.45, 0.8, 0.45, 0.01);
    }

    private void emitCountdownEffects(Player player) {
        Settings s = settings;
        if (s.countdownSound != null) {
            playSoundIf(player, s.countdownSound, s.countdownSoundVolume, s.countdownSoundPitch);
        }
        if (s.countdownParticle != null && s.countdownParticleCount > 0) {
            Location loc = player.getLocation(s.tmpLoc());
            loc.add(0, 1.0, 0);
            player.spawnParticle(s.countdownParticle, loc, s.countdownParticleCount, 0.35, 0.6, 0.35, 0.01);
        }
    }

    private static void playSoundIf(Player player, Sound sound, float volume, float pitch) {
        if (sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private Location loadSpawn() {
        ConfigurationSection sec = getConfig().getConfigurationSection("spawn");
        if (sec == null) {
            return null;
        }
        String worldName = sec.getString("world", null);
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = sec.getDouble("x");
        double y = sec.getDouble("y");
        double z = sec.getDouble("z");
        float yaw = (float) sec.getDouble("yaw");
        float pitch = (float) sec.getDouble("pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void setSpawn(Location loc) {
        getConfig().set("spawn.world", loc.getWorld() != null ? loc.getWorld().getName() : "world");
        getConfig().set("spawn.x", loc.getX());
        getConfig().set("spawn.y", loc.getY());
        getConfig().set("spawn.z", loc.getZ());
        getConfig().set("spawn.yaw", loc.getYaw());
        getConfig().set("spawn.pitch", loc.getPitch());
        requestConfigSave();
    }

    private void requestConfigSave() {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask prev = pendingConfigSave;
        if (prev != null) {
            prev.cancel();
        }
        pendingConfigSave = Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            pendingConfigSave = null;
            saveConfig();
        }, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Settings s = settings;
        if (!s.voidRescueEnabled) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        Location spawn = spawnLocation;
        if (spawn == null) {
            return;
        }
        if (player.getWorld() != spawn.getWorld()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastVoidRescueMs.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < s.voidRescueCooldownMs) {
            return;
        }
        lastVoidRescueMs.put(player.getUniqueId(), now);

        event.setCancelled(true);
        Bukkit.getRegionScheduler().run(this, spawn, task -> player.teleportAsync(spawn).thenAccept(ok -> {
            if (ok) {
                player.sendActionBar(messages.teleportSuccess);
                emitTeleportSuccessEffects(player);
                playSoundIf(player, settings.teleportSound, settings.teleportSoundVolume, settings.teleportSoundPitch);
            }
        }));
    }

    private static String raw(String s) {
        return s == null ? "" : s;
    }

    private static String applyColors(String s) {
        String withAmp = ChatColor.translateAlternateColorCodes('&', raw(s));
        return translateHexColors(withAmp);
    }

    private static String translateHexColors(String s) {
        String in = raw(s);
        StringBuilder out = new StringBuilder(in.length() + 16);
        int i = 0;
        while (i < in.length()) {
            char c = in.charAt(i);
            if (c == '#' && i + 6 < in.length() && isHex6(in, i + 1)) {
                out.append('§').append('x');
                for (int j = 0; j < 6; j++) {
                    out.append('§').append(Character.toLowerCase(in.charAt(i + 1 + j)));
                }
                i += 7;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex6(String s, int start) {
        for (int j = 0; j < 6; j++) {
            char c = s.charAt(start + j);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Component component(String legacy) {
        return LEGACY_SECTION.deserialize(raw(legacy));
    }

    private static String format1(double v) {
        long scaled = Math.round(v * 10.0d);
        long integer = scaled / 10L;
        long frac = Math.abs(scaled % 10L);
        return integer + "." + frac;
    }

    private static final class CountdownTp {
        private final NextSpawn plugin;
        private final UUID playerId;
        private final Location target;
        private final Location startLoc;
        private final Location tmpLoc;
        private volatile int seconds;
        private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask task;

        private CountdownTp(NextSpawn plugin, Player player, Location target, int seconds) {
            this.plugin = plugin;
            this.playerId = player.getUniqueId();
            this.target = target;
            this.startLoc = player.getLocation().clone();
            this.tmpLoc = new Location(player.getWorld(), 0, 0, 0);
            this.seconds = seconds;
        }

        private void start() {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                plugin.pendingTp.remove(playerId);
                return;
            }
            player.sendActionBar(plugin.messages.teleportCount.forSeconds(seconds));
            plugin.emitCountdownEffects(player);
            task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tick(), 20L, 20L);
        }

        private void tick() {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                cancel();
                plugin.pendingTp.remove(playerId);
                return;
            }
            if (plugin.settings.cancelOnMove && moved(player)) {
                cancel();
                plugin.pendingTp.remove(playerId);
                player.sendActionBar(plugin.messages.teleportCancelled);
                playSoundIf(player, plugin.settings.teleportCancelledSound, 0.9f, 1.0f);
                return;
            }
            seconds--;
            if (seconds > 0) {
                player.sendActionBar(plugin.messages.teleportCount.forSeconds(seconds));
                plugin.emitCountdownEffects(player);
                return;
            }
            cancel();
            plugin.doTeleport(player, target);
        }

        private boolean moved(Player player) {
            Location now = player.getLocation(tmpLoc);
            if (now.getWorld() != startLoc.getWorld()) {
                return true;
            }
            double dx = startLoc.getX() - now.getX();
            double dy = startLoc.getY() - now.getY();
            double dz = startLoc.getZ() - now.getZ();
            return (dx * dx + dy * dy + dz * dz) > plugin.settings.cancelMoveDistanceSquared;
        }

        private void cancel() {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask t = task;
            if (t != null) {
                t.cancel();
            }
        }
    }

    private static final class CountdownTemplate {
        private final Component[] bySecond;

        private CountdownTemplate(Component[] bySecond) {
            this.bySecond = bySecond;
        }

        private Component forSeconds(int seconds) {
            if (seconds <= 0) {
                return bySecond[0];
            }
            int idx = Math.min(seconds, bySecond.length - 1);
            return bySecond[idx];
        }

        private static CountdownTemplate compile(String template, String token, int maxSeconds) {
            String t = raw(template);
            int idx = t.indexOf(token);
            String pre;
            String suf;
            if (idx < 0) {
                pre = t;
                suf = "";
            } else {
                pre = t.substring(0, idx);
                suf = t.substring(idx + token.length());
            }
            int size = Math.max(1, maxSeconds);
            Component[] arr = new Component[size + 1];
            arr[0] = component(pre + "0" + suf);
            for (int i = 1; i <= size; i++) {
                arr[i] = component(pre + i + suf);
            }
            return new CountdownTemplate(arr);
        }
    }

    private static final class Settings {
        private final int teleportDelaySeconds;
        private final boolean cancelOnMove;
        private final double cancelMoveDistanceSquared;
        private final Sound countdownSound;
        private final float countdownSoundVolume;
        private final float countdownSoundPitch;
        private final Particle countdownParticle;
        private final int countdownParticleCount;
        private final Sound teleportSound;
        private final float teleportSoundVolume;
        private final float teleportSoundPitch;
        private final Particle teleportSuccessParticle;
        private final int teleportSuccessParticleCount;
        private final Sound teleportFailedSound;
        private final Sound teleportCancelledSound;
        private final Sound spawnSetSound;
        private final Sound spawnNotSetSound;
        private final Sound reloadSound;
        private final boolean voidRescueEnabled;
        private final long voidRescueCooldownMs;
        private final ThreadLocal<Location> tmpLocation;

        private Settings(int teleportDelaySeconds,
                         boolean cancelOnMove,
                         double cancelMoveDistanceSquared,
                         Sound countdownSound,
                         float countdownSoundVolume,
                         float countdownSoundPitch,
                         Particle countdownParticle,
                         int countdownParticleCount,
                         Sound teleportSound,
                         float teleportSoundVolume,
                         float teleportSoundPitch,
                         Particle teleportSuccessParticle,
                         int teleportSuccessParticleCount,
                         Sound teleportFailedSound,
                         Sound teleportCancelledSound,
                         Sound spawnSetSound,
                         Sound spawnNotSetSound,
                         Sound reloadSound,
                         boolean voidRescueEnabled,
                         long voidRescueCooldownMs) {
            this.teleportDelaySeconds = teleportDelaySeconds;
            this.cancelOnMove = cancelOnMove;
            this.cancelMoveDistanceSquared = cancelMoveDistanceSquared;
            this.countdownSound = countdownSound;
            this.countdownSoundVolume = countdownSoundVolume;
            this.countdownSoundPitch = countdownSoundPitch;
            this.countdownParticle = countdownParticle;
            this.countdownParticleCount = countdownParticleCount;
            this.teleportSound = teleportSound;
            this.teleportSoundVolume = teleportSoundVolume;
            this.teleportSoundPitch = teleportSoundPitch;
            this.teleportSuccessParticle = teleportSuccessParticle;
            this.teleportSuccessParticleCount = teleportSuccessParticleCount;
            this.teleportFailedSound = teleportFailedSound;
            this.teleportCancelledSound = teleportCancelledSound;
            this.spawnSetSound = spawnSetSound;
            this.spawnNotSetSound = spawnNotSetSound;
            this.reloadSound = reloadSound;
            this.voidRescueEnabled = voidRescueEnabled;
            this.voidRescueCooldownMs = voidRescueCooldownMs;
            this.tmpLocation = ThreadLocal.withInitial(() -> new Location(null, 0, 0, 0));
        }

        private Location tmpLoc() {
            return tmpLocation.get();
        }

        private static Settings from(org.bukkit.configuration.file.FileConfiguration config) {
            int delay = Math.max(0, config.getInt("settings.teleport-delay-seconds", 0));
            boolean cancelOnMove = config.getBoolean("settings.cancel-on-move", true);
            double dist = Math.max(0.0, config.getDouble("settings.cancel-move-distance", 0.1));
            double distSq = dist * dist;

            Sound countdownSound = parseSound(config.getString("settings.countdown-sound", ""));
            float countdownVol = (float) Math.max(0.0, config.getDouble("settings.countdown-sound-volume", 0.8));
            float countdownPitch = (float) config.getDouble("settings.countdown-sound-pitch", 1.2);

            Particle countdownParticle = parseParticle(config.getString("settings.countdown-particle", ""));
            int countdownParticleCount = Math.max(0, config.getInt("settings.countdown-particle-count", 12));

            Sound teleportSound = parseSound(config.getString("settings.teleport-sound", ""));
            float teleportVol = (float) Math.max(0.0, config.getDouble("settings.teleport-sound-volume", 0.6));
            float teleportPitch = (float) config.getDouble("settings.teleport-sound-pitch", 1.0);

            Particle teleportSuccessParticle = parseParticle(config.getString("settings.teleport-success-particle", ""));
            int teleportSuccessParticleCount = Math.max(0, config.getInt("settings.teleport-success-particle-count", 30));

            Sound teleportFailedSound = parseSound(config.getString("settings.teleport-failed-sound", ""));
            Sound teleportCancelledSound = parseSound(config.getString("settings.teleport-cancelled-sound", ""));
            Sound spawnSetSound = parseSound(config.getString("settings.spawn-set-sound", ""));
            Sound spawnNotSetSound = parseSound(config.getString("settings.spawn-not-set-sound", ""));
            Sound reloadSound = parseSound(config.getString("settings.reload-sound", ""));

            boolean voidEnabled = config.getBoolean("settings.void-rescue.enabled", true);
            long voidCooldownMs = Math.max(0L, config.getLong("settings.void-rescue.cooldown-seconds", 10L) * 1000L);

            return new Settings(delay, cancelOnMove, distSq,
                countdownSound, countdownVol, countdownPitch,
                countdownParticle, countdownParticleCount,
                teleportSound, teleportVol, teleportPitch,
                teleportSuccessParticle, teleportSuccessParticleCount,
                teleportFailedSound, teleportCancelledSound,
                spawnSetSound, spawnNotSetSound, reloadSound,
                voidEnabled, voidCooldownMs);
        }

        private static Sound parseSound(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                return Sound.valueOf(name);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static Particle parseParticle(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                return Particle.valueOf(name);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class Messages {
        private final String noPermissionLegacy;
        private final String playerOnlyLegacy;
        private final String reloadedLegacy;
        private final String spawnSetLegacy;
        private final String spawnNotSetLegacy;
        private final CountdownTemplate teleportCount;
        private final Component teleportSuccess;
        private final Component teleportFailed;
        private final Component teleportCancelled;

        private Messages(String noPermissionLegacy,
                         String playerOnlyLegacy,
                         String reloadedLegacy,
                         String spawnSetLegacy,
                         String spawnNotSetLegacy,
                         CountdownTemplate teleportCount,
                         Component teleportSuccess,
                         Component teleportFailed,
                         Component teleportCancelled) {
            this.noPermissionLegacy = noPermissionLegacy;
            this.playerOnlyLegacy = playerOnlyLegacy;
            this.reloadedLegacy = reloadedLegacy;
            this.spawnSetLegacy = spawnSetLegacy;
            this.spawnNotSetLegacy = spawnNotSetLegacy;
            this.teleportCount = teleportCount;
            this.teleportSuccess = teleportSuccess;
            this.teleportFailed = teleportFailed;
            this.teleportCancelled = teleportCancelled;
        }

        private static Messages from(org.bukkit.configuration.file.FileConfiguration config, int delaySeconds) {
            String noPerm = applyColors(config.getString("messages.no-permission", ""));
            String playerOnly = applyColors(config.getString("messages.player-only", ""));
            String reloaded = applyColors(config.getString("messages.reloaded", ""));
            String spawnSet = applyColors(config.getString("messages.spawn.set", ""));
            String spawnNotSet = applyColors(config.getString("messages.teleport.spawn-not-set", ""));

            String countTpl = applyColors(config.getString("messages.teleport.count", ""));
            CountdownTemplate teleportCount = CountdownTemplate.compile(countTpl, "{countdown}", Math.max(1, delaySeconds));

            Component teleportSuccess = component(applyColors(config.getString("messages.teleport.success", "")));
            Component teleportFailed = component(applyColors(config.getString("messages.teleport.failed", "")));
            Component teleportCancelled = component(applyColors(config.getString("messages.teleport.cancelled", "")));

            return new Messages(noPerm, playerOnly, reloaded, spawnSet, spawnNotSet, teleportCount, teleportSuccess, teleportFailed, teleportCancelled);
        }
    }
}

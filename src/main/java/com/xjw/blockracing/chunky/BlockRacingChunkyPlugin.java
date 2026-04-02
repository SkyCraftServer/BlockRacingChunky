package com.xjw.blockracing.chunky;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.xjw.blockracing.chunky.commands.PoolCommand;

import top.lqsnow.blockracing.Main;
import top.lqsnow.blockracing.managers.Game;

public class BlockRacingChunkyPlugin extends JavaPlugin {
    private Main blockRacing;
    private ChunkyBridge chunky;
    private final Deque<Candidate> pending = new ArrayDeque<>();
    private InflightGroup inflight;
    private BukkitTask task;
    private final Random random = new Random();
    private boolean pausedByTps;
    private boolean manualPaused;
    private final Map<String, ActiveTask> active = new HashMap<>();

    @Override
    public void onEnable() {
        Plugin plugin = getServer().getPluginManager().getPlugin("BlockRacingPlus");
        if (plugin == null) {
            plugin = getServer().getPluginManager().getPlugin("BlockRacing");
        }
        if (!(plugin instanceof Main)) {
            getLogger().severe("BlockRacing not found or incompatible. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        blockRacing = (Main) plugin;

        saveDefaultConfig();

        chunky = new ChunkyBridge();
        if (!chunky.init()) {
            getLogger().warning("Chunky not available. Pool generator is disabled.");
        }
        chunky.registerListeners(this::handleCompleteEvent, this::handleProgressEvent);

        if (getCommand("brc") != null) {
            PoolCommand command = new PoolCommand(this);
            getCommand("brc").setExecutor(command);
            getCommand("brc").setTabCompleter(command);
        }

        int interval = Math.max(20, getConfig().getInt("tick-interval", 40));
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, interval);
        getLogger().info("BlockRacingChunky enabled.");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        pending.clear();
        inflight = null;
    }

    private void tick() {
        if (chunky == null || !chunky.isReady()) {
            return;
        }
        if (manualPaused) {
            return;
        }
        String worldName = getConfig().getString("world", "world");
        String netherWorldName = getNetherWorldName(worldName);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }

        double tps = getTps();
        double pauseAt = getConfig().getDouble("tps-pause", 18.0);
        double resumeAt = getConfig().getDouble("tps-resume", 20.0);
        if (tps < pauseAt) {
            if (!pausedByTps && isAnyRunning(worldName, netherWorldName)) {
                pauseManagedTasks(worldName, netherWorldName);
                pausedByTps = true;
                getLogger().info(String.format("Chunky paused (tps=%.2f < %.2f)", tps, pauseAt));
            }
            return;
        }
        if (pausedByTps && tps >= resumeAt) {
            resumeManagedTasks(worldName, netherWorldName);
            pausedByTps = false;
            getLogger().info(String.format("Chunky resumed (tps=%.2f >= %.2f)", tps, resumeAt));
        }

        int target = Math.max(0, getConfig().getInt("pool-target", 30));
        int ready = Game.getRandomTeleportPoolSize();
        int want = target - (ready + pending.size() + (inflight != null ? 1 : 0));
        int range = Math.max(1, getConfig().getInt("range", 10000));

        for (int i = 0; i < want; i++) {
            pending.add(new Candidate(rand(range), rand(range)));
        }

        if (inflight == null && !pending.isEmpty() && !isAnyRunning(worldName, netherWorldName)) {
            Candidate next = pending.pollFirst();
            if (next != null) {
                startGeneration(worldName, netherWorldName, next);
            }
        }
    }

    private boolean startGeneration(String worldName, String netherWorldName, Candidate candidate) {
        int radiusChunks = Math.max(1, getConfig().getInt("pregen-radius-chunks", 10));
        int radiusBlocks = radiusChunks * 16;
        String shape = getConfig().getString("shape", "circle");
        String pattern = getConfig().getString("pattern", "concentric");
        boolean startedOverworld = chunky.startTask(worldName, shape, candidate.x, candidate.z, radiusBlocks, radiusBlocks, pattern);
        if (!startedOverworld) {
            return false;
        }

        InflightGroup group = new InflightGroup(candidate);
        inflight = group;
        active.put(worldName, new ActiveTask(group, candidate, worldName, radiusChunks, System.currentTimeMillis(), false));
        group.startedWorlds.add(worldName);
        getLogger().info(String.format("Chunky start: world=%s x=%d z=%d radius=%dc", worldName, candidate.x, candidate.z, radiusChunks));

        World netherWorld = netherWorldName != null ? Bukkit.getWorld(netherWorldName) : null;
        if (netherWorld != null && !netherWorldName.equals(worldName)) {
            int nx = Math.floorDiv(candidate.x, 8);
            int nz = Math.floorDiv(candidate.z, 8);
            boolean startedNether = chunky.startTask(netherWorldName, shape, nx, nz, radiusBlocks, radiusBlocks, pattern);
            if (startedNether) {
                active.put(netherWorldName, new ActiveTask(group, candidate, netherWorldName, radiusChunks, System.currentTimeMillis(), true));
                group.startedWorlds.add(netherWorldName);
                getLogger().info(String.format("Chunky start: world=%s x=%d z=%d radius=%dc (mapped /8)", netherWorldName, nx, nz, radiusChunks));
            } else {
                getLogger().warning(String.format("Chunky nether task start failed: world=%s x=%d z=%d", netherWorldName, nx, nz));
            }
        }
        return true;
    }

    private void handleCompleteEvent(Object event) {
        String worldName = getEventWorld(event);
        if (worldName == null) {
            return;
        }
        ActiveTask taskInfo = active.remove(worldName);
        if (taskInfo == null) {
            return;
        }

        Candidate done = taskInfo.candidate;
        if (taskInfo.group != null) {
            taskInfo.group.startedWorlds.remove(worldName);
            if (taskInfo.group.startedWorlds.isEmpty()) {
                inflight = null;
            }
        }

        long elapsedMs = taskInfo != null ? (System.currentTimeMillis() - taskInfo.startMs) : 0L;
        double seconds = elapsedMs > 0 ? elapsedMs / 1000.0 : 0.0;
        double lastRate = taskInfo != null ? taskInfo.lastRate : 0.0;
        long lastChunks = taskInfo != null ? taskInfo.lastChunks : 0L;
        double avg = (seconds > 0 && lastChunks > 0) ? (lastChunks / seconds) : 0.0;

        if (done != null && !taskInfo.isNether) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                Location loc = world.getHighestBlockAt(done.x, done.z).getLocation();
                loc.setY(loc.getY() + 1);
                Game.addRandomTeleportCandidate(loc);
            }
        }
        getLogger().info(String.format(
                "Chunky done: world=%s seconds=%.2f cps=%.2f lastRate=%.2f chunks=%d",
                worldName, seconds, avg, lastRate, lastChunks));
    }

    private void handleProgressEvent(Object event) {
        String worldName = getEventWorld(event);
        if (worldName == null) {
            return;
        }
        ActiveTask taskInfo = active.get(worldName);
        if (taskInfo == null) {
            return;
        }
        Long chunks = getEventLong(event, "chunks");
        Double rate = getEventDouble(event, "rate");
        if (chunks != null) {
            taskInfo.lastChunks = chunks;
        }
        if (rate != null) {
            taskInfo.lastRate = rate;
        }
    }

    private int rand(int range) {
        return random.nextInt(range * 2 + 1) - range;
    }

    public int getPendingSize() {
        return pending.size();
    }

    public boolean isInflight() {
        return inflight != null;
    }

    public boolean isManualPaused() {
        return manualPaused;
    }

    public boolean isPausedByTps() {
        return pausedByTps;
    }

    public boolean isRunning() {
        String worldName = getConfig().getString("world", "world");
        String netherWorldName = getNetherWorldName(worldName);
        return chunky != null && chunky.isReady() && isAnyRunning(worldName, netherWorldName);
    }

    public void pauseManual() {
        manualPaused = true;
        String worldName = getConfig().getString("world", "world");
        String netherWorldName = getNetherWorldName(worldName);
        pauseManagedTasks(worldName, netherWorldName);
    }

    public void resumeManual() {
        manualPaused = false;
        String worldName = getConfig().getString("world", "world");
        String netherWorldName = getNetherWorldName(worldName);
        resumeManagedTasks(worldName, netherWorldName);
        tick();
    }

    public void reloadLocalConfig() {
        reloadConfig();
    }

    public double getCurrentTps() {
        return getTps();
    }

    private static final class Candidate {
        final int x;
        final int z;

        Candidate(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class ActiveTask {
        final InflightGroup group;
        final Candidate candidate;
        final String worldName;
        final int radiusChunks;
        final long startMs;
        final boolean isNether;
        long lastChunks;
        double lastRate;

        ActiveTask(InflightGroup group, Candidate candidate, String worldName, int radiusChunks, long startMs, boolean isNether) {
            this.group = group;
            this.candidate = candidate;
            this.worldName = worldName;
            this.radiusChunks = radiusChunks;
            this.startMs = startMs;
            this.isNether = isNether;
        }
    }

    private static final class InflightGroup {
        final Candidate candidate;
        final Set<String> startedWorlds = new HashSet<>();

        InflightGroup(Candidate candidate) {
            this.candidate = candidate;
        }
    }

    private String getNetherWorldName(String worldName) {
        return getConfig().getString("nether-world", worldName + "_nether");
    }

    private boolean isAnyRunning(String worldName, String netherWorldName) {
        if (chunky == null || !chunky.isReady()) {
            return false;
        }
        boolean overworldRunning = chunky.isRunning(worldName);
        boolean netherRunning = netherWorldName != null && !netherWorldName.equals(worldName) && chunky.isRunning(netherWorldName);
        return overworldRunning || netherRunning;
    }

    private void pauseManagedTasks(String worldName, String netherWorldName) {
        if (chunky == null || !chunky.isReady()) {
            return;
        }
        chunky.pauseTask(worldName);
        if (netherWorldName != null && !netherWorldName.equals(worldName)) {
            chunky.pauseTask(netherWorldName);
        }
    }

    private void resumeManagedTasks(String worldName, String netherWorldName) {
        if (chunky == null || !chunky.isReady()) {
            return;
        }
        chunky.continueTask(worldName);
        if (netherWorldName != null && !netherWorldName.equals(worldName)) {
            chunky.continueTask(netherWorldName);
        }
    }

    private String getEventWorld(Object event) {
        try {
            Method m = event.getClass().getMethod("world");
            return String.valueOf(m.invoke(event));
        } catch (Exception e) {
            return null;
        }
    }

    private Long getEventLong(Object event, String method) {
        try {
            Method m = event.getClass().getMethod(method);
            Object v = m.invoke(event);
            return (v instanceof Number) ? ((Number) v).longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Double getEventDouble(Object event, String method) {
        try {
            Method m = event.getClass().getMethod(method);
            Object v = m.invoke(event);
            return (v instanceof Number) ? ((Number) v).doubleValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private double getTps() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getTPS");
            Object val = m.invoke(Bukkit.getServer());
            if (val instanceof double[] arr && arr.length > 0) {
                return arr[0];
            }
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Field f = Bukkit.getServer().getClass().getDeclaredField("recentTps");
            f.setAccessible(true);
            Object val = f.get(Bukkit.getServer());
            if (val instanceof double[] arr && arr.length > 0) {
                return arr[0];
            }
        } catch (Exception ignored) {
        }
        return 20.0;
    }

    private static final class ChunkyBridge {
        private Object api;
        private Method isRunning;
        private Method startTask;
        private Method onComplete;
        private Method onProgress;

        boolean init() {
            try {
                Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
                Object chunky = provider.getMethod("get").invoke(null);
                api = chunky.getClass().getMethod("getApi").invoke(chunky);
                isRunning = api.getClass().getMethod("isRunning", String.class);
                startTask = api.getClass().getMethod("startTask", String.class, String.class, double.class, double.class, double.class, double.class, String.class);
                onComplete = api.getClass().getMethod("onGenerationComplete", Consumer.class);
                onProgress = api.getClass().getMethod("onGenerationProgress", Consumer.class);
                return true;
            } catch (Exception e) {
                api = null;
                return false;
            }
        }

        boolean isReady() {
            return api != null;
        }

        boolean isRunning(String world) {
            if (api == null) {
                return false;
            }
            try {
                return (boolean) isRunning.invoke(api, world);
            } catch (Exception e) {
                return false;
            }
        }

        void registerListeners(Consumer<Object> complete, Consumer<Object> progress) {
            if (api == null) {
                return;
            }
            try {
                onComplete.invoke(api, (Consumer<Object>) complete::accept);
                onProgress.invoke(api, (Consumer<Object>) progress::accept);
            } catch (Exception e) {
            }
        }

        boolean startTask(String world, String shape, double cx, double cz, double rx, double rz, String pattern) {
            if (api == null) {
                return false;
            }
            try {
                return (boolean) startTask.invoke(api, world, shape, cx, cz, rx, rz, pattern);
            } catch (Exception e) {
                return false;
            }
        }

        boolean pauseTask(String world) {
            if (api == null) {
                return false;
            }
            try {
                Method pauseTask = api.getClass().getMethod("pauseTask", String.class);
                return (boolean) pauseTask.invoke(api, world);
            } catch (Exception e) {
                return false;
            }
        }

        boolean continueTask(String world) {
            if (api == null) {
                return false;
            }
            try {
                Method continueTask = api.getClass().getMethod("continueTask", String.class);
                return (boolean) continueTask.invoke(api, world);
            } catch (Exception e) {
                return false;
            }
        }
    }
}

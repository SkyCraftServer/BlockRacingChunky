package com.xjw.blockracing.chunky;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
    private Candidate inflight;
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
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }

        double tps = getTps();
        double pauseAt = getConfig().getDouble("tps-pause", 18.0);
        double resumeAt = getConfig().getDouble("tps-resume", 20.0);
        if (tps < pauseAt) {
            if (!pausedByTps && chunky.isRunning(worldName)) {
                chunky.pauseTask(worldName);
                pausedByTps = true;
                getLogger().info(String.format("Chunky paused (tps=%.2f < %.2f)", tps, pauseAt));
            }
            return;
        }
        if (pausedByTps && tps >= resumeAt) {
            if (!chunky.isRunning(worldName)) {
                chunky.continueTask(worldName);
            }
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

        if (inflight == null && !pending.isEmpty() && !chunky.isRunning(worldName)) {
            Candidate next = pending.pollFirst();
            if (next != null) {
                startGeneration(worldName, next);
            }
        }
    }

    private boolean startGeneration(String worldName, Candidate candidate) {
        int radiusChunks = Math.max(1, getConfig().getInt("pregen-radius-chunks", 10));
        int radiusBlocks = radiusChunks * 16;
        String shape = getConfig().getString("shape", "circle");
        String pattern = getConfig().getString("pattern", "concentric");
        boolean started = chunky.startTask(worldName, shape, candidate.x, candidate.z, radiusBlocks, radiusBlocks, pattern);
        if (started) {
            inflight = candidate;
            active.put(worldName, new ActiveTask(candidate, radiusChunks, System.currentTimeMillis()));
            getLogger().info(String.format("Chunky start: world=%s x=%d z=%d radius=%dc", worldName, candidate.x, candidate.z, radiusChunks));
        }
        return started;
    }

    private void handleCompleteEvent(Object event) {
        String worldName = getEventWorld(event);
        if (worldName == null) {
            return;
        }
        ActiveTask taskInfo = active.remove(worldName);
        Candidate done = taskInfo != null ? taskInfo.candidate : inflight;
        inflight = null;
        long elapsedMs = taskInfo != null ? (System.currentTimeMillis() - taskInfo.startMs) : 0L;
        double seconds = elapsedMs > 0 ? elapsedMs / 1000.0 : 0.0;
        double lastRate = taskInfo != null ? taskInfo.lastRate : 0.0;
        long lastChunks = taskInfo != null ? taskInfo.lastChunks : 0L;
        double avg = (seconds > 0 && lastChunks > 0) ? (lastChunks / seconds) : 0.0;

        if (done != null) {
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
        return chunky != null && chunky.isReady() && chunky.isRunning(worldName);
    }

    public void pauseManual() {
        manualPaused = true;
        String worldName = getConfig().getString("world", "world");
        if (chunky != null && chunky.isReady()) {
            chunky.pauseTask(worldName);
        }
    }

    public void resumeManual() {
        manualPaused = false;
        String worldName = getConfig().getString("world", "world");
        if (chunky != null && chunky.isReady()) {
            chunky.continueTask(worldName);
        }
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
        final Candidate candidate;
        final int radiusChunks;
        final long startMs;
        long lastChunks;
        double lastRate;

        ActiveTask(Candidate candidate, int radiusChunks, long startMs) {
            this.candidate = candidate;
            this.radiusChunks = radiusChunks;
            this.startMs = startMs;
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

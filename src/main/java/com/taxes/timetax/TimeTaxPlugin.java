package com.yourname.timetax;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;

import java.util.*;

public class TimeTaxPlugin extends JavaPlugin {

    private PlayerPointsAPI pointsAPI;
    private final Map<UUID, Long> joinTimestamps = new HashMap<>();
    private final Map<UUID, Long> remainingTime = new HashMap<>();
    private final Map<UUID, BukkitRunnable> sidebarTasks = new HashMap<>();
    private final Map<UUID, Scoreboard> sideboards = new HashMap<>();

    private static final long FULL_CYCLE_TICKS = 20L * 60L * 20L; // 20 mins in ticks

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("PlayerPoints") == null) {
            getLogger().severe("PlayerPoints not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        pointsAPI = PlayerPoints.getInstance().getAPI();

        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            startTracking(player);
            startSidebar(player);
        }

        getLogger().info("TimeTax enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel all sidebar update tasks
        sidebarTasks.values().forEach(BukkitRunnable::cancel);
        sidebarTasks.clear();
        sideboards.clear();
    }

    private void startTracking(Player player) {
        UUID uuid = player.getUniqueId();

        joinTimestamps.putIfAbsent(uuid, System.currentTimeMillis());
        remainingTime.putIfAbsent(uuid, FULL_CYCLE_TICKS);
    }

    private void pauseTracking(UUID uuid) {
        Long joinTime = joinTimestamps.get(uuid);
        if (joinTime == null) return;

        long elapsedTicks = (System.currentTimeMillis() - joinTime) / 50L;
        long timeLeft = remainingTime.getOrDefault(uuid, FULL_CYCLE_TICKS) - elapsedTicks;
        remainingTime.put(uuid, Math.max(timeLeft, 0L));
        joinTimestamps.remove(uuid);
    }

    private void resumeTracking(UUID uuid) {
        joinTimestamps.put(uuid, System.currentTimeMillis());
    }

    private void startSidebar(Player player) {
        UUID uuid = player.getUniqueId();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = manager.getNewScoreboard();

        Objective objective = board.registerNewObjective("timetax", "dummy", "§eTime Tax");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        objective.getScore("§7--------------").setScore(5);
        objective.getScore("§fNext tax in:").setScore(4);
        objective.getScore("§fYour balance:").setScore(2);

        Score timeScore = objective.getScore("§aLoading...");
        timeScore.setScore(3);

        Score balanceScore = objective.getScore("§aFetching...");
        balanceScore.setScore(1);

        player.setScoreboard(board);
        sideboards.put(uuid, board);

        BukkitRunnable sidebarUpdater = new BukkitRunnable() {
            String lastTimeLine = "§aLoading...";
            String lastBalanceLine = "§aFetching...";

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                Long joinTime = joinTimestamps.get(uuid);
                if (joinTime == null) {
                    joinTime = System.currentTimeMillis();
                    joinTimestamps.put(uuid, joinTime);
                }
                long elapsedTicks = (System.currentTimeMillis() - joinTime) / 50L;
                long timeLeft = remainingTime.getOrDefault(uuid, FULL_CYCLE_TICKS) - elapsedTicks;

                if (timeLeft < 0) timeLeft = 0;

                long seconds = timeLeft / 20;
                long minutes = seconds / 60;
                long secs = seconds % 60;

                String timeLine = String.format("§a%dm %02ds", minutes, secs);
                String balanceLine = "§a" + pointsAPI.look(uuid);

                if (!timeLine.equals(lastTimeLine)) {
                    board.resetScores(lastTimeLine);
                    objective.getScore(timeLine).setScore(3);
                    lastTimeLine = timeLine;
                }

                if (!balanceLine.equals(lastBalanceLine)) {
                    board.resetScores(lastBalanceLine);
                    objective.getScore(balanceLine).setScore(1);
                    lastBalanceLine = balanceLine;
                }
            }
        };

        sidebarUpdater.runTaskTimer(this, 0L, 20L);
        sidebarTasks.put(uuid, sidebarUpdater);
    }

    private void stopSidebar(UUID uuid) {
        BukkitRunnable task = sidebarTasks.remove(uuid);
        if (task != null) task.cancel();
        sideboards.remove(uuid);
    }

    private void chargeTax(Player player) {
        UUID uuid = player.getUniqueId();
        int points = pointsAPI.look(uuid);
        int tax = (int) Math.ceil(points * 0.08);

        if (tax > 0) {
            pointsAPI.take(player, tax);
            player.sendMessage("§cTimeTax §7- 8% tax deducted: §c" + tax + " points.");
        } else {
            player.sendMessage("§cTimeTax §7- You have no points to tax.");
        }
    }

    private class PlayerListener implements org.bukkit.event.Listener {

        @org.bukkit.event.EventHandler
        public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();

            resumeTracking(uuid);
            startSidebar(player);
        }

        @org.bukkit.event.EventHandler
        public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();

            pauseTracking(uuid);
            stopSidebar(uuid);
        }
    }

    // Every 20 minutes, run tax on each online player individually based on their timers.
    @Override
    public void onLoad() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();

                    Long joinTime = joinTimestamps.get(uuid);
                    if (joinTime == null) continue;

                    long elapsedTicks = (System.currentTimeMillis() - joinTime) / 50L;
                    long timeLeft = remainingTime.getOrDefault(uuid, FULL_CYCLE_TICKS) - elapsedTicks;

                    if (timeLeft <= 0) {
                        // Time to take tax
                        chargeTax(player);

                        // Reset timer for player
                        joinTimestamps.put(uuid, System.currentTimeMillis());
                        remainingTime.put(uuid, FULL_CYCLE_TICKS);
                    }
                }
            }
        }.runTaskTimer(this, 20L * 60 * 20, 20L * 60 * 20); // every 20 mins
    }
}

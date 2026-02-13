package com.xjw.blockracing.chunky.commands;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.xjw.blockracing.chunky.BlockRacingChunkyPlugin;

import top.lqsnow.blockracing.managers.Game;

public class PoolCommand implements CommandExecutor, TabCompleter {
    private final BlockRacingChunkyPlugin plugin;

    public PoolCommand(BlockRacingChunkyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "pause" -> {
                plugin.pauseManual();
                sender.sendMessage(color("&e[BRChunky] &a已手动暂停生成。"));
                return true;
            }
            case "resume", "start" -> {
                plugin.resumeManual();
                sender.sendMessage(color("&e[BRChunky] &a已恢复生成。"));
                return true;
            }
            case "reload" -> {
                plugin.reloadLocalConfig();
                sender.sendMessage(color("&e[BRChunky] &a配置已重载。"));
                return true;
            }
            case "about" -> {
                sender.sendMessage(color("&e[BRChunky] &a作者：&fxiaojiuwo233"));
                sender.sendMessage(color("&e[BRChunky] &aGitHub：&fhttps://github.com/xiaojiuwo233/BlockRacingChunky"));
                return true;
            }
            case "status", "pool" -> {
                int ready = Game.getRandomTeleportPoolSize();
                sender.sendMessage(color("&6随机传送池状态"));
                sender.sendMessage(color("&7ready: &f" + ready
                        + " &7pending: &f" + plugin.getPendingSize()
                        + " &7inflight: &f" + plugin.isInflight()));
                sender.sendMessage(color("&7running: &f" + plugin.isRunning()
                        + " &7manualPaused: &f" + plugin.isManualPaused()
                        + " &7tpsPaused: &f" + plugin.isPausedByTps()
                        + " &7tps: &f" + String.format("%.2f", plugin.getCurrentTps())));
                return true;
            }
            default -> {
                sender.sendMessage(color("&e[BRChunky] &7用法：&f/brc <status|pause|resume|start|reload|about>"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            list.add("status");
            list.add("pause");
            list.add("resume");
            list.add("start");
            list.add("reload");
            list.add("about");
        }
        return list;
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}

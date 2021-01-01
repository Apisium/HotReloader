package cn.apisium.hotreloader;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.PluginClassLoader;
import org.bukkit.plugin.java.annotation.command.Commands;
import org.bukkit.plugin.java.annotation.permission.*;
import org.bukkit.plugin.java.annotation.plugin.*;
import org.bukkit.plugin.java.annotation.plugin.author.Author;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

@SuppressWarnings("unused")
@Plugin(name = "HotReloader", version = "1.0")
@Description("Auto reload your plugin.")
@Author("Shirasawa")
@Website("https://apisium.cn")
@ApiVersion(ApiVersion.Target.v1_13)
@Permissions(@Permission(name = "hotreloader.use", defaultValue = PermissionDefault.OP))
@Commands(@org.bukkit.plugin.java.annotation.command.Command(name = "setdevpath", aliases = { "sdp" }, permission = "hotreloader.use", permissionMessage = "§e[HotReloader]: §cYou do not have permission to do this!"))
public class Main extends JavaPlugin {
    private final CommandSender logger = getServer().getConsoleSender();
    private final PluginManager pm = getServer().getPluginManager();
    private BukkitTask task;
    private WatchKey watchKey;
    org.bukkit.plugin.Plugin plugin;

    private final List<Plugin> plugins = getField(pm, "plugins");
    private final Map<String, Plugin> names = getField(pm, "lookupNames");
    private final SimpleCommandMap commandMap = getField(pm, "commandMap");
    private final Map<String, Command> commands = getField(SimpleCommandMap.class, commandMap, "knownCommands");

    private static <T> T getField(final Object obj, final String name) {
        return getField(obj.getClass(), obj, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(final Class<?> clazz, final Object obj, final String name) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void onEnable() {
        getServer().getPluginCommand("setdevpath").setExecutor(this);
    }

    @SuppressWarnings({ "NullableProblems", "unchecked" })
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e[HotReloader]: §cArguments must be plugin path!");
            return true;
        }
        final File file = new File(String.join(" ", args));
        final String abs = file.getAbsolutePath();
        final String fn = file.getName();
        if (!file.exists()) {
            sender.sendMessage("§e[HotReloader]: §cThis path is not exists: §b" + abs);
            return true;
        }
        if (watchKey != null) {
            watchKey.cancel();
            watchKey = null;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        try {
            plugin = Objects.requireNonNull(pm.loadPlugin(file));
            pm.enablePlugin(plugin);
        } catch (Exception e) {
            e.printStackTrace();
            if (logger != sender) sender.sendMessage("§e[HotReloader]: §cFail to reload plugin!");
            logger.sendMessage("§e[HotReloader]: §cFail to reload plugin!");
            return true;
        }
        task = getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                watchKey = file.toPath().getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                logger.sendMessage("§e[HotReloader]: §aLoad plugin successfully: §b" + abs);
                if (logger != sender) sender.sendMessage("§e[HotReloader]: §aLoad plugin successfully: §b" + abs);
                while (watchKey.isValid()) {
                    if (watchKey.pollEvents().stream().anyMatch(it -> ((WatchEvent<Path>) it).context().toString().equals(fn))) {
                        try {
                            getPluginLoader().getPluginDescription(file);
                        } catch (Exception ignored) {
                            continue;
                        }
                        try {
                            if (pm.isPluginEnabled(plugin)) unload(plugin);
                            plugin = Objects.requireNonNull(pm.loadPlugin(file));
                            pm.enablePlugin(plugin);
                            getServer().broadcast("§e[HotReloader]: §aReload plugin successfully!", "hotreloader.use");
                        } catch (Exception e) {
                            e.printStackTrace();
                            getServer().broadcast("§e[HotReloader]: §cFail to reload plugin!", "hotreloader.use");
                        }
                    }
                    if (!watchKey.reset()) break;
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.sendMessage("§e[HotReloader]: §cFail to reload plugin!");
                if (logger != sender) sender.sendMessage("§e[HotReloader]: §cFail to reload plugin!");
            }
        });
        return true;
    }

    @Override
    public void onDisable() {
        if (watchKey != null) {
            watchKey.cancel();
            watchKey = null;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    public void unload(final org.bukkit.plugin.Plugin plugin) throws Exception {
        String name = plugin.getName();
        pm.disablePlugin(plugin);
        plugins.remove(plugin);
        names.remove(name);

        final Iterator<Map.Entry<String, Command>> it = commands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Command> entry = it.next();
            if (entry.getValue() instanceof PluginCommand) {
                PluginCommand c = (PluginCommand) entry.getValue();
                if (c.getPlugin() == plugin) {
                    c.unregister(commandMap);
                    it.remove();
                }
            }
        }

        final ClassLoader cl = plugin.getClass().getClassLoader();
        if (cl instanceof PluginClassLoader) {
            Field pluginField = cl.getClass().getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(cl, null);
            Field pluginInitField = cl.getClass().getDeclaredField("pluginInit");
            pluginInitField.setAccessible(true);
            pluginInitField.set(cl, null);
            ((PluginClassLoader) cl).close();
        }
        this.plugin = null;
        System.gc();
    }
}

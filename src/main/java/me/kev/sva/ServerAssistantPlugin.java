package me.kev.sva;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.HandlerList;

import me.kev.sva.chat.ChatListener;
import me.kev.sva.chat.ConversationManager;
import me.kev.sva.commands.CommandManager;
import me.kev.sva.constants.Constants;
import me.kev.sva.utils.MessageSender;

public final class ServerAssistantPlugin extends JavaPlugin {

    private ConversationManager conversationManager;
    private ChatListener chatListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateLegacyAiDefaults();

        // Register command handler
        CommandManager commandManager = new CommandManager(this);
        if (getCommand("sva") != null) {
            getCommand("sva").setExecutor(commandManager);
            getCommand("sva").setTabCompleter(commandManager);
        }

        initializePlugin();

        Bukkit.getConsoleSender().sendMessage(Constants.ASCII_LOGO);
        MessageSender.Success("Plugin enabled successfully.");
    }


    /**
     * Migrates only known 1.2.0 default AI settings. Real user secrets/custom
     * model selections are never overwritten. This makes a drop-in 1.2 -> 1.3
     * update point at Gemini without requiring users to delete their full config.
     */
    private void migrateLegacyAiDefaults() {
        boolean changed = false;

        String keyEnv = getConfig().getString("api-key-env");
        if (keyEnv == null || keyEnv.isBlank() || "OPENAI_API_KEY".equals(keyEnv)) {
            getConfig().set("api-key-env", "GEMINI_API_KEY");
            changed = true;
        }

        String apiKey = getConfig().getString("api-key");
        if (apiKey == null || apiKey.isBlank() || "YOUR_API_KEY_HERE".equals(apiKey)) {
            getConfig().set("api-key", "YOUR_GEMINI_API_KEY_HERE");
            changed = true;
        }

        String model = getConfig().getString("ai-model");
        if (model == null || model.isBlank() || "gpt-4o-mini".equalsIgnoreCase(model)) {
            getConfig().set("ai-model", "gemini-3.7-flash");
            changed = true;
        }

        String baseUrl = getConfig().getString("api-base-url");
        if (baseUrl == null || baseUrl.isBlank()) {
            getConfig().set(
                "api-base-url",
                "https://generativelanguage.googleapis.com/v1beta/openai/");
            changed = true;
        }

        if (changed) {
            saveConfig();
            getLogger().info("Migrated legacy AI defaults to Gemini 3.7 Flash.");
        }
    }

    void initializePlugin() {
        // If already initialized, shut down previous services and unregister listener
        if (conversationManager != null) {
            try {
                conversationManager.shutdown();
            } catch (Exception ignored) {
            }
            conversationManager = null;
        }

        if (chatListener != null) {
            try {
                HandlerList.unregisterAll(chatListener);
            } catch (Exception ignored) {
            }
            chatListener = null;
        }

        // Create new conversation manager (reads updated config) and register listener
        conversationManager = new ConversationManager(this);
        chatListener = new ChatListener(this, conversationManager);
        getServer().getPluginManager().registerEvents(chatListener, this);
    }

    public ConversationManager getConversationManager() {
        return conversationManager;
    }

    /**
     * Public reload helper used by the command handler to reload config and
     * reinitialize.
     */
    public void reloadPlugin() {
        reloadConfig();
        initializePlugin();
    }

    @Override
    public void onDisable() {
        // Shutdown services and unregister listeners
        if (conversationManager != null) {
            try {
                conversationManager.shutdown();
            } catch (Exception ignored) {
            }
            conversationManager = null;
        }

        if (chatListener != null) {
            try {
                HandlerList.unregisterAll(chatListener);
            } catch (Exception ignored) {
            }
            chatListener = null;
        }

        MessageSender.Error("Plugin Disabled!");
    }

}
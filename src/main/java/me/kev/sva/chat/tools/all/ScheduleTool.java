package me.kev.sva.chat.tools.all;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.scheduler.BukkitTask;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantResponse;
import me.kev.sva.chat.tools.ToolKind;

/**
 * Implements the friend's previously-planned schedule capability without a
 * second AI request: the model supplies the future chat line in its original
 * response and Java broadcasts it later.
 */
public final class ScheduleTool extends Tool {
  private final Map<Integer, BukkitTask> pending = new LinkedHashMap<>();

  public ScheduleTool(ServerAssistantPlugin plugin) {
    super(plugin, "schedule");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.ACTION;
  }

  @Override
  public String usage() {
    int max = Math.max(plugin.getConfig().getInt("tools.schedule.max-delay-seconds", 120), 1);
    return "schedule <seconds> <chat message> — schedule one future Isolda chat line (max " + max
        + "s). Use only when a player explicitly asks for a delayed reminder/reaction.";
  }

  @Override
  public String execute(String arguments) {
    String raw = arguments == null ? "" : arguments.trim();
    int firstSpace = raw.indexOf(' ');
    if (firstSpace <= 0 || firstSpace >= raw.length() - 1) {
      return "Usage: schedule <seconds> <chat message>";
    }

    int seconds;
    try {
      seconds = Integer.parseInt(raw.substring(0, firstSpace));
    } catch (NumberFormatException ex) {
      return "Schedule delay must be a whole number of seconds.";
    }

    int min = Math.max(plugin.getConfig().getInt("tools.schedule.min-delay-seconds", 2), 1);
    int max = Math.max(plugin.getConfig().getInt("tools.schedule.max-delay-seconds", 120), min);
    if (seconds < min || seconds > max) {
      return "Schedule delay must be between " + min + " and " + max + " seconds.";
    }

    pending.entrySet().removeIf(entry -> entry.getValue().isCancelled());
    int maxPending = Math.max(plugin.getConfig().getInt("tools.schedule.max-pending", 2), 1);
    if (pending.size() >= maxPending) {
      return "Schedule limit reached.";
    }

    AssistantResponse normalized = new AssistantResponse(
        plugin, List.of(raw.substring(firstSpace + 1).trim()), List.of(), false);
    if (normalized.getMessages().isEmpty()) return "Scheduled message was empty after sanitization.";
    String message = normalized.getMessages().getFirst();

    AtomicInteger taskId = new AtomicInteger(-1);
    BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      int id = taskId.get();
      pending.remove(id);
      plugin.getServer().broadcast(AssistantResponse.formatMessage(plugin, message));
    }, Math.max(1L, seconds * 20L));
    taskId.set(task.getTaskId());
    pending.put(task.getTaskId(), task);
    return "Scheduled one chat message in " + seconds + " seconds.";
  }

  @Override
  public void shutdown() {
    for (BukkitTask task : pending.values()) {
      try {
        task.cancel();
      } catch (Exception ignored) {
      }
    }
    pending.clear();
  }
}

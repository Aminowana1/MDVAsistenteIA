package me.kev.sva.integrations;

import org.bukkit.entity.Player;

/** Optional external-plugin source for trusted player profile context. */
public interface PlayerContextIntegration {
  String id();

  boolean enabled();

  boolean available();

  /** Returns compact trusted fields without a leading player name. */
  String build(Player player, ProfileQuery query);

  /** Human-readable runtime status for /sva integrations. */
  String status();
}

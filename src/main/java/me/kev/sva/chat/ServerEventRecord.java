package me.kev.sva.chat;

import java.util.List;
import java.util.Map;

/** One trusted Bukkit event kept as context. Events never trigger an AI request by themselves. */
record ServerEventRecord(
    long timestampMs,
    String type,
    String text,
    List<String> involvedPlayers,
    Map<String, String> playerIdentities) { }

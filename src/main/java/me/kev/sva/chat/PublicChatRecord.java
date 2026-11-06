package me.kev.sva.chat;

import java.util.UUID;

/** One public chat line kept in the rolling local log (costs zero tokens until a scene uses it). */
record PublicChatRecord(
    long timestampMs,
    UUID playerId,
    String playerName,
    String displayName,
    boolean admin,
    String content) { }

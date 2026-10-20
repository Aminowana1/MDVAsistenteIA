package me.kev.sva.chat;

import java.util.UUID;

/** The scene window currently collecting chat after a mention or SMART follow-up. */
record ActiveCapture(
    long sceneId,
    long triggerAt,
    long endsAt,
    UUID triggerPlayerId,
    String triggerPlayerName,
    boolean directMention,
    boolean smartFollowUp) { }

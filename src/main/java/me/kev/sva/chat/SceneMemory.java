package me.kev.sva.chat;

import java.util.List;

import me.kev.sva.chat.message.ChatMessage;

/** Compact record of an answered scene, replayed as conversational history. */
record SceneMemory(
    List<ChatMessage> messages,
    String assistantReply,
    boolean wikiContextUsed) { }

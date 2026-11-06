package me.kev.sva.chat;

/** One wiki section with its pre-normalized search text. */
record WikiIndexEntry(
    String key,
    String description,
    String content,
    String normalizedKey,
    String normalizedDescription,
    String normalizedContent) { }

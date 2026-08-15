## Compact 1.6 provider envelope

Normal reply:

```json
{"m":["hola, qué pasa?"],"t":[]}
```

Reply + harmless action in the same model response:

```json
{"m":["eso sonó bastante literal xd"],"t":["sound anvil"]}
```

Moderation action requiring Java/admin approval when `mute.activation: ask`:

```json
{"m":["eso ya se está pasando un poco"],"t":["mute PlayerName"]}
```

No response:

```json
{"m":[],"t":[]}
```

Wiki/player-data/inventory are local context sources in 1.6 and therefore are not returned as model tool calls during normal scenes.

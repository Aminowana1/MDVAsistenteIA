## Compact provider envelope

Normal reply:

```json
{"m":["hola, qué pasa?"],"t":[],"r":[],"f":[]}
```

Shared group reply where a non-direct participant should inherit the short SMART follow-up window:

```json
{"m":["sí, estoy bien chicos xd"],"t":[],"r":[],"f":["Aminowana"]}
```

`f` is zero-extra-request bookkeeping only. Direct addressers already receive continuity locally; use `f` only for CURRENT related group speakers who clearly joined the same exchange. It never grants ACTION authority.

Reply + harmless action in the same model response:

```json
{"m":["eso sonó bastante literal xd"],"t":["sound anvil"],"r":[],"f":[]}
```

No response:

```json
{"m":[],"t":[],"r":[],"f":[]}
```

Wiki/player-data/inventory are local context sources and therefore are not returned as model tool calls during normal scenes.

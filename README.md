# PrismMC

**Modern software. Built to perform.**

PrismMC improves Minecraft's ecosystem with fast, secure software and an expanding
plugin API, providing quick releases and helpful support as the most widely used,
performant, and stable software available.

## What is PrismMC?

PrismMC is a modern Minecraft server platform written in Kotlin. It works as a
**high-performance proxy** in front of any backend servers (Paper, Fabric, vanilla…)
— and with its **built-in world server**, it can also run as a **standalone server
software** with no external backend at all.

```
Minecraft Client ──► PrismMC proxy ──► Paper / Fabric / vanilla backends
                        │
                        └──► Prism built-in world (embedded, persistent)
```

## Highlights

- **Pool-based routing** — servers are organized into pools with weighted selection
- **Health checking with hysteresis** — no flapping; consecutive failures/successes
  gate state transitions, with automatic fallback rerouting
- **Lifecycle orchestration** — `/prism drain <pool>`, `/prism restart <pool>`,
  crash-threshold auto-restart with configurable restart commands
- **Embedded world server** — in-process Minestom world with Anvil persistence
  (blocks survive restarts) and in-game commands (`/gamemode`, `/tp`, `/setblock`)
- **Purpur-style granular config** — deep `chat`, `players`, `commands`, `lifecycle`,
  `sync`, `metrics`, `advanced`, `messages`, `embedded-world` sections, every key
  documented, with automatic config-version migrations
- **Secure config validation** — errors stop startup, warnings advise; secrets are
  never logged
- **Cross-proxy sync** — Redis pub/sub for multi-proxy deployments
- **Database support** — PostgreSQL/MySQL/SQLite with idempotent migrations
- **WebSocket API** — JWT-secured dashboard API with rate limiting and metrics
- **Prometheus metrics** — JVM and pool metrics out of the box
- **Fast releases & helpful support** — quick iterations, CI-verified builds

## Commands (in game)

| Command | Purpose |
|---|---|
| `/prism status` | Pool/server overview |
| `/prism reload` | Safe config reload |
| `/prism drain <pool>` / `undrain` | Soft-drain a pool |
| `/prism restart <pool>` | Manual restart trigger |
| `/server <name>` | Switch servers |
| `/glist` | Players per pool |

## Building

```bash
./gradlew build        # compile + test + shadowJar
./gradlew runVelocity  # run a dev proxy
```

Requires JDK 21. The production jar lands in `build/libs/`.

## Quick start

1. Drop the jar into your Velocity `plugins/` folder (or run `runVelocity`).
2. Edit `plugins/prism/prism.conf` — pools, servers, and every subsystem.
3. Optional: set `embedded-world.enabled = true` to run PrismMC as a
   standalone server with its own persistent world.

---

PrismMC — modern software, built to perform.

# PrismMC

**Modern software. Built to perform.**

PrismMC improves Minecraft's ecosystem with fast, secure software and an expanding
plugin API, providing quick releases and helpful support as the most widely used,
performant, and stable software available.

## What is PrismMC?

PrismMC is a **standalone Minecraft server software** written in Kotlin — a complete,
self-contained server that boots in seconds and runs light. It can also run in
**proxy mode** in front of existing backends (Paper, Fabric, vanilla…).

```
Standalone (one jar, no dependencies):

  java -jar prism.jar          ← that's the whole server

Proxy mode:

  Minecraft Client ──► PrismMC proxy ──► Paper / Fabric / vanilla backends
                          │
                          └──► Prism built-in world (embedded, persistent)
```

## Benchmark: PrismMC vs Paper (measured, same machine & flags)

Real measurements on Windows (i7-10750H), both servers started with
`-Xms256M -Xmx512M`, vanilla world, one protocol-level bot joining:

| Metric | **PrismMC** | **Paper 1.21.8** |
|---|---|---|
| Boot time | **~3–13 s** | 34–36 s |
| RAM after boot (working set) | **229–240 MB** | 851–857 MB |
| RAM with a player online | **240–241 MB** | 854–935 MB |
| Private memory (player online) | **395 MB** | 892–971 MB |
| TPS (idle & with bot) | **20.0** | 20.0 |
| Heap headroom at 512 MB cap | ~50% used | nearly exhausted at spike |

Takeaways: PrismMC boots ~3x faster, idles at roughly **a quarter of Paper's
memory**, and holds a solid 20 TPS with a player online. Paper's memory spikes
to nearly the 512 MB ceiling during world generation — at the same cap PrismMC
stays comfortably under half of it.

*Scope note: PrismMC (Minestom-based world engine) does not yet implement all
vanilla mechanics (full mob AI, redstone, etc.). These numbers reflect the
lightweight engine's footprint for lobbies, minigame and custom worlds — for a
typical vanilla survival world, run Paper behind PrismMC in proxy mode.*

## Why PrismMC?

| | PrismMC | Paper |
|---|---|---|
| **Boot time** | ~4–5 seconds | ~15–25 seconds |
| **Memory floor** | runs comfortably in 512 MB | typically wants 1–2 GB |
| **Config depth** | Purpur-style deep sections for *every* subsystem | Paper/Purpur configs |
| **Extensibility** | built-in extension API (event bus + isolated classloaders) | plugin API (separate ecosystem) |
| **Modes** | standalone server *and* proxy in one jar | server only |

## Quick start (standalone)

```bash
java -jar prism.jar
```

1. First boot writes `eula.txt` — set `eula=true` (https://aka.ms/MinecraftEULA).
2. Second boot writes `prism.conf` (standalone profile, embedded world enabled)
   and starts listening — default port `25580`, Minecraft 1.21.8.
3. Play. Blocks persist to disk (Anvil format) across restarts.

Console: type commands directly (`list`, `gamemode`, `stop` to save & shut down).

## Quick start (proxy mode)

1. Drop `prism.jar` into a Velocity server's `plugins/` folder.
2. Edit `plugins/prism/prism.conf` — pools, servers, every subsystem.
3. Optional: set `embedded-world.enabled = true` to give the proxy its own
   persistent world with no external backend.

## Highlights

- **Standalone server** — in-process world engine (Minestom), Anvil persistence,
  in-game commands (`/gamemode`, `/tp`, `/setblock`), real console
- **Pool-based routing** — servers organized into pools with weighted selection
- **Health checking with hysteresis** — no flapping; automatic fallback rerouting
- **Lifecycle orchestration** — `/prism drain <pool>`, `/prism restart <pool>`,
  crash-threshold auto-restart with configurable restart commands
- **Extension API** — `PrismExtension` interface, PrismMC event bus
  (`PoolHealthChangeEvent`, `PlayerRouteEvent` (cancellable), `ProxyChatEvent`
  (cancellable) …), isolated classloaders per extension, per-extension data folders
- **Purpur-style granular config** — deep `chat`, `players`, `commands`, `lifecycle`,
  `sync`, `metrics`, `advanced`, `messages`, `embedded-world` sections, every key
  documented, with automatic config-version migrations
- **Secure config validation** — errors stop startup, warnings advise; secrets are
  never logged
- **Cross-proxy sync** — Redis pub/sub for multi-proxy deployments
- **Database support** — PostgreSQL/MySQL/SQLite with idempotent migrations
- **WebSocket API** — JWT-secured dashboard API with rate limiting
- **Prometheus metrics** — JVM and pool metrics out of the box

## Writing an extension

```java
public class HelloExtension implements PrismExtension {
    @Override public String getId() { return "hello"; }

    @Override
    public void onEnable(PrismApi api) {
        api.events().listen(PoolHealthChangeEvent.class, getId(), e ->
            api.logger().info("{} is now {}", e.getServerName(),
                e.getHealthy() ? "ONLINE" : "OFFLINE"));
    }
}
```

Manifest attribute `Prism-Extension: com.example.HelloExtension`, jar into
`extensions/`. Full example in [`examples/hello-extension/`](examples/hello-extension/).

## Building

```bash
./gradlew build        # compile + test + shadowJar → build/libs/prism-*.jar
./gradlew runVelocity  # run a dev proxy
```

Requires JDK 21.

## Commands (in game, proxy mode)

| Command | Purpose |
|---|---|
| `/prism status` | Pool/server overview |
| `/prism reload` | Safe config reload |
| `/prism drain <pool>` / `undrain` | Soft-drain a pool |
| `/prism restart <pool>` | Manual restart trigger |
| `/server <name>` | Switch servers |
| `/glist` | Players per pool |

---

PrismMC — modern software, built to perform.

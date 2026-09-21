# Hello Extension — PrismMC extension example

A minimal [PrismMC](../../../README.md) extension that logs pool health changes.

## Manifest (required)

Your jar's `MANIFEST.MF` must contain:

```
Prism-Extension: com.example.HelloExtension
```

Gradle snippet:

```groovy
jar {
    manifest {
        attributes 'Prism-Extension': 'com.example.HelloExtension'
    }
}
```

## Compile against PrismMC

PrismMC does not yet publish to Maven — compile against the shaded jar:

```groovy
repositories { flatDir { dirs 'libs' } }
dependencies { compileOnly ':Prism-1.0-SNAPSHOT' }
```

## Install

1. `./gradlew build` in the PrismMC repo, copy `build/libs/Prism-1.0-SNAPSHOT.jar` into your build as `libs/`.
2. Build this example, drop the jar into `plugins/prism/extensions/`.
3. Start the proxy — watch for `Enabled extension: hello v1.0`.

## What the API offers

- `api.events()` — the PrismMC event bus (`PoolHealthChangeEvent`, `PoolDrainChangeEvent`,
  `PlayerRouteEvent` (cancellable), `ProxyChatEvent` (cancellable), `EmbeddedWorldStateEvent`)
- `api.pools()` — read-only pool view
- `api.dataFolder()` — your own `extensions/<id>/` folder
- `api.logger()` — a logger prefixed with your extension id

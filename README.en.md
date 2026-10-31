# ModPedia client-core

This branch contains only the loader-neutral Java 8 `client-core` module shared by
Minecraft version adapters.

- Worker implementation and Minecraft/Forge/NeoForge classes are not maintained here.
- The root Gradle build aggregates `:client-core` only and never produces a mod or Worker JAR.
- Worker implementation comes exclusively from `modpedia-refactor-worker-core`.
- Version adapters must provide `net.i2p.crypto:eddsa:0.3.0` at runtime when embedding the
  client-core sources.

Run `./gradlew clean check --no-daemon --no-configuration-cache` to compile Java 8 bytecode,
run the self-test, and verify class major versions. The signing contract is documented in
[`client-core/README.md`](client-core/README.md).

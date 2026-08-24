# ModPedia · Mod Knowledge Assistant

ModPedia is a local knowledge assistant for Minecraft modpacks. It reads manuals from installed content mods, pack-author Wiki files, static quest definitions, and item Tooltips; converts them to traceable Markdown; stores them in a local SQLite/FTS5 knowledge base; and presents either direct Search-only results or optional AI answers.

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ct-yx/modpedia?label=release)](https://github.com/ct-yx/modpedia/releases)

中文：[README.md](README.md) · Website: [GitHub Pages](https://ct-yx.github.io/modpedia/)

Full player-facing mod introduction: [中文](docs/MOD_INTRODUCTION.md) · [English](docs/MOD_INTRODUCTION.en.md)

## v1.4.0 release baseline

| Release asset | Minecraft / loader | Java | Worker |
| --- | --- | --- | --- |
| `modpedia-1.4.0-mc1.21.1-neoforge.jar` | 1.21.1 / NeoForge 21.1.x | 21 | `worker-baseline-3` |
| `modpedia-1.4.0-mc1.20.1-forge.jar` | 1.20.1 / Forge 47.x | 21 | `worker-baseline-3` |
| `modpedia-1.4.0-mc1.12.2-cleanroom.jar` | 1.12.2 / Forge 14.23.5.2847 + Cleanroom 0.3+ compatibility line | game Java 8; Worker Java 21 | `worker-baseline-3` |

The three builds share the product protocol and Worker design, while their client adapters are compiled for their respective loaders. The full `v1.1.0 → v1.4.0` comparison is in [`docs/RELEASE_1.4.0.md`](docs/RELEASE_1.4.0.md). The website contains the [feature matrix](https://ct-yx.github.io/modpedia/#feature-matrix).

Stable technical identifiers: `mod_id=modpedia`, package `io.ctyx.modpedia`, author `ctyx`, and
[Apache License 2.0](LICENSE).

## Core features

- **Local-first retrieval**: one `knowledge.db` using SQLite FTS5; complete Markdown is read from fact tables while document IDs, heading paths, original paths, and page navigation remain traceable.
- **Two modes**: Search-only needs no API key and returns complete paragraphs, heading paths, scores, item facts, and inline source buttons. AI mode can call local tools and search again when evidence is insufficient.
- **Separate Worker JVM**: the game JVM handles UI, registries/Tooltips, and optional mod adapters; the Worker handles scanning, SQLite/FTS5, AI, conversations, static task import, and bulk writes.
- **Four AI protocols**: Chat Completions, native Messages, Responses, and Gemini `generateContent`, with model listing, connection tests, regular/streaming requests, tool continuation, and fallbacks.
- **Local calculation**: quantities, ratios, rounding, and multi-step arithmetic use `BigDecimal` rather than LLM mental math.
- **Optional integrations**: FTB Quests, JEI, and Jade are optional; runtime quest progress is read on demand, JEI recipes are queried rather than copied, and the item catalog supplies names and Tooltips.

### Manual frameworks and content mods

Manual frameworks are prerequisites, not necessarily content. Patchouli, GuideME, Modonomicon/APP, and Mantle
can be absent without preventing ModPedia from loading. To obtain actual text, install content mods
that provide books or add pack-author sources.

| Source | Supported content | Classification |
| --- | --- | --- |
| Patchouli | Books, categories, entries, pages, common page nodes, and `zh_cn → en_us → neutral` fallback | `mod_manual` or overridden to `wiki` |
| GuideME / Guide-API | Markdown, text, language directories, and page indexes; includes the 1.12.2 adapter | `mod_manual` |
| Mantle / Tinkers' Construct (1.12.2) | Mantle/Tinkers' Construct books, sections, and page sources with search and navigation | `mod_manual` |
| Modonomicon / APP JSON | Books, categories, entries, pages, recipe/item/link nodes, and Markdown fallback for unknown nodes | `mod_manual` by default; can be `wiki` |
| Custom Markdown | `custom/**/*.md` and `sources/<source-id>/documents/**/*.md` | `wiki` by default |
| FTB Quests Wiki | Built-in or local Wiki Markdown, separate from static task definitions | `wiki` |

An APP/Modonomicon book can declare:

```json
{
  "knowledge": {
    "content_kind": "wiki",
    "source_id": "pack-guide",
    "collection_id": "example-pack",
    "title": "Pack Guide",
    "priority": 60
  }
}
```

`config/modpedia/knowledge/source-overrides.json` can override classification by
`namespace/book_id`, so the same JSON format does not decide whether a book is a mod manual or a pack Wiki.

### Optional integrations

All integrations below are optional. ModernUI is not a runtime dependency either; the assistant UI uses the target loader's native GUI API.

- **FTB Quests**: imports static task definitions, dependencies, requirements, rewards, and Wiki text. Current completed/in-progress state is read when a task question is asked, not written to the global knowledge base; multiple valid next steps are returned as candidates, and random rewards remain candidates rather than guarantees.
- **JEI**: no recipe database is imported. The model selects workbench, furnace, or another process; workbench/furnace queries are direct, furnace results include processing time, and other processes use a two-stage method/detail query with machine tiers merged. Shift-clicking an item name in an answer can open JEI.
- **Jade and crosshair targets**: the target is frozen when `K` is pressed; after the assistant opens, the user explicitly clicks “Insert” so a covered or changing background UI cannot replace the target.
- **Item catalog**: the loading phase captures registered item names and full Tooltips in the current language. Normal display uses names, Ctrl reveals IDs, and AI can use the catalog as facts before searching manuals.

Without any optional integration, the core assistant, Search-only mode, manual scanning, database, and AI Worker still load. Dedicated Server does not parse client UI or third-party client reflection classes.

## Installation and first use

1. Download the matching JAR from the [v1.4.0 Release](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0).
2. Put it in the current instance's `mods/` directory; do not install a JAR for another Minecraft version.
3. Start the game with the matching Java version and wait for the loading-screen manual/item prefill.
4. Press `K` to open the assistant; press `F9` to rebuild the current instance knowledge base.
5. Choose Search-only or AI mode in Settings. In AI mode configure the endpoint, model, API format, and key. Model listing is optional because some services do not expose `/models`.

See [`INSTALL.md`](INSTALL.md) for the full installation and pack-cleanup rules. The API key is stored as AES-GCM ciphertext derived from a system identifier and decrypted only in memory; logs, conversations, and diagnostics do not record the key or system UUID in plaintext.

## Storage layout and modpack publishing

Runtime state, derived indexes, user settings, and pack-author sources are separated:

```text
<instance>/config/modpedia/
├── runtime/                         # player runtime; remove before publishing a pack
│   ├── conversations/
│   ├── diagnostics/
│   ├── worker/                      # logs, IPC state, temporary payloads
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/knowledge.db*      # derived database, cache, reports
└── knowledge/                       # reusable pack-author source content
    ├── custom/**/*.md
    ├── sources/<source-id>/source.json
    ├── sources/<source-id>/documents/**/*.md
    ├── sources/<source-id>/media.json
    ├── source-overrides.json
    └── search-synonyms.json

~/.modpedia/
├── ai.json                           # encrypted, shared between instances
├── installation-id                   # fallback identifier
└── worker/lib/worker-baseline-3/     # shared by the same Worker baseline
```

Before publishing a modpack:

- Remove the whole `<instance>/config/modpedia/runtime/`, including `knowledge.db*`, generated Markdown, scan caches, conversations, diagnostics, Worker logs, and temporary payloads. They are regenerated on first startup or after `F9`.
- Keep `config/modpedia/knowledge/custom/**/*.md`, `sources/<source-id>/source.json`, `documents/**/*.md`, `media.json`, `source-overrides.json`, and any required `search-synonyms.json`.
- Do not copy user-level `~/.modpedia/ai.json`, `installation-id`, or `worker/lib/`; those belong to the player's machine and are shared by instances.
- Do not commit local JARs, API keys, conversations, `knowledge.db`, diagnostics, or build caches to a modpack.

## Development and release

- Release/Pages branch: `main`; the three code branches maintain only their loader code and tests.
- NeoForge 1.21.1: branch `mainline-1.21.1`.
- Forge 1.20.1: branch `migration/forge-1.20.1`.
- Forge 1.12.2 / Cleanroom 0.3+: branch `migration/1.12.2-common`.
- If Worker dependencies or the protocol change, use [`docs/WORKER_CHANGE_PROTOCOL.md`](docs/WORKER_CHANGE_PROTOCOL.md) to prepare a summary; make the Worker change in the Worker task and then update client adapters.
- Architecture, knowledge-base details, and the next roadmap are in [`docs/`](docs/).

Local verification:

```bash
./gradlew test
./gradlew build
git diff --check
```

See [`docs/RELEASE_1.4.0.md`](docs/RELEASE_1.4.0.md) for the three-version comparison, removals, fixes, and test record.

## License and redistribution

ModPedia is licensed under Apache License 2.0. A third-party modification or redistribution must retain the original author `ctyx`, copyright, and license notices, and clearly identify the changes; it must not advertise a modified build as if it were authored by the original author. Including the unmodified Mod in a modpack does not require an additional modified-build notice.

---

Author: `ctyx` · Technical identifiers: `modpedia` / `io.ctyx.modpedia`

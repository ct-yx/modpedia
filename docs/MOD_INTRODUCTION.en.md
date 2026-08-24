# ModPedia · Mod Knowledge Assistant

> A local knowledge assistant for Minecraft modpacks: turn manuals, pack Wikis, quest definitions, and item Tooltips into searchable, traceable in-game help.

## What is ModPedia?

In a large modpack, the information usually exists—but it is spread across different mod manuals, quest trees, recipe viewers, and pack-author documents. ModPedia builds a local knowledge base, converts these sources into complete Markdown, and keeps document IDs, heading paths, original sources, and page navigation available for verification.

You can search directly or let an optional AI organize an answer from local facts. When AI is unavailable, the core Search-only mode remains usable.

## Core experience

- **Local first**: manuals, Wikis, item Tooltips, and static quest definitions are stored in the current instance's `knowledge.db` and searched with SQLite/FTS5.
- **Traceable sources**: results retain document titles, heading paths, original sources, and inline navigation instead of becoming unverifiable summaries.
- **Search-only mode**: no endpoint, model, or API key is required; it returns complete paragraphs, scores, item facts, and source buttons.
- **AI mode**: the model can call local search, task, recipe, and calculation tools, and search again when evidence is insufficient.
- **Separate Worker**: scanning, database work, AI requests, and conversation storage run in a separate Worker JVM rather than on the game's rendering thread.
- **Three release lines**: Minecraft 1.21.1 NeoForge, 1.20.1 Forge, and a line compatible with both Forge 1.12.2 and Cleanroom 0.3+.

## Supported content sources

Manual frameworks are prerequisites, not necessarily the content itself. The actual book text must come from a content mod or a pack-author source.

| Source | Supported content |
| --- | --- |
| Patchouli | Books, categories, entries, pages, common page nodes, and `zh_cn → en_us → neutral` fallback |
| GuideME / Guide-API | Markdown, text, language directories, page indexes, and the 1.12.2 compatibility adapter |
| Mantle / Tinkers' Construct (1.12.2) | Mantle/Tinkers' Construct books, sections, and page sources with search and navigation |
| Modonomicon / APP JSON | Book hierarchy, entries, pages, recipe/item/link nodes, and Markdown fallback for unknown nodes |
| Custom Markdown | `config/modpedia/knowledge/custom/**/*.md` |
| Pack-author Wiki | `sources/<source-id>/source.json`, Markdown documents, media metadata, and source overrides |
| FTB Quests Wiki | Quest explanations as Wiki content, separate from runtime progress and static definitions |

An APP/Modonomicon book can declare that it belongs to a pack Wiki rather than a mod manual:

```json
{
  "knowledge": {
    "content_kind": "wiki",
    "source_id": "pack-guide",
    "collection_id": "example-pack",
    "title": "Pack Guide"
  }
}
```

The book format and content ownership are therefore independent concepts. Mod manuals, pack guides, and community documents can coexist in the same extensible knowledge base.

## Optional integrations

FTB Quests, JEI, Jade, and all manual mods are optional. Without them, the core assistant, Search-only mode, local knowledge base, and Worker should still load normally.

- **FTB Quests**: imports static definitions, dependencies, requirements, rewards, and Wiki content; reads current player progress on demand for task questions and can return candidate next steps, blockers, and a timeline. Runtime progress is not written into the global knowledge base.
- **JEI**: does not copy the full recipe set into the database. Workbench, furnace, and other processes are queried on demand; furnace results include processing time, and machine tiers are merged. Shift-clicking an item name in an answer can attempt to open JEI.
- **Jade and crosshair targets**: the target is frozen when `K` is pressed; the user confirms insertion with the assistant's “Insert” button so a changing background UI cannot replace the item.
- **Item catalog**: the loading phase imports registered item IDs, names, and full Tooltips in the current language. Normal display uses localized names, Ctrl reveals IDs, and AI can use Tooltips as local facts before searching manuals.

The ModPedia UI is drawn with the target loader's native GUI API and does not require ModernUI.

## AI and privacy

- Supports Chat Completions, native Messages, Responses, and Gemini `generateContent` API shapes.
- The API key is stored in an encrypted field in user-level `~/.modpedia/ai.json`; it is not distributed with a modpack, and different game instances can share the configuration.
- The configuration is decrypted into memory only at startup; logs, conversations, and diagnostics do not record the plaintext API key or system UUID.
- Search-only mode fully bypasses AI requests, does not read API settings, and does not require network access.
- The calculation tool uses `BigDecimal` for quantities, ratios, rounding, and multi-step arithmetic to reduce mental-math errors from the model.

## Installation and use

1. Download the JAR matching your Minecraft version and loader from the [v1.4.0 Release](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0).
2. Put it in the current instance's `mods/` directory; do not mix files for different Minecraft versions.
3. Start the game and wait for the loading phase to prefill manuals and the item catalog.
4. Press `K` to open the assistant and choose Search-only or AI mode in Settings.
5. Press `F9` when the current instance knowledge base needs to be rebuilt.

| File | Target environment | Java |
| --- | --- | --- |
| `modpedia-1.4.0-mc1.21.1-neoforge.jar` | Minecraft 1.21.1 + NeoForge 21.1.x | 21 |
| `modpedia-1.4.0-mc1.20.1-forge.jar` | Minecraft 1.20.1 + Forge 47.x | 21 |
| `modpedia-1.4.0-mc1.12.2-cleanroom.jar` | Minecraft 1.12.2 + Forge 14.23.5.2847 / Cleanroom 0.3+ | game Java 8; Worker Java 21 |

## Modpack author notes

Before publishing a modpack, remove `config/modpedia/runtime/` from the instance. It contains the derived database, caches, conversations, diagnostics, and Worker runtime state. Keep custom Markdown, Wiki sources, media metadata, and source overrides under `config/modpedia/knowledge/`.

Do not package a player's `~/.modpedia/ai.json`, API key, user-level Worker libraries, conversation database, or local `knowledge.db`. See [`INSTALL.md`](../INSTALL.md) and [`README.en.md`](../README.en.md) for the complete directory rules.

## Project and license

- Project: [github.com/ct-yx/modpedia](https://github.com/ct-yx/modpedia)
- Current release: [v1.4.0](https://github.com/ct-yx/modpedia/releases/tag/v1.4.0)
- Technical identifiers: `modpedia` / `io.ctyx.modpedia`
- License: [Apache License 2.0](../LICENSE)

Third-party modifications or redistributions must retain the original author `ctyx`, copyright, and license notices, and clearly identify the changes. A modified build must not be advertised as if it were authored by the original author. Including the unmodified Mod in a modpack is not a third-party modification.

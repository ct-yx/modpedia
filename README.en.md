# ModPedia

ModPedia is a local mod knowledge assistant for Minecraft modpacks. It reads
manual resources from installed mods, converts them to normalized Markdown,
indexes them in SQLite, and presents either direct search results or AI-assisted
answers while preserving links back to the original manual pages.

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ct-yx/modpedia?include_prereleases&label=release)](https://github.com/ct-yx/modpedia/releases)

简体中文版本: [README.md](README.md)

## Current release

| Item | Version / status |
| --- | --- |
| Mod | **v1.1.0** |
| Release status | Official GitHub release |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.244** (compatible with **21.1.x**) |
| Java | **21** |
| Mod ID | **modpedia** |
| Client UI dependency | None; drawn with the native NeoForge GUI API |
| Author | **ctyx** |

The current JAR, checksum, installation guide, and known limitations are
available in the [GitHub Release](https://github.com/ct-yx/modpedia/releases/tag/v1.1.0).
`v1.0.0`, `v1.0.0-fix`, and `v1.0.1` remain historical versions. `v1.1.0`
includes user-level shared AI settings, API key storage protection, Worker
startup caching, and a Mod List icon.

## Quick installation

### Requirements

1. Minecraft **1.21.1**.
2. NeoForge **21.1.x**.
3. Java **21**.

### Installation

1. Download **modpedia-1.1.0.jar**.
2. Put the ModPedia JAR in the instance's **mods/** directory.
3. Start the game and enter a single-player world or server.
4. Wait for the initial knowledge database and item catalog prefill to finish;
   press **F9** when an immediate rebuild is needed.
5. Press **K** in-game to open the assistant.

ModPedia does not bundle Patchouli, GuideME, Modonomicon, or any content mod.
They are optional manual adapters; searchable text comes from the content mods
actually installed in the modpack.

The following integrations are optional:

- **FTB Quests**: The client no longer polls or serializes all quests on every
  tick. When a task question invokes `search_tasks`, the current player runtime
  progress is read first. In single-player, the Worker first reads
  `saves/<world>/ftbquests/<team-uuid>.snbt`; multiplayer or unavailable local
  files fall back to TeamData in the game JVM. The Worker then queries the
  static task database and overlays the runtime result in memory. Each AI
  request reads progress once, and runtime progress is never written to the
  database. The runtime response also contains a task `timeline`: FTBQ
  timestamps are used for started/completed events, while progress changes use
  detection time. The model can therefore list new entries instead of only
  comparing counts. Task Wiki content is imported as a separate
  `content_kind=wiki` source and is not mixed with mod manuals.
- **JEI**: Recipes are not imported into the database. Registered item IDs in
  answers, including model-generated `namespace:path` values, are resolved to
  localized names on demand. Hold Ctrl to show IDs, and Shift-click an item name
  to try opening the JEI recipe screen.
- **Jade**: When Jade is installed, the block or item under the crosshair can be
  recorded. After opening the assistant, one click inserts its item ID. Display
  areas show the localized name by default and the ID while Ctrl is held.
- **Item catalog**: After client registries are ready and before entering the
  main menu, the client writes every item ID, localized name, and full Tooltip
  description for the current language to the `item_catalog` table in the same
  `knowledge.db`. Tens of thousands of records are written by an independent
  I/O thread as an atomic JSONL payload; IPC transfers only the path and the
  Worker performs a short batched transaction. The game tick never assembles a
  large JSON payload. After an item is confirmed, AI and local-search modes
  read this information before continuing with manual search. Language changes
  trigger a new capture only after returning to the main menu; the catalog is
  not continuously scanned in a world.

Without these integrations, the assistant, manual scanner, SQLite search, and
AI client still load normally. The assistant UI itself has no external UI mod
dependency.

## First use

### Search-only mode

Use this mode when no AI API is configured or when you want a fully offline
workflow:

1. Press **K** to open the assistant.
2. Open **Settings**.
3. Set **Work mode** to **Search only**.
4. Enter keywords for a mod, machine, item, or recipe.

This mode reads the local SQLite database directly and returns complete Markdown
paragraphs, heading paths, match scores, and source buttons embedded in the
answer. It does not read the API configuration.

### AI answer mode

Select **AI answer** in Settings and fill in:

- **API URL**: A Chat Completions-compatible API root, usually ending in `/v1`.
  If only a domain is entered, the client adds `/v1` automatically.
- **Model name**: Click **Get model list** on the right, then click the button
  again to switch between returned models.
- **API key**: The Settings field takes priority; the `MODPEDIA_API_KEY`
  environment variable is used only when the field is empty.
- `~/.modpedia/ai.json` is a user-level configuration shared by game instances
  and is never part of a modpack. It never stores the API key in plaintext: it
  stores AES-GCM ciphertext derived from the current system identifier. The
  game process decrypts it on first read and keeps it in memory; a changed
  system identifier clears the encrypted key. If the system identifier cannot
  be read, `~/.modpedia/installation-id` is used as a shared user-level
  fallback. On POSIX systems the directory is `0700` and the file is `0600`.
- No one-by-one model testing is required. Click **Batch test models** at the
  bottom of Settings to probe every model returned by `/models` for ordinary
  requests, tool-call continuation, SSE, and streaming tool continuation. The
  redacted report is written to `config/modpedia/runtime/diagnostics/`.

If the connection test says that the API URL returned webpage content, the URL
points to a web page or service root instead of an API endpoint. Check the `/v1`
path first. Model-list and connection-test logs never write the API key.

Batch testing separates models into **ordinary + tools available** and
**streaming + tools available**. When streaming is enabled, prefer a model that
passes the streaming tool chain. Some image, realtime, or Codex-account-only
models may appear in the list but still be unsuitable for the current Chat
Completions tool-calling chain.

The model can call `search_knowledge`. When recipe, step, prerequisite, or
version evidence is incomplete, it rewrites the query and searches again. The
final answer shows only 3–5 highly relevant source buttons actually returned in
this round and labeled by the model, followed by three suggested questions.

Default search budgets:

| Profile | Max rounds | Results per round | Context limit |
| --- | ---: | ---: | ---: |
| Fast | 1 | 4 | 8,000 characters |
| Standard | 3 | 8 | 16,000 characters |
| Deep | 5 | 12 | 28,000 characters |

## Shortcuts and UI

| Action | Behavior |
| --- | --- |
| **K** | Open or close the assistant window |
| **Esc** | Defocus the input first, otherwise close the current page |
| **Enter** | Send a single-line question |
| **Shift+Enter** | Insert a newline in the input |
| **F8** | Preserve the original cinematic-camera behavior |
| **F9** | Force a local knowledge database rebuild |
| Drag the title bar | Move the floating window |
| Drag an edge or corner | Resize the floating window |

Window behavior:

- Default size: **320×400**; minimum size: **160×110**.
- Maximum size: **720×720**, with each dimension capped at **85%** of the
  viewport.
- History and Settings open inside the same assistant window; no full-screen
  secondary Screen is created.
- Settings fields, history lists, and buttons follow the parent-window bounds
  and are clipped with scissor rectangles.
- The game background remains clear; only a blue, translucent panel is drawn.
- Theme color, transparency, and glow are saved in
  `runtime/assistant-glass.json`.
- High-contrast or reduced-transparency modes fall back to an opaque surface.
- Collapsed input leaves a small entry point in the lower-right corner; the
  expanded state uses a single-line input.

## Manual sources: frameworks and content mods

Patchouli, GuideME, and Modonomicon are primarily manual frameworks or library
mods. Their framework JARs may contain no manual text at all. Coverage depends
on the content-mod JARs that provide the actual resources.

| Format | Scanned content | Source navigation |
| --- | --- | --- |
| Patchouli | Books, categories, entries, pages, and common page nodes | Book / entry |
| GuideME | Markdown pages, language directories, and page indexes | Book / page |
| Modonomicon | Books, categories, entries, pages, and unknown nodes | Book / entry / page |

The scanner preserves:

- Content-mod namespace;
- `sourceType`, `sourcePath`, and version;
- Document ID, heading path, and page-level anchor;
- Chinese, English, and `neutral` fallback information.

Install both the manual framework and the content mod when testing. Installing
only the three framework libraries verifies loading compatibility, but normally
does not increase the amount of searchable text.

## Local knowledge database

Runtime data and modpack fact sources use separate directories. Only the latter
should be kept when publishing a modpack:

~~~text
config/modpedia/
├── runtime/                         # Remove before publishing a modpack
│   ├── conversations/
│   ├── diagnostics/
│   ├── worker/
│   ├── assistant-window.json
│   ├── assistant-glass.json
│   └── knowledge/
│       ├── knowledge.db*            # Derived SQLite search database
│       ├── generated/                # Markdown generated from scanned manuals
│       ├── cache/                    # Build reports and scan cache
│       ├── manifest.json
│       ├── keyword-index.json
│       └── state.json                # JAR and resource fingerprints
└── knowledge/                       # Fact sources kept by modpack authors
    ├── custom/                      # Human-maintained Markdown
    ├── sources/                     # Extensible Wiki source collections
    │   └── <source-id>/source.json + documents/**/*.md
    ├── source-overrides.json
    └── search-synonyms.json         # Optional search synonyms

~/.modpedia/
├── ai.json                           # Shared AI settings; the key is ciphertext
└── installation-id                   # User-level fallback when no system UUID exists
~~~

`~/.modpedia/` is outside each game instance and belongs to the current OS user,
not to a modpack. Different Minecraft versions and modpacks read the same
user-level `ai.json`. Older files at `config/modpedia/ai.json` or
`config/modpedia/runtime/ai.json` are moved here on startup. This keeps personal
AI settings out of modpack archives; the API key is decrypted only into the
current process memory and is excluded from logs and conversation records.

`knowledge.db` uses Schema v7. Mod manuals, Wiki content, static FTBQ task
definitions, and the item catalog share this file but are separated by
`content_kind`, `source_type`, `origin_type`, and `collection_id`. In this early
testing phase, an old Schema or a missing `item_catalog` table causes a full
rebuild in a side database. The replacement is atomic after validation; a
failed build restores the previous database. Original source files are never
deleted.

FTS5 uses an external-content structure with `content='segments'`. Complete
Markdown is still read from `documents`/`segments`, while the search index no
longer stores a second `segments_fts_content` body copy. Full or large imports
run FTS5 optimize/merge; small incremental updates run only `PRAGMA optimize`.
Queries are ordered by `rank` to avoid an additional temporary sort.

The `item_catalog` table is separate from manual FTS and stores the item ID,
current language, localized name, complete Tooltip Markdown, source mod, and
SHA-256 fingerprint. It keeps only the current game language and is replaced
after a language change. Item context is never presented as a manual source,
but can be supplied to the AI as name and Tooltip facts.

Generic Wiki sources live under `sources/<source-id>/` and require at least
`source.json` and `documents/**/*.md`. `source.json` declares the source
collection, language, version, and priority. More pack-author guides or Wikis
can be added without changing the core schema.

### Cleaning `config` before publishing a modpack

`config/modpedia/` contains both player runtime state and modpack-maintained fact
sources. Do not package local player data, derived indexes, or API settings.

Remove the entire `config/modpedia/runtime/` before publishing, including the
following runtime files and derived data. The user-level `~/.modpedia/` folder
is outside the modpack and must not be copied into it:

~~~text
config/modpedia/runtime/conversations/
config/modpedia/runtime/diagnostics/
config/modpedia/runtime/worker/
config/modpedia/runtime/assistant-window.json
config/modpedia/runtime/assistant-glass.json
config/modpedia/runtime/knowledge/knowledge.db*
config/modpedia/runtime/knowledge/generated/
config/modpedia/runtime/knowledge/cache/
config/modpedia/runtime/knowledge/manifest.json
config/modpedia/runtime/knowledge/keyword-index.json
config/modpedia/runtime/knowledge/state.json
~~~

These files are regenerated on first startup or after pressing **F9**.
`knowledge.db-wal`, `knowledge.db-shm`, and temporary database files are also
derived files and should stay out of the modpack.

Keep these fact sources with the modpack:

~~~text
config/modpedia/knowledge/custom/**/*.md
config/modpedia/knowledge/sources/<source-id>/source.json
config/modpedia/knowledge/sources/<source-id>/documents/**/*.md
config/modpedia/knowledge/sources/<source-id>/media.json
config/modpedia/knowledge/source-overrides.json
config/modpedia/knowledge/search-synonyms.json
~~~

- `source.json` describes a Wiki source ID, collection, language, version,
  priority, and Markdown root.
- `documents/**/*.md` is the Wiki body imported into `knowledge.db` at startup;
  the original files are always kept.
- `source-overrides.json` classifies JAR APP/Modonomicon books as `wiki` or
  overrides their source ID, collection ID, and priority. Ordinary
  `sources/<source-id>/` Wikis do not need it.
- `media.json` is not read by the current importer and is optional. Keep it if
  the Wiki contains images or other original media for future adapters; it is
  not currently indexed as SQLite text.

Minimal modpack-author Wiki layout:

~~~text
config/modpedia/knowledge/
├── sources/example-pack/
│   ├── source.json
│   └── documents/**/*.md
└── custom/**/*.md
~~~

### Custom documents

Put human-maintained files in `custom/`. Use a stable ID and declare a language:

~~~markdown
---
id: mypack:automation
language: en_us
title: Automation Guide
keywords: [automation, 自动化]
source_type: manual_annotation
---

# Automation Guide

The complete Markdown is retained for search and AI context.
~~~

On every startup:

~~~text
Scan custom/*.md
  → read id, language, and SHA-256 fingerprint
  → import only new or modified files
  → delete records for removed files
  → commit the SQLite transaction
  → RetrievalService.reload()
~~~

Original Markdown is always kept; SQLite is only a rebuildable derived search
database. Custom documents have priority over automatically scanned documents.
Invalid Front Matter or a failed transaction keeps the previous valid record.

## Development

### Project structure

~~~text
src/main/java/io/ctyx/modpedia/
├── ai/           # AI client, tool calls, context, and conversations
├── knowledge/    # Manual scanning, conversion, incremental builds, custom import
├── search/       # SQLite, FTS, and rule-based retrieval
├── task/         # Task snapshots, progress, dependencies, rewards, and queries
└── client/       # NeoForge client UI, source previews, and manual navigation

docs/
├── ARCHITECTURE.md
├── AI_MEMORY_STORAGE_RESEARCH.md
├── DEVELOPMENT.md
├── DEVELOPMENT_LOG.md
├── KNOWLEDGE_BASE.md
└── ROADMAP.md
~~~

Optional item and source protocols in model responses:

~~~text
[[item:namespace:path|Display name]]
[[tag:namespace:path|Display name]]
[[source:document_id|Source label]]
~~~

IDs remain in search results and conversation traces. Normal display areas use
localized names; an unregistered ID is kept as plain text. Source buttons are
embedded on the corresponding citation line in the answer. Clicking one first
tries to open the original manual page and falls back to a source preview when
the target is unavailable. Without JEI or when the target is missing, the item
remains ordinary text and the answer continues to work.

### Run locally

Put test content mods into `run/mods/` before launching the client:

~~~bash
./gradlew runClient
~~~

Graphical client regression should run in a real desktop environment. Pure Java
tests and builds remain available in headless environments.

### Validation commands

~~~bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew test
./gradlew build
git diff --check
~~~

Knowledge-base and bilingual-search benchmark:

~~~bash
./gradlew knowledgeBenchmark
~~~

Reports are written to:

~~~text
build/reports/modpedia/knowledge-benchmark.json
build/reports/modpedia/knowledge-benchmark.md
~~~

The benchmark rebuilds a temporary v7 database from the current `run/mods/`
and Downloads corpus. It records cold/hot p50/p95/p99 for Chinese, English, ID,
multi-word, and no-result queries; SQLite/FTS/dbstat sizes; query plans; and
the result of comparing contentful and external-content FTS before and after
optimize.

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the Mod development checklist,
manual regression, and release process. The maintenance, test, and delivery
record is in [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md).

### CurseForge automated publishing

The repository includes `.github/workflows/publish-curseforge.yml`. Pushing a
`v*` version tag runs tests and builds again, extracts only the matching version
section from `CHANGELOG.md`, and uploads the NeoForge 1.21.1 JAR. Configure these
values under `Settings → Secrets and variables → Actions`:

- Repository variable: `CURSEFORGE_PROJECT_ID`, set to the project ID;
- Repository secret: `CURSEFORGE_TOKEN`, set to the publishing API token.

The token is read only from a GitHub Actions Secret and is not written to the
repository, build artifacts, or logs. To retry a publication, run
`Publish Mod Release` from Actions and enter an existing version tag such as
`v1.1.0`; this does not create another GitHub Release.

## Known limitations

- Only local manual resources in installed mod JARs are scanned; manuals are not
  downloaded from the network.
- Resource paths can change between versions or third-party forks, affecting
  scan coverage.
- A manual framework without content is counted only as a dependency JAR;
  searchable coverage depends on content mods.
- Source navigation depends on a public client entry point in the target manual
  mod. A source preview remains available when the entry point is absent.
- AI answers require a compatible API configured by the player; Search-only mode
  works fully offline.
- FTB Quests does not create a runtime snapshot every tick. Current progress is
  read only when a task question is asked after entering a world. Single-player
  prefers a direct Worker read of the small SNBT file; multiplayer or unavailable
  local files use a bounded TeamData runtime index, then static definitions come
  from SQLite. The task Wiki uses its bundled copy when a network update fails.
- JEI recipe navigation and Jade target recognition depend on their client
  runtime APIs. Missing or incompatible versions only disable the corresponding
  button.
- Search-only mode currently uses rule-based retrieval; vector search remains a
  future enhancement.
- The SQLite derived database and item catalog are built during the loading
  screen on first startup. Search results may be temporarily empty during an
  F9 rebuild.

See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) for the complete list.

## Related documents

- [Mod development checklist](docs/DEVELOPMENT.md)
- [Development log](docs/DEVELOPMENT_LOG.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Knowledge-base design](docs/KNOWLEDGE_BASE.md)
- [AI persistence research](docs/AI_MEMORY_STORAGE_RESEARCH.md)
- [Roadmap](docs/ROADMAP.md)
- [Changelog](CHANGELOG.md)
- [Installation guide](INSTALL.md)
- [Known limitations](KNOWN_LIMITATIONS.md)
- [Release](https://github.com/ct-yx/modpedia/releases/tag/v1.1.0)

## Author and license

- Author: ctyx
- Mod ID: `modpedia`
- Package: `io.ctyx.modpedia`
- License: [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for project
  attribution and modified-version identity requirements.

### Redistribution and modifications

- An unmodified ModPedia JAR may be included in and distributed with a
  modpack. Modpack authors do not need to claim that they authored or modified
  ModPedia, but should retain `LICENSE` and `NOTICE`.
- When publishing modified source or a modified JAR, retain the original
  `ctyx` and ModPedia attribution, prominently describe the changes in modified
  files or release notes, and identify the result as a third-party modified,
  forked, or derivative version.
- A modified version must not use `ctyx`, `ModPedia`, `ModPedia · 模组百科`, the
  original icon, or the original author's identity in a way that suggests
  official maintenance, publication, endorsement, or support. Use the
  derivative maintainer's own identity and version label.
- These project-identity notes supplement Apache License 2.0; the code license
  scope is defined by [LICENSE](LICENSE).

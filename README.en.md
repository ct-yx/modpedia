# ModPedia

ModPedia is a local mod knowledge assistant for Minecraft modpacks. It reads
manual resources from installed mods, converts them to normalized Markdown,
indexes them in SQLite, and presents either direct search results or AI-assisted
answers while preserving links back to the original manual pages.

[![Build](https://github.com/ct-yx/modpedia/actions/workflows/build.yml/badge.svg)](https://github.com/ct-yx/modpedia/actions/workflows/build.yml)

简体中文版本: [README.md](README.md)

专题后续计划（中文）: [AI context, database v8, and external encyclopedia](docs/NEXT_DEVELOPMENT_PLAN.md)

## Development baseline

This is the Worker Core and client-adapter development branch. It does not own release
assets, download pages, or external publishing configuration. Release versions, download
links, changelogs, and site content are maintained only on the `main` branch.

| Item | Development baseline |
| --- | --- |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.244** (compatible with **21.1.x**) |
| Java | **21** |
| Mod ID | **modpedia** |
| Worker baseline | **worker-baseline-1** |
| Client UI dependency | None; drawn with the native NeoForge GUI API |
| Author | **ctyx** |

## Branch validation

Build and validate this branch from source:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jre/Contents/Home
./gradlew test
./gradlew build
git diff --check
```

Release JARs, installation instructions, checksums, changelogs, and site pages are not
maintained here; the `main` branch owns the unified release process.

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

- **API format**: Choose `Chat Completions`, `Native Messages`, `Responses`, or
  `Gemini generateContent`. The selected format controls the request body,
  authentication header, tool-calling shape, and SSE parser; it is persisted as
  `api_format`.
- **API URL**: Enter the API root for the selected format. Chat Completions,
  Native Messages, and Responses usually use `/v1`; Gemini uses `/v1beta`. If
  only a domain is entered, the client adds the matching version path.
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
points to a web page or service root instead of an API endpoint. Check the
version path for the selected format. Some Native Messages services do not
expose `/models`; enter the model name directly in that case. Model-list and
connection-test logs never write the API key.

Batch testing currently targets the Chat Completions `/models` interface and
separates models into **ordinary + tools available** and **streaming + tools
available**. The other three formats can be checked with **Test connection** and
the real conversation chain. Some services or models do not expose a model list;
enter the model name directly.

The model can call `search_knowledge`, `search_wiki`, `search_tasks`,
`query_item_recipes`, and the local deterministic `calculate` tool. When recipe, step, prerequisite, or
version evidence is incomplete, it rewrites the query and searches again. Retrieval
rounds send tool calls only, without process narration; first tool arguments and
final answers use profile-specific output budgets. The two most recent tool turns
keep complete evidence; older turns compact only repeated body text into head/tail
snippets while retaining source IDs, heading paths, and source paths. For multi-step recipe
totals, ratios, rounding, and other non-trivial arithmetic, the model sends an
expression to `calculate` instead of relying on mental arithmetic. The final
answer shows only 3–5 highly relevant source buttons actually returned in this
round and labeled by the model, followed by three suggested questions.

Default search budgets:

| Profile | Max rounds | Results per round | Context limit |
| --- | ---: | ---: | ---: |
| Fast | 1 | 4 | 8,000 characters |
| Standard | 3 | 8 | 16,000 characters |
| Deep | 5 | 12 | 28,000 characters |

AI requests keep the first tool-call output short, cap the final answer by search
profile, and suppress process narration during retrieval. GPT-5/o-series models
use `max_completion_tokens`; other Chat Completions models keep `max_tokens`, so
the request never sends both mutually exclusive fields.

## Shortcuts and UI

| Action | Behavior |
| --- | --- |
| **K** | Open or close the assistant window; disabled on Minecraft's native Options and Controls screens |
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

User-directory resolution does not trust `user.home` alone. On macOS/Linux the
fallback order is `HOME`, `USERPROFILE`, `user.home`; on Windows it is
`USERPROFILE`, `HOMEDRIVE + HOMEPATH`, `HOME`, `user.home`. If none is usable,
the parent of the instance config directory is used. This keeps launcher-overridden
`user.home` from creating a second `.modpedia` directory. An empty `ai.json` in
the old launcher directory is removed; a non-empty old configuration is migrated
only when the user-level file does not exist, and the user-level file always wins.
An old `runtime/worker/lib/` is moved to the fixed shared
`~/.modpedia/worker/lib/worker-baseline-1/`; Worker logs, IPC state, and temporary
payloads remain in the current instance at `config/modpedia/runtime/worker/`.

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
derived files and should stay out of the modpack. Worker shared libraries live
at `~/.modpedia/worker/lib/worker-baseline-1/`; they are user-level files and
must not be copied into a modpack. ModPedia versions and game instances using
the same Worker baseline reuse that directory. Increment the baseline number
when Worker dependencies change.

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
├── DEVELOPMENT.md
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
- JEI recipe queries/navigation and Jade target recognition depend on their
  client runtime APIs. Missing or incompatible versions only disable the
  corresponding integration; recipes are never written to `knowledge.db`.
- Search-only mode currently uses rule-based retrieval; vector search remains a
  future enhancement.
- The SQLite derived database and item catalog are built during the loading
  screen on first startup. Search results may be temporarily empty during an
  F9 rebuild.

See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) for the complete list.

## Related documents

- [Mod development checklist](docs/DEVELOPMENT.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Knowledge-base design](docs/KNOWLEDGE_BASE.md)
- [Roadmap](docs/ROADMAP.md)
- [Worker baseline and compatibility](docs/WORKER_BASELINE.md)
- [Worker change and version-adaptation protocol](docs/WORKER_CHANGE_PROTOCOL.md)
- [Worker verification matrix](docs/WORKER_VERIFICATION_MATRIX.md)
- [Known limitations](KNOWN_LIMITATIONS.md)

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

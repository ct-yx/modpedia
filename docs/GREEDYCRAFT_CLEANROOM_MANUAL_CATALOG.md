# GreedyCraft-Cleanroom 手册与知识来源清单

> 扫描对象：`/Users/chenhong/Documents/Minecraft/.minecraft/versions/GreedyCraft-Cleanroom/`
>
> 扫描性质：只读资源扫描，不修改整合包。扫描结果已经用于 1.12.2 第一版静态来源
> 转换器；可识别的书籍来源均已登记对应跳转适配，最终 GUI 行为仍需真实客户端回归。

## 1. 扫描结论

目标实例确认是 Minecraft 1.12.2 + Cleanroom `0.3.16-alpha`，客户端 Java 8。
`mods/` 目录递归发现 517 个 JAR/ZIP 文件（根目录有 492 个 JAR）。

本实例并不是“没有 1.12.2 手册”。实际存在以下三套手册框架：

| 框架 | 实际文件 | 结论 |
| --- | --- | --- |
| Patchouli | `mods/Patchouli-1.0-23.6.jar` | 存在，且实例目录和多个 Mod 都提供了书籍 |
| Guide-API | `mods/Guide-API-1.12-2.1.8-63.jar` | 存在，是旧版 Guide API 框架，不是正文来源 |
| FTB Guides | `mods/FTBGuides-2.0.0.52.jar` | 存在，是指南 GUI/组件框架；当前实例未发现对应正文文件 |

扫描中没有发现 GuideME。需要特别区分：资源路径中的 `guide` 通常是书籍 ID，
不是 GuideME，也不是一个统一的 1.12.2 手册 API。例如 Twilight Forest、多个
Packaged 模组和 Treasure 2 都使用了 `patchouli_books/.../guide/`。

当前发现 **13 本启用中的 Patchouli 书籍**：11 本来自 JAR，2 本来自整合包
实例目录；另有 1 本位于 `patchouli_books_disabled/`，不应导入。

## 2. 整合包作者手册（最高优先级，归类为 Wiki）

这些内容位于实例目录，不属于某一个内容 Mod。导入时应使用：

```text
content_kind = wiki
origin_type = local_file
collection_id = greedycraft
source_type = patchouli_1_12_json
```

### 2.1 GreedyCraft 指南

```text
patchouli_books/greedycraft_guide_book/
```

- `book.json`：`贪婪整合包 从入门到精通`；
- `zh_cn`：7 个分类、44 个条目；
- `en_us`：7 个分类、44 个条目；
- 内容包括快速入门、按键、任务、阶段、难度、食物、性能和整合包专属规则；
- 建议来源标识：`greedycraft-guide`。

### 2.2 The Elysia Project 记录手册

```text
patchouli_books/the_elysia_project/
```

- `book.json`：`埃拉西亚计划 记录手册`；
- `zh_cn`：3 个分类、10 个条目；
- `en_us`：3 个分类、10 个条目；
- 内容是整合包作者的章程、设定和日志，不应误归为某个 Mod 手册；
- 建议来源标识：`the-elysia-project`；
- `config/triumph/script/greedycraft/elysia/book.txt` 只是发放该书的进度触发器，
  不是正文，不要重复导入。

两本作者手册都应保留书籍 ID、entry ID、语言、原始相对路径和跳转锚点。
整合包许可证位于实例根目录 `LICENSE`，导入时保留来源和许可证元数据。

## 3. JAR 内的 Patchouli 书籍

适配器已经同时扫描 `assets/<namespace>/patchouli_books/` 和
`data/<namespace>/patchouli_books/`，不能只扫描现代版本常见的单一路径。

| JAR | 书籍资源 | 状态/规模 |
| --- | --- | --- |
| `ExtremeReactors-1.12.2-0.4.5.68.jar` | `assets/bigreactors/patchouli_books/erguide/` | 启用；约 7 分类、36 条目 |
| `PackagedAstral-1.12.2-1.0.4.22.jar` | `assets/packagedastral/patchouli_books/guide/` | 启用；约 6 条目 |
| `PackagedAuto-1.12.2-1.0.23.71.jar` | `assets/packagedauto/patchouli_books/guide/` | 启用；约 2 分类、14 条目 |
| `PackagedAvaritia-1.12.2-1.0.3.25.jar` | `assets/packagedavaritia/patchouli_books/guide/` | 启用；约 2 条目 |
| `PackagedDraconic-1.12.2-1.0.4.24.jar` | `assets/packageddraconic/patchouli_books/guide/` | 启用；约 2 条目 |
| `PackagedThaumic-1.12.2-1.0.3.20.jar` | `assets/packagedthaumic/patchouli_books/guide/` | 启用；约 4 条目；另有 Thaumcraft research 扩展 |
| `Sakura-1.0.7-1.12.2.jar` | `assets/sakura/patchouli_books/sakura_guide/` | 启用；约 2 分类、5 条目 |
| `botanianeedsit-1.12.2-3.0-release.jar` | `data/botanianeedsit/patchouli_books/botanianeedsit/` | 启用；约 2 条目；验证了 `data/` 路径 |
| `touhoulittlemaid-1.12.2-1.2.5-release.jar` | `assets/touhou_little_maid/patchouli_books/memorizable_gensokyo/` | 启用；约 3 分类、45–46 条目；内容量大 |
| `twilightforest-1.12.2-3.11.1021-universal.jar` | `assets/twilightforest/patchouli_books/guide/` | 启用；约 8 分类、82 条目 |
| `modpedia-0.2.0.jar` | `assets/modpedia/patchouli_books/modpedia/` | 当前实例已装入本 Mod 自带书籍 |

以下来源明确被禁用，默认不导入：

```text
Treasure2-mc1.12.2-f14.23.5.2859-v2.3.2.jar:
assets/treasure2/patchouli_books_disabled/guide/
```

Patchouli 条目页不应只转换 `text` 页，还要保留 `crafting`、`smelting`、
`link`、`image`、`entity`、`spotlight` 等页型的原始 JSON 和可读 Markdown。

## 4. 旧版自有静态手册

这些来源不是 Patchouli，第一版按格式分别归类并转换；不能因为目录叫 `book` 或 `guide`
就共用一个解析器。

### 4.1 Mantle/Tinkers 书籍族（优先级最高）

共同使用旧版 Mantle 书籍结构，但每个 JAR 仍必须保留独立 `source_id`：

| JAR | 资源根 | 规模 |
| --- | --- | --- |
| `TConstruct-1.12.2-2.13.0.183.jar` | `assets/tconstruct/book/` | 约 491 个文件，多语言 |
| `TinkersComplement-1.12.2-0.4.3.jar` | `assets/tcomplement/book/` | 约 19 个文件 |
| `conarm-1.12.2-1.2.5.10.jar` | `assets/conarm/book/` | 约 181 个文件，多语言 |
| `taiga-1.12.2-1.3.4.jar` | `assets/taiga/book/` | 约 86 个文件，多语言 |
| `tconevo-1.12.2-1.1.1.jar` | `assets/tconstruct/book/`、`assets/conarm/book/` | 扩展其他书籍；不能按 tconstruct 自带正文合并 |
| `toolprogression-1.12.2-1.6.12.jar` | `assets/toolprogression/book/` | 约 3 个文件 |

转换时要以 JAR 作为来源边界。特别是 `tconevo` 会向已有 namespace 的书籍目录
追加内容，不能只按目录 namespace 去重。

### 4.2 Forestry Manual

```text
forestry_1.12.2-5.8.2.426.jar:
assets/forestry/manual/
```

约 309 个文件，包含 `categories.json`、entries、页面和多语言资源。Forestry
同时在类中提供 `forestry.api.book.*`，跳转适配使用其运行时分类/条目 API；静态导入
仍优先读取资源文件。

### 4.3 EnderIO 书籍

```text
EnderIO-1.12.2-5.3.72.jar:
assets/enderio/book/
assets/enderio/eiobook/
```

`eiobook` 约 12 个正文/索引文件，`book` 还有共享入口资源。两套结构要保留原始
书籍类型和相对路径，不能把它们伪装成 Patchouli。

### 4.4 Guide-API 内容

`Guide-API-1.12-2.1.8-63.jar` 是框架本体。当前包中需要单独识别其内容提供者：

| Mod | 证据 | 适配方向 |
| --- | --- | --- |
| Chisel | `assets/chisel_guide/guide.json`、`assets/chisel_guide/guide/index.json` | Guide-API/自定义 JSON |
| Blood Magic | `assets/bloodmagic/books/architect.xml`、`compat/guideapi/` | XML + Guide-API 兼容层 |
| Blood Arsenal | `compat/guideapi/`、`assets/bloodarsenalguide/lang/` | Guide-API/语言资源 |
| Cyclic | `compat/guideapi/`、`guide/` | 运行时注册 + 资源 |

Guide-API 的 `PageText`、`PageItemStack`、`PageFurnaceRecipe`、`PageIRecipe` 等
旧页型不能直接套用 Patchouli 页型；导入器要保存原页类型，并在 Markdown 中生成
可读降级文本。

## 5. 研究、Lexicon 和运行时注册内容

### 5.1 Thaumcraft Research 族

以下来源使用 `assets/*/research/*.json` 或 Thaumcraft 研究 API：

```text
Thaumcraft-1.12.2-6.1.BETA26.jar
  assets/thaumcraft/research/
ThaumicAugmentation-1.12.2-2.1.14.jar
  assets/thaumicaugmentation/research/
thaumic-energistics-extended-life-1.12.2-2.3.5.jar
  assets/thaumicenergistics/research/
thaumictinkerer-1.12.2-5.0-620a0c5.jar
  assets/thaumictinkerer/research/
thaumicwonders-1.8.4.jar
  assets/thaumicwonders/research/
Rustic Thaumaturgy-4.4a.jar
  assets/rusticthaumaturgy/research/
PackagedThaumic-1.12.2-1.0.3.20.jar
  assets/packagedthaumic/research/
```

第一版共享 `thaumcraft_research_1_12` 转换器保存研究 ID、前置研究、阶段、
解锁条件、扫描对象和页面文本；各扩展 Mod 只负责提供来源 namespace。玩家研究进度
是运行时状态，不应写入静态知识文档。

### 5.2 Lexicon/Booklet/Journal

当前包中存在下列运行时或代码注册型手册，JAR 中没有可直接完整导入的统一正文：

| Mod | 手册系统 | 关键证据 |
| --- | --- | --- |
| Botania | Lexica Botania | `vazkii/botania/common/lexicon/`、`api/lexicon/` |
| Actually Additions | Booklet | `de/ellpeck/actuallyadditions/mod/booklet/` |
| Astral Sorcery | Astral Journal/Research | `hellfirepvp/astralsorcery/client/gui/journal/`、`common/data/research/` |
| ExtraBotany | Lexicon 扩展 | `com/meteor/extrabotany/common/` |
| Rustic | 自有 Book API | `rustic/common/book/` |
| ProjectE | Manual 页面系统 | `moze_intel/projecte/manual/` |
| OpenBlocks | Info Book | `assets/openblocks/recipes/info_book_0.json` 与客户端页面类 |
| AbyssalCraft | Necronomicon | Necronomicon GUI/资源和运行时页面 |
| RebornCore | Team Reborn Manual | `reborncore/modcl/manual/` |

这些内容需要目标客户端运行时适配层从已注册对象导出，或针对其资源/语言
格式做专用解析。不能把 class 文件或 GUI 纹理当正文。

## 6. 自有文本 Wiki/手册资源

这些来源适合优先实现 `custom_text_1_12` / `wiki_asset_text_1_12`：

| JAR | 资源 |
| --- | --- |
| `logisticspipes-0.10.4.49.jar` | `assets/logisticspipes/book/en_us/*.md` |
| `rftools-1.12-7.73.jar` | `assets/rftools/text/manual*.txt` |
| `rftoolsctrl-1.12-2.0.2.jar` | `assets/rftoolscontrol/text/manual_control*.txt` |
| `xnet-1.12-1.8.2.jar` | `assets/xnet/text/manual_xnet.txt` |
| `AEAdditions-1.12.2-1.3.8.jar` | `assets/aeadditions/wiki/`，约 40 个文件 |
| `ompd-1.12.2-3.2.0-76.jar` | `assets/ompd/wiki/` |
| `openmodularturrets-1.12.2-3.2.0-379.jar` | `assets/openmodularturrets/wiki/` |

语言目录大小写存在差异（例如 `en_US` 与 `en_us`），扫描器应在比较语言代码时
规范化大小写，但 `sourcePath` 必须保留原始路径。

## 7. 框架、聚合容器与非手册来源

以下项目不能重复导入为正文：

| 项目 | 处理 |
| --- | --- |
| Patchouli | 记录为框架；导入实际 `patchouli_books` 书籍 |
| Guide-API | 记录为框架；导入各内容 Mod |
| FTB Guides | 记录为框架；当前实例未发现正文，等待外部 guide 或运行时事件 |
| AkashicTome | 记录为聚合入口；不重复导入其聚合的其他书籍 |
| JEI/HEI | 配方查询联动，不是手册正文 |
| FTB Quests | 任务定义与运行时进度，不是手册正文 |
| Jade/The One Probe | 目标物品识别，不是手册正文 |
| `patchouli_books_disabled` | 默认排除，除非用户显式启用 |

## 8. 1.12.2 物品身份设计约束

1.12.2 不是扁平化物品模型，不能只保存 `namespace:item`。

```text
逻辑 ID       = namespace:item_name
物品变体      = metadata/damage（通常为 short）
完整身份      = namespace:item_name + metadata + 规范化 NBT（必要时）
OreDictionary = ore:<name>
```

建议 1.12.2 适配层统一输出：

```json
{
  "item_id": "minecraft:wool",
  "meta": 14,
  "nbt": null,
  "display_name": "红色羊毛"
}
```

回答正文中的令牌可以使用：

```text
[[item:minecraft:wool|红色羊毛|meta=14]]
```

解析 `ItemStack` 时必须从旧版 `Item` 注册名、metadata 和 NBT 生成身份；不能使用
1.13+ 的 flattened ID 假设。JEI/HEI 跳转、Jade/准星插入、手册中的 `item`/`data`/
`Damage` 字段都要复用同一套解析器。

## 9. 适配器矩阵与实施顺序

第一版已经登记以下独立来源类型；静态资源统一落为 Markdown，运行时注册型内容
保留为后续真实客户端验收项：

```text
patchouli_1_12_json
guide_api_1_12
mantle_book_1_12
forestry_manual_1_12
enderio_book_1_12
logistics_pipes_book_1_12
thaumcraft_research_1_12
lexicon_runtime_1_12
custom_text_1_12
wiki_asset_text_1_12
ftb_guides_1_12
```

建议顺序：

1. 外部 GreedyCraft/The Elysia Patchouli Wiki；
2. Patchouli 1.12.2 静态书籍；
3. Mantle、Forestry、EnderIO、Guide-API 静态书籍；
4. Thaumcraft Research 及扩展；
5. RFTools、XNet 等静态文本；Logistics Pipes 作为可打开的书籍单独使用
   `logistics_pipes_book_1_12` 跳转适配；
6. Botania、Actually Additions、Astral Sorcery 等运行时手册；
7. FTB Guides 外部内容或事件型来源；
8. 统一来源跳转和旧版物品 metadata/旧配方对象适配。

实现阶段只在 `migration/1.12.2-common` 分支修改目标工程；扫描和转换不修改 Worker、
AI、数据库或真实整合包原始文件，Worker 仅在运行时读取派生来源。

## 10. 验收条件

- `assets/` 与 `data/` 两种 Patchouli 路径都能发现；
- `zh_cn → en_us → neutral` 回退在 1.12.2 资源目录大小写差异下稳定；
- 作者 Wiki 与 Mod 手册的 `content_kind` 分离；
- disabled 书籍不进入默认知识库；
- 同一内容被多个 JAR 扩展时按 JAR/source_id 去重，而不是按 namespace 粗暴覆盖；
- `namespace:item + meta + NBT` 在目录、搜索、Jade、JEI/HEI 和来源跳转中保持一致；
- 缺少 Patchouli、Guide-API、FTB Guides 或其他可选 Mod 时仍能启动；
- 运行时手册适配仍必须有实际 Cleanroom 客户端证据，不能只凭 class 路径标记为已完成。

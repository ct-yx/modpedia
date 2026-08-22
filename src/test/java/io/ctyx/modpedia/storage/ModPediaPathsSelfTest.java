package io.ctyx.modpedia.storage;

import io.ctyx.modpedia.knowledge.KnowledgeCompiler;
import io.ctyx.modpedia.knowledge.KnowledgeDocument;
import io.ctyx.modpedia.knowledge.KnowledgeScanResult;
import io.ctyx.modpedia.knowledge.ScannedResource;
import io.ctyx.modpedia.search.KnowledgeDatabase;
import io.ctyx.modpedia.search.RetrievalService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 验证运行时派生文件与整合包事实源的实际磁盘布局及早期目录迁移。 */
public final class ModPediaPathsSelfTest {
    private ModPediaPathsSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("modpedia-storage-layout-");
        try {
            Path config = temporary.resolve("config");
            Path userHome = temporary.resolve("user-home");
            Path launcherHome = temporary.resolve("launcher-home");
            check(ModPediaPaths.resolveUserHome(
                            config,
                            Map.of("HOME", userHome.toString()),
                            launcherHome.toString(),
                            "Mac OS X"
                    ).equals(userHome),
                    "启动器覆盖 user.home 时应优先使用 HOME");
            check(ModPediaPaths.resolveUserHome(
                            config,
                            Map.of("USERPROFILE", userHome.toString()),
                            launcherHome.toString(),
                            "Windows 11"
                    ).equals(userHome),
                    "Windows 应优先使用 USERPROFILE");
            check(ModPediaPaths.resolveUserHome(
                            config,
                            Map.of("HOMEDRIVE", userHome.toString(), "HOMEPATH", "/windows-user"),
                            launcherHome.toString(),
                            "Windows 11"
                    ).equals(userHome.resolve("windows-user")),
                    "Windows 应支持 HOMEDRIVE + HOMEPATH");
            check(ModPediaPaths.resolveUserHome(
                            config,
                            Map.of("HOME", userHome.toString()),
                            launcherHome.toString(),
                            "Linux"
                    ).equals(userHome),
                    "Linux 应优先使用 HOME");
            check(ModPediaPaths.resolveUserHome(
                            config,
                            Map.of(),
                            launcherHome.toString(),
                            "Linux"
                    ).equals(launcherHome),
                    "无环境变量时应回退到 user.home");
            check(ModPediaPaths.resolveUserHome(
                            config,
                            Map.of(),
                            "",
                            "Linux"
                    ).equals(config.toAbsolutePath().normalize().getParent()),
                    "无用户目录来源时应回退到配置目录父级");
            Path legacyRoot = config.resolve("modpedia");
            write(legacyRoot.resolve("ai.json"), "{\"mode\":\"ai\"}");
            write(legacyRoot.resolve("conversations/conversation.json"), "history");
            write(legacyRoot.resolve("diagnostics/report.json"), "report");
            write(legacyRoot.resolve("worker/worker.log"), "worker");
            write(legacyRoot.resolve("worker/lib/gson.jar"), "worker-library");
            write(legacyRoot.resolve("assistant-window.json"), "window");
            write(legacyRoot.resolve("assistant-glass.json"), "glass");
            write(legacyRoot.resolve("search-synonyms.json"), "{\"groups\":[]}");
            write(legacyRoot.resolve("knowledge/knowledge.db"), "database");
            write(legacyRoot.resolve("knowledge/knowledge.db-wal"), "wal");
            write(legacyRoot.resolve("knowledge/generated/generated.md"), "generated");
            write(legacyRoot.resolve("knowledge/cache/build-report.json"), "cache");
            write(legacyRoot.resolve("knowledge/manifest.json"), "manifest");
            write(legacyRoot.resolve("knowledge/keyword-index.json"), "index");
            write(legacyRoot.resolve("knowledge/state.json"), "state");

            Path custom = legacyRoot.resolve("knowledge/custom/pack-guide.md");
            Path source = legacyRoot.resolve("knowledge/sources/pack/source.json");
            write(custom, "---\nid: pack:guide\n---\n# Pack guide\n");
            write(source, "{}");
            write(legacyRoot.resolve("knowledge/sources/pack/documents/guide.md"), "# Guide\n");
            write(legacyRoot.resolve("knowledge/sources/pack/media.json"), "{}\n");
            write(legacyRoot.resolve("knowledge/source-overrides.json"), "{}\n");

            Path launcherRoot = launcherHome.resolve(".modpedia");
            write(launcherRoot.resolve("ai.json"), "{}");
            write(launcherRoot.resolve("worker/lib/launcher.jar"), "launcher-worker-library");

            ModPediaPaths paths = ModPediaPaths.forConfig(config, userHome);
            String originalUserHome = System.getProperty("user.home");
            System.setProperty("user.home", launcherHome.toString());
            ModPediaPaths.MigrationResult migration;
            try {
                migration = paths.migrateLegacy();
            } finally {
                if (originalUserHome == null) {
                    System.clearProperty("user.home");
                } else {
                    System.setProperty("user.home", originalUserHome);
                }
            }
            check(migration.changed(), "旧布局应产生迁移记录");
            check(Files.isRegularFile(paths.aiSettings()), "ai.json 应迁移到用户共享目录");
            check(paths.aiSettings().equals(userHome.resolve(".modpedia/ai.json")),
                    "共享 ai.json 应位于用户目录下的 .modpedia");
            checkSharedSettingsPermissions(paths);
            check(Files.isRegularFile(paths.conversationsRoot().resolve("conversation.json")),
                    "会话应迁移到 runtime");
            check(Files.isRegularFile(paths.workerRoot().resolve("worker.log")),
                    "Worker 文件应迁移到 runtime");
            check(Files.isRegularFile(paths.workerLibraryRoot().resolve("gson.jar")),
                    "旧 Worker lib 应迁移到用户级固定基线目录");
            check(Files.isRegularFile(paths.workerLibraryRoot().resolve("launcher.jar")),
                    "旧启动器目录 Worker lib 应迁移到用户级固定基线目录");
            check(!Files.exists(paths.workerRoot().resolve("lib")),
                    "迁移成功后实例 runtime 不应继续保留 Worker lib");
            check(!Files.exists(launcherRoot.resolve("ai.json")),
                    "旧启动器目录中的空 ai.json 应自动清理");
            check(Files.isRegularFile(paths.runtimeKnowledgeRoot().resolve("knowledge.db")),
                    "SQLite 应迁移到 runtime/knowledge");
            check(Files.isRegularFile(paths.runtimeKnowledgeRoot().resolve("generated/generated.md")),
                    "生成 Markdown 应迁移到 runtime/knowledge");
            check(Files.isRegularFile(paths.searchSynonyms()),
                    "同义词配置应迁移到保留的 knowledge 目录");
            check(Files.isRegularFile(custom), "custom 原始 Markdown 必须原地保留");
            check(Files.isRegularFile(source), "source.json 必须原地保留");
            check(Files.isRegularFile(legacyRoot.resolve("knowledge/sources/pack/media.json")),
                    "media.json 必须原地保留");
            check(Files.isRegularFile(legacyRoot.resolve("knowledge/source-overrides.json")),
                    "source-overrides.json 必须原地保留");
            check(!Files.exists(legacyRoot.resolve("ai.json")), "旧 ai.json 不应继续散落在根目录");
            check(!Files.exists(paths.runtimeRoot().resolve("ai.json")),
                    "旧 runtime ai.json 不应继续留在实例目录");
            check(!Files.exists(legacyRoot.resolve("knowledge/knowledge.db")),
                    "旧 knowledge.db 不应继续散落在事实源目录");

            ModPediaPaths.MigrationResult second = paths.migrateLegacy();
            check(!second.changed(), "重复启动不应重复迁移文件");
            ModPediaPaths anotherInstance = ModPediaPaths.forConfig(
                    temporary.resolve("other-instance/config"), userHome
            );
            check(paths.aiSettings().equals(anotherInstance.aiSettings()),
                    "不同游戏实例应共享同一个用户级 ai.json");
            check(paths.workerLibraryRoot().equals(anotherInstance.workerLibraryRoot()),
                    "不同游戏实例应共享同一个 Worker 基线 lib");

            Path staleLauncherHome = temporary.resolve("stale-launcher-home");
            Path staleLauncherSettings = staleLauncherHome.resolve(".modpedia/ai.json");
            write(staleLauncherSettings, "{}");
            String sharedSettings = Files.readString(paths.aiSettings(), StandardCharsets.UTF_8);
            String previousUserHome = System.getProperty("user.home");
            System.setProperty("user.home", staleLauncherHome.toString());
            try {
                ModPediaPaths.forConfig(temporary.resolve("stale-instance/config"), userHome)
                        .migrateLegacy();
            } finally {
                if (previousUserHome == null) {
                    System.clearProperty("user.home");
                } else {
                    System.setProperty("user.home", previousUserHome);
                }
            }
            check(!Files.exists(staleLauncherSettings), "用户级配置存在时应清理旧启动器空 ai.json");
            check(sharedSettings.equals(Files.readString(paths.aiSettings(), StandardCharsets.UTF_8)),
                    "用户级 ai.json 应优先于旧启动器配置");

            Path migratedUserHome = temporary.resolve("migrated-user-home");
            Path migratedLauncherHome = temporary.resolve("migrated-launcher-home");
            Path migratedConfig = temporary.resolve("migrated-instance/config");
            Path migratedLauncherRoot = migratedLauncherHome.resolve(".modpedia");
            String secret = "fixture-secret-api-key";
            String migratedSettings = "{\"api_key_encrypted\":\"" + secret
                    + "\",\"endpoint\":\"https://example.invalid\"}";
            write(migratedLauncherRoot.resolve("ai.json"), migratedSettings);
            write(migratedLauncherRoot.resolve("worker/lib/launcher.jar"), "launcher-worker-library");
            write(migratedConfig.resolve("modpedia/runtime/worker/worker.log"), "runtime-worker");
            ModPediaPaths migratedPaths = ModPediaPaths.forConfig(migratedConfig, migratedUserHome);
            String beforeMigrationUserHome = System.getProperty("user.home");
            System.setProperty("user.home", migratedLauncherHome.toString());
            ModPediaPaths.MigrationResult launcherMigration;
            try {
                launcherMigration = migratedPaths.migrateLegacy();
            } finally {
                if (beforeMigrationUserHome == null) {
                    System.clearProperty("user.home");
                } else {
                    System.setProperty("user.home", beforeMigrationUserHome);
                }
            }
            check(migratedSettings.equals(Files.readString(
                            migratedPaths.aiSettings(),
                            StandardCharsets.UTF_8
                    )),
                    "用户级 ai.json 不存在时应迁移旧启动器配置并保留内容");
            check(!Files.exists(migratedLauncherRoot.resolve("ai.json")),
                    "旧启动器配置迁移后不应保留第二份文件");
            check(Files.isRegularFile(migratedPaths.workerLibraryRoot().resolve("launcher.jar")),
                    "旧启动器 Worker lib 应迁移到固定基线目录");
            check(Files.isRegularFile(migratedPaths.workerRoot().resolve("worker.log")),
                    "Worker 日志应继续留在当前实例 runtime/worker");
            for (String moved : launcherMigration.moved()) {
                check(!moved.contains(secret), "迁移日志不得包含 API Key");
            }

            Path secondLegacyConfig = temporary.resolve("second-instance/config");
            Path secondLegacyLibrary = secondLegacyConfig.resolve(
                    "modpedia/runtime/worker/lib/second.jar"
            );
            write(secondLegacyLibrary, "second-worker-library");
            ModPediaPaths secondPaths = ModPediaPaths.forConfig(secondLegacyConfig, userHome);
            secondPaths.migrateLegacy();
            check(Files.isRegularFile(paths.workerLibraryRoot().resolve("second.jar")),
                    "已有用户级 Worker 基线时也应合并旧实例 lib");
            check(!Files.exists(secondLegacyLibrary),
                    "合并成功后第二个实例不应继续保留 Worker lib");

            Path generated = paths.runtimeKnowledgeRoot().resolve("generated/fixture.md");
            ScannedResource scanned = new ScannedResource(
                    "fixture", "Fixture", "1", "guide.md", "builtin_markdown",
                    "# Fixture\n\n运行时生成内容。", "fixture-fingerprint", Map.of()
            );
            KnowledgeCompiler.CompileResult result = new KnowledgeCompiler().compile(
                    paths.contentRoot(),
                    paths.runtimeKnowledgeRoot(),
                    new KnowledgeScanResult(List.of(scanned), List.of()),
                    true
            );
            check(result.successful(), "分离目录构建应成功");
            check(Files.isRegularFile(KnowledgeDatabase.path(paths.runtimeKnowledgeRoot())),
                    "分离目录构建的 SQLite 应写入 runtime/knowledge");
            check(Files.isRegularFile(paths.runtimeKnowledgeRoot().resolve("generated/fixture/guide.md")),
                    "JAR 生成 Markdown 应写入 runtime/knowledge/generated");
            check(!Files.exists(generated), "测试用的错误路径不应生成文件");
            check(new RetrievalService(paths.runtimeKnowledgeRoot()).search("运行时生成内容").hasResults(),
                    "检索应同时使用分离后的 runtime 数据库和生成正文");
            System.out.println("ModPedia storage layout self-test passed");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkSharedSettingsPermissions(ModPediaPaths paths) {
        try {
            Set<PosixFilePermission> directory = Files.getPosixFilePermissions(paths.userRoot());
            Set<PosixFilePermission> file = Files.getPosixFilePermissions(paths.aiSettings());
            check(directory.equals(EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE
                    )), "共享配置目录应限制为 0700");
            check(file.equals(EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE
                    )), "共享 ai.json 应限制为 0600");
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统使用平台默认权限。
        } catch (Exception exception) {
            throw new AssertionError("共享配置权限检查失败", exception);
        }
    }
}

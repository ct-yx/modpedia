package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;

import java.util.concurrent.CompletableFuture;

/**
 * 任务 Wiki 的客户端桥接入口。
 *
 * <p>网络请求、缓存文件和知识库重建全部由 Worker 执行；游戏 JVM 这里只提交
 * 一个异步 IPC 请求，不创建 HTTP 客户端，也不写入 Wiki 文件。</p>
 */
public final class TaskWikiSyncService {
    private TaskWikiSyncService() {
    }

    /** 在首轮 Worker 构建结束后提交 Wiki 同步，避免启动竞态丢请求。 */
    public static void startAfter(CompletableFuture<Boolean> bootstrap) {
        if (bootstrap == null) {
            if (ModPediaBridge.get().isReady()) {
                startAsync();
            } else {
                retryAfterWorkerReady();
            }
            return;
        }
        bootstrap.whenComplete((completed, failure) -> {
            if (ModPediaBridge.get().isReady()) {
                // Wiki 同步本身也会被 Worker 的知识操作 gate 串行化；即使首轮
                // 扫描失败或仍有旧数据库，也要让本地 Wiki 准备/更新请求进入
                // 队列，不能把“bootstrap 返回 false”当成静默放弃 Wiki。
                startAsync();
            } else {
                // 首次启动可能只是 Worker 握手失败。不要把 Wiki 请求静默
                // 丢掉；Bridge 重连成功后重新跑一次完整的启动 bootstrap，
                // 让知识库、物品目录和 Wiki 仍保持固定顺序。
                retryAfterWorkerReady();
            }
        });
    }

    private static void retryAfterWorkerReady() {
        ModPediaBridge bridge = ModPediaBridge.get();
        if (bridge.isReady()) {
            startAsync();
            return;
        }
        bridge.whenReady(() ->
                StartupKnowledgeBootstrap.startAsync().whenComplete((completed, failure) -> {
                    if (bridge.isReady()) {
                        startAsync();
                    } else if (failure != null) {
                        ModPedia.LOGGER.warn("Worker 重连后知识库 bootstrap 失败，Wiki 同步将等待下一次重连", failure);
                    }
                })
        );
    }

    public static void startAsync() {
        ModPediaBridge.get().syncTaskWikiAsync(
                StartupKnowledgeBootstrap.resolveModsDirectory()
        );
    }
}

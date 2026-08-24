package io.ctyx.modpedia.client;

/**
 * 物品目录捕获生命周期。
 *
 * <p>捕获只允许发生在尚未进入世界的客户端阶段。进入世界时暂停而不是丢弃
 * 已经捕获的结果，返回主菜单后继续；这样不会在游戏内扫描，也不会因为玩家
 * 比扫描更早进入世界而得到一个空目录。</p>
 */
public final class LegacyCatalogCaptureGate {
    public enum Decision {
        WAIT,
        CAPTURE,
        PAUSE,
        CLOSED
    }

    private boolean requested;
    private boolean completed;
    private boolean closed;

    public void request() {
        requested = true;
    }

    public Decision observe(boolean worldActive, boolean menuVisible) {
        if (completed || closed) {
            return Decision.CLOSED;
        }
        if (worldActive) {
            return Decision.PAUSE;
        }
        // 1.12.2 的 FML 加载屏幕不一定被暴露为 currentScreen。只要世界还
        // 没有建立，就允许从加载阶段开始捕获，避免把整个目录推迟到主菜单后。
        if (!requested) {
            return Decision.WAIT;
        }
        return Decision.CAPTURE;
    }

    public void complete() {
        completed = true;
        closed = true;
    }

    public void reset() {
        requested = false;
        completed = false;
        closed = false;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isClosed() {
        return closed;
    }
}

package io.ctyx.modpedia.clientcore;

import java.nio.file.Path;

/** 共享用户级 Worker 安装路径；实例可变数据不放在这里。 */
public final class WorkerPaths {
    private final Path root;

    public WorkerPaths(Path root) {
        if (root == null) throw new IllegalArgumentException("Worker 根目录不能为空");
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() { return root; }
    public Path current() { return root.resolve("current"); }
    public Path workerJar() { return current().resolve("modpedia-worker.jar"); }
    public Path manifest() { return current().resolve("manifest.json"); }
    public Path lib() { return current().resolve("lib"); }
    public Path staging() { return root.resolve("staging"); }
    public Path installLock() { return root.resolve("install.lock"); }
    public Path installState() { return root.resolve("install-state.json"); }
}

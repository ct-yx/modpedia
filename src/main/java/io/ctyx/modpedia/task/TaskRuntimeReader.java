package io.ctyx.modpedia.task;

/**
 * 任务运行时读取边界。
 *
 * <p>AI 工具只依赖这个小接口，因此没有 FTB Quests 时仍可正常加载；具体模组
 * 适配器通过反射实现该接口。</p>
 */
@FunctionalInterface
public interface TaskRuntimeReader {
    TaskRuntimeReadResult readForQuery(TaskQuery query, String requestKey);
}

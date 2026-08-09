package io.ctyx.modpedia.client;

@FunctionalInterface
public interface SourceNavigator {
    /** 打开一个来源；返回值表示已找到并调用对应手册模组的打开入口。 */
    boolean open(SourceReference source);
}

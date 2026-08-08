package io.ctyx.modpedia.client;

@FunctionalInterface
public interface SourceNavigator {
    /** 打开一个来源；阶段四默认只在浮窗内预览，后续由手册模组适配器接管。 */
    void open(SourceReference source);
}

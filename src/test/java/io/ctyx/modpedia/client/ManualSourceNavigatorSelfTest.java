package io.ctyx.modpedia.client;

import java.util.List;

/** GuideME 来源跳转参数的纯 Java 回归测试。 */
public final class ManualSourceNavigatorSelfTest {
    private ManualSourceNavigatorSelfTest() {
    }

    public static void main(String[] args) {
        List<String> candidates = ManualSourceNavigator.guidePageCandidatePaths(
                "ae2",
                "ae2guide/items-blocks-machines/controller.md",
                "ae2guide/items-blocks-machines/controller",
                "ae2guide"
        );
        check(
                candidates.contains("ae2:items-blocks-machines/controller.md"),
                "GuideME 页面候选必须保留 .md 后缀"
        );
        check(
                candidates.stream().noneMatch(candidate -> candidate.equals("ae2:items-blocks-machines/controller")),
                "GuideME 页面候选不能使用去掉 .md 的 ID"
        );
        List<String> localizedCandidates = ManualSourceNavigator.guidePageCandidatePaths(
                "appliedpneumatics",
                "ae2guide/_zh_cn/me_pressure_interface_block.md",
                "ae2guide/_zh_cn/me_pressure_interface_block",
                "ae2guide"
        );
        check(
                localizedCandidates.contains("appliedpneumatics:me_pressure_interface_block.md"),
                "GuideME 本地化页面跳转必须回退到不带语言目录的页面 ID"
        );
        List<String> bookNamespaceCandidates = ManualSourceNavigator.guidePageCandidatePaths(
                "ae2",
                "ae2guide/_zh_cn/me_pressure_interface_block.md",
                "ae2guide/_zh_cn/me_pressure_interface_block",
                "ae2guide"
        );
        check(
                bookNamespaceCandidates.contains("ae2:me_pressure_interface_block.md"),
                "GuideME 扩展页面还应尝试书籍 namespace"
        );
        check(
                ManualSourceNavigator.guideFolderMatches(
                        "ae2guide/_zh_cn/me_pressure_interface_block.md",
                        "ae2guide"
                ),
                "GuideME 扩展页面应按书籍文件夹匹配注册书籍"
        );
        SourceReference exactInterface = new SourceReference(
                "ae2:ae2guide/items-blocks-machines/interface",
                "ME Interface",
                "ae2",
                "assets/ae2/ae2guide/items-blocks-machines/interface.md"
        );
        List<String> exactInterfaceCandidates = ManualSourceNavigator.guidePageCandidatePaths(
                exactInterface,
                "ae2guide"
        );
        check(
                exactInterfaceCandidates.contains("ae2:items-blocks-machines/interface.md"),
                "真实 AE2 GuideME 来源必须从完整 assets 路径还原到页面 ID"
        );
        check(
                exactInterfaceCandidates.contains("ae2:ae2guide/items-blocks-machines/interface.md"),
                "真实 GuideME 来源还应保留带书籍根目录的兼容候选"
        );
        SourceReference appSource = new SourceReference(
                "content:app/book/basics/pressure",
                "压力说明",
                "content",
                "data/content/modonomicon/books/book/entries/basics/pressure.json#book=book&category=basics&entry=pressure"
        );
        AppSourceNavigator.Target target = AppSourceNavigator.target(appSource).orElseThrow();
        check("content".equals(target.namespace()), "APP 来源应使用内容模组 namespace");
        check("book".equals(target.book()), "APP 来源应解析书籍 ID");
        check(AppSourceNavigator.entryCandidates(target).contains("basics/pressure"),
                "APP 来源应生成分类/条目页级候选");
        System.out.println("ModPedia GuideME navigation self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

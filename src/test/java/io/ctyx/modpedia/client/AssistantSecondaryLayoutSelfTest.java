package io.ctyx.modpedia.client;

/** 二级页面几何约束回归测试，不依赖 Minecraft 渲染线程。 */
public final class AssistantSecondaryLayoutSelfTest {
    private AssistantSecondaryLayoutSelfTest() {
    }

    public static void main(String[] args) {
        checkWindow(new WindowBounds(53, 24, 439, 280));
        checkWindow(new WindowBounds(0, 0, WindowBounds.MIN_WIDTH, WindowBounds.MIN_HEIGHT));

        WindowBounds normalized = new WindowBounds(0, 0, 2_000, 2_000).clampTo(1_544, 864);
        check(normalized.width() <= 720 && normalized.height() <= 720,
                "旧配置的超大窗口必须先经过固定上限约束");
        checkWindow(normalized);

        check(AssistantSecondaryLayout.scaleFor(new AssistantSecondaryLayout.Rect(0, 0, 80, 80)) == 0.62,
                "二级页面缩放必须有固定最小比例");
        check(AssistantSecondaryLayout.scaleFor(new AssistantSecondaryLayout.Rect(0, 0, 2_000, 2_000)) == 1.18,
                "二级页面缩放必须有固定最大比例");

        check(AiSettingsPanel.positionAt(100, 38, 0) == 138,
                "设置页已缩放的行偏移不能再次缩放");
        check(AiSettingsPanel.positionAt(100, 38, 12) == 126,
                "设置页滚动应只平移已计算好的页面坐标");

        WindowBounds tiny = new WindowBounds(10, 10, WindowBounds.MIN_WIDTH, WindowBounds.MIN_HEIGHT);
        int actionWidth = FloatingAssistantWindow.headerActionWidth(tiny.width());
        int closeWidth = FloatingAssistantWindow.headerCloseWidth(tiny.width());
        int actionStart = FloatingAssistantWindow.headerActionsStart(tiny);
        check(actionStart >= tiny.x(), "窄窗口标题栏操作区不能跑出窗口左边界");
        check(actionStart + actionWidth + FloatingAssistantWindow.headerActionGap() + actionWidth
                        <= tiny.x() + tiny.width() - closeWidth,
                "标题栏操作按钮和关闭按钮不能重叠");

        System.out.println("ModPedia assistant secondary layout self-test passed");
    }

    private static void checkWindow(WindowBounds window) {
        AssistantSecondaryLayout.Rect page = AssistantSecondaryLayout.page(window);
        AssistantSecondaryLayout.History history = AssistantSecondaryLayout.history(page);
        AssistantSecondaryLayout.History readableHistory = AssistantSecondaryLayout.history(page, 18);
        checkInside(page, window, "二级页面");
        checkInside(history.newConversation(), page, "新建会话按钮");
        checkInside(history.list(), page, "历史列表");
        checkInside(history.rename(), page, "重命名按钮");
        checkInside(history.delete(), page, "删除按钮");
        checkInside(readableHistory.newConversation(), page, "大字体新建按钮");
        checkInside(readableHistory.list(), page, "大字体历史列表");
        checkInside(readableHistory.rename(), page, "大字体重命名按钮");
        checkInside(readableHistory.delete(), page, "大字体删除按钮");
        check(history.newConversation().bottom() <= history.list().top(), "新建按钮不能与列表重叠");
        check(history.list().bottom() <= history.rename().top(), "列表不能与页脚重叠");
        check(history.rename().right() <= history.delete().left(), "底部按钮不能重叠");
    }

    private static void checkInside(AssistantSecondaryLayout.Rect child,
                                    AssistantSecondaryLayout.Rect parent,
                                    String label) {
        check(child.left() >= parent.left()
                        && child.top() >= parent.top()
                        && child.right() <= parent.right()
                        && child.bottom() <= parent.bottom(),
                label + "必须限制在父区域内");
    }

    private static void checkInside(AssistantSecondaryLayout.Rect child,
                                    WindowBounds parent,
                                    String label) {
        check(child.left() >= parent.x()
                        && child.top() >= parent.y()
                        && child.right() <= parent.x() + parent.width()
                        && child.bottom() <= parent.y() + parent.height(),
                label + "必须限制在主窗口内");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

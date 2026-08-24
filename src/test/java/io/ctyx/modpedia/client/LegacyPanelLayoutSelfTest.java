package io.ctyx.modpedia.client;

/** 无图形环境下验证窗口边界和滚动模型。 */
public final class LegacyPanelLayoutSelfTest {
    private LegacyPanelLayoutSelfTest() {
    }

    public static void main(String[] args) {
        assertLayout(3364, 1016);
        assertLayout(480, 270);
        assertLayout(280, 180);
        assertLayout(256, 144);
        assertLayout(192, 96);

        LegacyScrollModel scroll = new LegacyScrollModel();
        scroll.configure(1000, 100);
        check(scroll.maxOffset() == 900, "max offset");
        scroll.scroll(-1, 24);
        check(scroll.offset() == 24, "scroll down");
        scroll.setOffset(10000);
        check(scroll.offset() == 900, "upper clamp");
        scroll.scroll(1, 24);
        check(scroll.offset() == 876, "scroll up");
        scroll.configure(20, 100);
        check(scroll.offset() == 0 && !scroll.canScroll(), "content shrink clamp");
        System.out.println("LegacyPanelLayoutSelfTest: OK");
    }

    private static void assertLayout(int width, int height) {
        LegacyPanelLayout.Layout layout = LegacyPanelLayout.calculate(width, height);
        LegacyPanelLayout.Rect window = layout.window();
        LegacyPanelLayout.Rect header = layout.header();
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        LegacyPanelLayout.Rect footer = layout.footer();
        check(window.x() >= 0 && window.y() >= 0, "window origin");
        check(window.right() <= width && window.bottom() <= height, "window bounds");
        check(header.x() >= window.x() && header.right() <= window.right(), "header bounds");
        check(viewport.x() >= window.x() && viewport.right() <= window.right(), "viewport x");
        check(viewport.y() >= window.y() && viewport.bottom() <= window.bottom(), "viewport y");
        check(footer.x() >= window.x() && footer.right() <= window.right(), "footer x");
        check(footer.y() >= viewport.bottom(), "footer does not cover content");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

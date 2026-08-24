package io.ctyx.modpedia.client;

/** 滚轮、拖动滚动条和边界约束回归。 */
public final class LegacyScrollModelSelfTest {
    private LegacyScrollModelSelfTest() {
    }

    public static void main(String[] args) {
        LegacyScrollModel model = new LegacyScrollModel();
        model.configure(1000, 200);
        check(model.canScroll(), "长内容应可滚动");
        model.scroll(-1, 18);
        check(model.offset() == 18, "滚轮向下未移动");
        model.dragTo(200, 200);
        check(model.offset() > 18, "拖动滚动条未移动");
        model.setOffset(10000);
        check(model.offset() == model.maxOffset(), "滚动超出底部未约束");
        model.configure(100, 200);
        check(!model.canScroll() && model.offset() == 0, "短内容未回到顶部");
        System.out.println("LegacyScrollModelSelfTest OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

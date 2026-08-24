package io.ctyx.modpedia.client;

/**
 * 1.12.2 助手窗口的纯几何布局。
 *
 * <p>绘制、输入框和按钮都使用同一份几何结果，避免窗口缩放后仍引用旧的
 * 固定坐标。这个类不依赖 Minecraft，方便在没有图形环境的测试中回归。</p>
 */
public final class LegacyPanelLayout {
    private LegacyPanelLayout() {
    }

    public static Layout calculate(int screenWidth, int screenHeight) {
        int safeWidth = Math.max(1, screenWidth);
        int safeHeight = Math.max(1, screenHeight);
        int panelWidth = Math.min(620, Math.max(280, safeWidth - 24));
        if (panelWidth > safeWidth) {
            panelWidth = safeWidth;
        }
        int panelLeft = Math.max(0, (safeWidth - panelWidth) / 2);
        // 外层留白只用于避免贴边；正文高度优先，不能把可滚动内容挤到输入框后面。
        int panelTop = safeHeight <= 128 ? 0 : Math.min(8, Math.max(2, (safeHeight - 180) / 2));
        int panelBottom = safeHeight <= 128 ? safeHeight : Math.min(safeHeight - 4, safeHeight - 8);
        if (panelBottom < panelTop + 96) {
            panelTop = 0;
            panelBottom = safeHeight;
        }

        int headerBottom = Math.min(panelBottom, panelTop + 26);
        int contentTop = Math.min(panelBottom, panelTop + 44);
        // 底部同时容纳目标提示、状态提示、输入框和操作按钮；正文只使用
        // footerTop 之前的区域，避免输入框或按钮覆盖正文最后一行。
        // 即使最小窗口只剩一行正文，也要给正文和页脚留下明确的边界；
        // 否则 footerTop == contentBottom 时，输入框会覆盖正文的最后一行。
        int footerTop = Math.min(panelBottom,
                Math.max(contentTop + 1, panelBottom - 56));
        int contentBottom = Math.max(contentTop, Math.min(panelBottom, footerTop - 6));
        Rect window = new Rect(panelLeft, panelTop, panelWidth, panelBottom - panelTop);
        Rect viewport = new Rect(panelLeft + 8, contentTop, Math.max(1, panelWidth - 16),
                Math.max(1, contentBottom - contentTop));
        Rect footer = new Rect(panelLeft + 8, footerTop, Math.max(1, panelWidth - 16),
                Math.max(1, panelBottom - footerTop - 4));
        return new Layout(window, new Rect(panelLeft, panelTop, panelWidth, headerBottom - panelTop),
                viewport, footer);
    }

    public static final class Layout {
        private final Rect window;
        private final Rect header;
        private final Rect contentViewport;
        private final Rect footer;

        private Layout(Rect window, Rect header, Rect contentViewport, Rect footer) {
            this.window = window;
            this.header = header;
            this.contentViewport = contentViewport;
            this.footer = footer;
        }

        public Rect window() {
            return window;
        }

        public Rect header() {
            return header;
        }

        public Rect contentViewport() {
            return contentViewport;
        }

        public Rect footer() {
            return footer;
        }
    }

    public static final class Rect {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(int pointX, int pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }
    }
}

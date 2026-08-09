package io.ctyx.modpedia.client;

/** 浮窗位置和尺寸，以及视口边界约束。 */
public record WindowBounds(int x, int y, int width, int height) {
    public static final int MIN_WIDTH = 160;
    public static final int MIN_HEIGHT = 110;
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 400;
    public static final int MAX_WIDTH = 720;
    public static final int MAX_HEIGHT = 720;
    public static final int VIEWPORT_RATIO_PERCENT = 85;
    public static final int VIEWPORT_MARGIN = 12;

    public static WindowBounds defaultFor(int viewportWidth, int viewportHeight) {
        int maxWidth = maxWidthFor(viewportWidth);
        int maxHeight = maxHeightFor(viewportHeight);
        int width = Math.min(DEFAULT_WIDTH, maxWidth);
        int height = Math.min(DEFAULT_HEIGHT, maxHeight);
        int x = (viewportWidth - width) / 2 + Math.min(72, viewportWidth / 10);
        int y = (viewportHeight - height) / 2;
        return new WindowBounds(x, y, width, height).clampTo(viewportWidth, viewportHeight);
    }

    public WindowBounds clampTo(int viewportWidth, int viewportHeight) {
        int marginX = marginFor(viewportWidth);
        int marginY = marginFor(viewportHeight);
        int minimumWidth = Math.min(MIN_WIDTH, Math.max(1, viewportWidth - marginX * 2));
        int minimumHeight = Math.min(MIN_HEIGHT, Math.max(1, viewportHeight - marginY * 2));
        int maximumWidth = Math.max(minimumWidth, Math.min(MAX_WIDTH, maxWidthFor(viewportWidth)));
        int maximumHeight = Math.max(minimumHeight, Math.min(MAX_HEIGHT, maxHeightFor(viewportHeight)));
        int clampedWidth = clamp(width, minimumWidth, maximumWidth);
        int clampedHeight = clamp(height, minimumHeight, maximumHeight);
        int maxX = Math.max(marginX, viewportWidth - marginX - clampedWidth);
        int maxY = Math.max(marginY, viewportHeight - marginY - clampedHeight);
        return new WindowBounds(
                clamp(x, marginX, maxX),
                clamp(y, marginY, maxY),
                clampedWidth,
                clampedHeight
        );
    }

    public WindowBounds resize(ResizeEdge edge, int deltaX, int deltaY, int viewportWidth, int viewportHeight) {
        WindowBounds base = clampTo(viewportWidth, viewportHeight);
        int marginX = marginFor(viewportWidth);
        int marginY = marginFor(viewportHeight);
        int minimumWidth = Math.min(MIN_WIDTH, Math.max(1, viewportWidth - marginX * 2));
        int minimumHeight = Math.min(MIN_HEIGHT, Math.max(1, viewportHeight - marginY * 2));
        int maximumWidth = Math.max(minimumWidth, Math.min(MAX_WIDTH, maxWidthFor(viewportWidth)));
        int maximumHeight = Math.max(minimumHeight, Math.min(MAX_HEIGHT, maxHeightFor(viewportHeight)));
        int left = base.x();
        int top = base.y();
        int right = base.x() + base.width();
        int bottom = base.y() + base.height();

        if (edge.left()) {
            left = clamp(base.x() + deltaX, marginX, right - minimumWidth);
            left = Math.max(left, right - maximumWidth);
        } else if (edge.right()) {
            right = clamp(base.x() + base.width() + deltaX, left + minimumWidth, left + maximumWidth);
            right = Math.min(right, viewportWidth - marginX);
        }

        if (edge.top()) {
            top = clamp(base.y() + deltaY, marginY, bottom - minimumHeight);
            top = Math.max(top, bottom - maximumHeight);
        } else if (edge.bottom()) {
            bottom = clamp(base.y() + base.height() + deltaY, top + minimumHeight, top + maximumHeight);
            bottom = Math.min(bottom, viewportHeight - marginY);
        }

        return new WindowBounds(left, top, right - left, bottom - top).clampTo(viewportWidth, viewportHeight);
    }

    public enum ResizeEdge {
        NONE(false, false, false, false),
        LEFT(true, false, false, false),
        RIGHT(false, true, false, false),
        TOP(false, false, true, false),
        BOTTOM(false, false, false, true),
        TOP_LEFT(true, false, true, false),
        TOP_RIGHT(false, true, true, false),
        BOTTOM_LEFT(true, false, false, true),
        BOTTOM_RIGHT(false, true, false, true);

        private final boolean left;
        private final boolean right;
        private final boolean top;
        private final boolean bottom;

        ResizeEdge(boolean left, boolean right, boolean top, boolean bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }

        public boolean left() {
            return left;
        }

        public boolean right() {
            return right;
        }

        public boolean top() {
            return top;
        }

        public boolean bottom() {
            return bottom;
        }
    }

    private static int maxWidthFor(int viewportWidth) {
        return Math.max(1, viewportWidth * VIEWPORT_RATIO_PERCENT / 100);
    }

    private static int maxHeightFor(int viewportHeight) {
        return Math.max(1, viewportHeight * VIEWPORT_RATIO_PERCENT / 100);
    }

    private static int marginFor(int viewportSize) {
        return Math.min(VIEWPORT_MARGIN, Math.max(0, viewportSize / 2));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

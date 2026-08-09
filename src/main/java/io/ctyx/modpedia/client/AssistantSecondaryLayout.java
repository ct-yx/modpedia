package io.ctyx.modpedia.client;

/**
 * 助手二级页面的纯几何布局。
 *
 * <p>历史和设置共用同一个主窗口；所有页面矩形都从主窗口推导，避免
 * 二级页面因为视口尺寸或旧配置而脱离浮窗。</p>
 */
final class AssistantSecondaryLayout {
    static final int PAGE_INSET = 2;
    private static final double MIN_SCALE = 0.62;
    private static final double MAX_SCALE = 1.18;
    private static final int BASE_PAGE_WIDTH = 420;
    private static final int BASE_PAGE_HEIGHT = 360;
    private static final int BASE_TITLE_HEIGHT = 26;
    private static final int BASE_BUTTON_HEIGHT = 20;
    private static final int BASE_LIST_GAP = 6;
    private static final int BASE_FOOTER_HEIGHT = 28;

    private AssistantSecondaryLayout() {
    }

    static Rect page(WindowBounds window) {
        return page(window, FloatingAssistantWindow.headerHeight(window));
    }

    static Rect page(WindowBounds window, int headerHeight) {
        int left = window.x() + PAGE_INSET;
        int top = window.y() + headerHeight;
        int right = window.x() + window.width() - PAGE_INSET;
        int bottom = window.y() + window.height() - PAGE_INSET;
        return new Rect(
                left,
                top,
                Math.max(1, right - left),
                Math.max(1, bottom - top)
        );
    }

    static History history(Rect page) {
        return history(page, 9);
    }

    static History history(Rect page, int fontLineHeight) {
        double scale = scaleFor(page);
        int readableLineHeight = Math.max(1, fontLineHeight);
        int titleHeight = Math.max(
                scaled(BASE_TITLE_HEIGHT, scale, 16),
                readableLineHeight + 4
        );
        int buttonHeight = Math.max(
                scaled(BASE_BUTTON_HEIGHT, scale, 16),
                readableLineHeight + 6
        );
        int listGap = scaled(BASE_LIST_GAP, scale, 4);
        int footerHeight = Math.max(
                scaled(BASE_FOOTER_HEIGHT, scale, 18),
                readableLineHeight + 8
        );
        int horizontalPadding = scaled(8, scale, 5);
        int newTop = page.top() + titleHeight;
        int newHeight = Math.max(1, Math.min(buttonHeight, page.bottom() - newTop));
        Rect newConversation = new Rect(
                page.left() + horizontalPadding,
                newTop,
                Math.max(1, page.width() - horizontalPadding * 2),
                newHeight
        );

        int listTop = newConversation.bottom() + listGap;
        int footerTop = Math.max(page.top() + titleHeight, page.bottom() - footerHeight);
        // WindowBounds 的最小高度足够容纳这三个区域；这个分支只处理
        // 外部夹具传入的极小矩形，仍保证列表和页脚不互相穿透。
        if (footerTop <= listTop) {
            footerTop = Math.min(page.bottom() - 1, listTop + 1);
        }
        Rect list = new Rect(
                page.left() + horizontalPadding,
                listTop,
                Math.max(1, page.width() - horizontalPadding * 2),
                Math.max(1, footerTop - listTop)
        );
        Rect footer = new Rect(
                page.left() + horizontalPadding,
                footerTop,
                Math.max(1, page.width() - horizontalPadding * 2),
                Math.max(1, page.bottom() - footerTop)
        );
        int buttonGap = scaled(8, scale, 4);
        int buttonWidth = Math.max(1, (footer.width() - buttonGap) / 2);
        Rect rename = new Rect(footer.left(), footer.top(), buttonWidth,
                Math.min(buttonHeight, footer.height()));
        Rect delete = new Rect(rename.right() + buttonGap, footer.top(),
                Math.max(1, footer.right() - rename.right() - buttonGap), rename.height());
        return new History(newConversation, list, footer, rename, delete, scale);
    }

    static double scaleFor(Rect page) {
        double scale = Math.min(
                (double) page.width() / BASE_PAGE_WIDTH,
                (double) page.height() / BASE_PAGE_HEIGHT
        );
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    private static int scaled(int value, double scale, int minimum) {
        return Math.max(minimum, (int) Math.round(value * scale));
    }

    static Rect clip(Rect candidate, Rect container) {
        int left = Math.max(candidate.left(), container.left());
        int top = Math.max(candidate.top(), container.top());
        int right = Math.min(candidate.right(), container.right());
        int bottom = Math.min(candidate.bottom(), container.bottom());
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Rect(left, top, right - left, bottom - top);
    }

    record History(Rect newConversation, Rect list, Rect footer, Rect rename, Rect delete, double scale) {
    }

    record Rect(int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }
}

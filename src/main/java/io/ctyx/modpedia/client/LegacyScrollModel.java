package io.ctyx.modpedia.client;

/** 旧版 GUI 通用的像素滚动模型。 */
public final class LegacyScrollModel {
    private double offset;
    private int contentExtent;
    private int viewportExtent;

    public void configure(int contentExtent, int viewportExtent) {
        this.contentExtent = Math.max(0, contentExtent);
        this.viewportExtent = Math.max(0, viewportExtent);
        clamp();
    }

    public void scroll(int wheelDelta, int step) {
        if (wheelDelta == 0) {
            return;
        }
        int safeStep = Math.max(1, step);
        offset -= wheelDelta > 0 ? safeStep : -safeStep;
        clamp();
    }

    public void setOffset(int value) {
        offset = value;
        clamp();
    }

    public int offset() {
        return (int) Math.round(offset);
    }

    public int maxOffset() {
        return Math.max(0, contentExtent - viewportExtent);
    }

    public boolean canScroll() {
        return maxOffset() > 0;
    }

    public int thumbSize(int trackExtent) {
        if (trackExtent <= 0 || viewportExtent <= 0 || contentExtent <= 0) {
            return 0;
        }
        return Math.max(18, Math.min(trackExtent,
                trackExtent * viewportExtent / Math.max(viewportExtent, contentExtent)));
    }

    public int thumbOffset(int trackExtent) {
        int thumb = thumbSize(trackExtent);
        int available = Math.max(0, trackExtent - thumb);
        return maxOffset() == 0 ? 0 : available * offset() / maxOffset();
    }

    public void dragTo(int mouseOffset, int trackExtent) {
        int thumb = thumbSize(trackExtent);
        int available = Math.max(1, trackExtent - thumb);
        int clamped = Math.max(0, Math.min(available, mouseOffset - thumb / 2));
        setOffset((int) Math.round((double) clamped * maxOffset() / available));
    }

    public int contentExtent() {
        return contentExtent;
    }

    public int viewportExtent() {
        return viewportExtent;
    }

    private void clamp() {
        double maximum = maxOffset();
        if (offset < 0) {
            offset = 0;
        } else if (offset > maximum) {
            offset = maximum;
        }
    }
}

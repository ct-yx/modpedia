package io.ctyx.modpedia.clientcore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只允许用户确认或手动标记的物品进入运行时 Tooltip 请求。 */
public final class RuntimeTooltipCoordinator {
    public interface Reader {
        String readTooltip(String itemId);
    }

    public static final class Result {
        private final String itemId;
        private final boolean available;
        private final String tooltip;
        private final String reason;
        private Result(String itemId, boolean available, String tooltip, String reason) {
            this.itemId = itemId;
            this.available = available;
            this.tooltip = tooltip == null ? "" : tooltip;
            this.reason = reason == null ? "" : reason;
        }
        public String itemId() { return itemId; }
        public boolean available() { return available; }
        public String tooltip() { return tooltip; }
        public String reason() { return reason; }
        public static Result unavailable(String itemId, String reason) { return new Result(itemId, false, "", reason); }
        public static Result available(String itemId, String tooltip) { return new Result(itemId, true, tooltip, ""); }
    }

    private final Map<String, Boolean> approved = new LinkedHashMap<String, Boolean>();
    private final Map<String, Result> cache = new LinkedHashMap<String, Result>();

    public synchronized void approve(String itemId) { approved.put(require(itemId), Boolean.TRUE); }
    public synchronized void revoke(String itemId) { approved.remove(require(itemId)); cache.remove(itemId); }
    public synchronized boolean isApproved(String itemId) { return Boolean.TRUE.equals(approved.get(itemId)); }

    public synchronized Result read(String itemId, Reader reader) {
        String id = require(itemId);
        if (!isApproved(id)) return Result.unavailable(id, "item_not_confirmed");
        if (reader == null) return Result.unavailable(id, "reader_unavailable");
        Result previous = cache.get(id);
        if (previous != null) return previous;
        String tooltip = reader.readTooltip(id);
        if (tooltip == null || tooltip.trim().isEmpty()) return Result.unavailable(id, "tooltip_unavailable");
        Result result = Result.available(id, tooltip);
        cache.put(id, result);
        return result;
    }

    public synchronized Map<String, Result> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Result>(cache));
    }

    private static String require(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("item_id 不能为空");
        return value.trim();
    }
}

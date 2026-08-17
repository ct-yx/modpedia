package io.ctyx.modpedia.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 使用一次构建的前缀树匹配正文中的本地化物品名称。
 *
 * <p>名称索引只在语言或注册表快照变化时构建。渲染帧内只沿着一条前缀树
 * 查找最长名称，避免对数万候选名称逐个排序和比较。</p>
 */
final class ItemNameMatcher {
    private static final ItemNameMatcher EMPTY = new ItemNameMatcher(new Node());

    private final Node root;

    private ItemNameMatcher(Node root) {
        this.root = root;
    }

    static ItemNameMatcher empty() {
        return EMPTY;
    }

    static ItemNameMatcher from(Map<String, String> uniqueNames) {
        if (uniqueNames == null || uniqueNames.isEmpty()) {
            return EMPTY;
        }
        Node root = new Node();
        for (Map.Entry<String, String> entry : uniqueNames.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().strip();
            String itemId = entry.getValue() == null ? "" : entry.getValue().strip();
            // 单字中文物品名（如“弓”“桶”）也必须能在 Ctrl 模式下还原为 ID。
            // ASCII 单字符名称仍由 displayNameBoundary() 约束，不会替换单词内部字符。
            if (name.isBlank() || itemId.isBlank()) {
                continue;
            }
            Node node = root;
            String normalized = name.toLowerCase(Locale.ROOT);
            for (int index = 0; index < normalized.length(); index++) {
                node = node.children.computeIfAbsent(normalized.charAt(index), ignored -> new Node());
            }
            if (!node.ambiguous) {
                if (node.itemId == null) {
                    node.itemId = itemId;
                } else if (!node.itemId.equals(itemId)) {
                    // DISPLAY_NAME 索引以原始大小写保存，但 Trie 查询不区分大小写。
                    // 两个只在大小写上不同且指向不同 ID 的名称不能猜测其物品。
                    node.itemId = null;
                    node.ambiguous = true;
                }
            }
            node.nameLength = name.length();
        }
        return root.children.isEmpty() ? EMPTY : new ItemNameMatcher(root);
    }

    Match longestMatch(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()) {
            return null;
        }
        Node node = root;
        Match result = null;
        for (int index = offset; index < text.length(); index++) {
            node = node.children.get(Character.toLowerCase(text.charAt(index)));
            if (node == null) {
                break;
            }
            if (node.itemId != null && !node.ambiguous) {
                result = new Match(node.itemId, node.nameLength);
            }
        }
        return result;
    }

    record Match(String itemId, int length) {
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private String itemId;
        private int nameLength;
        private boolean ambiguous;
    }
}

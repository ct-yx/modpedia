package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ModPedia;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用可选手册模组的公开 API 打开搜索结果对应的原始页面。 */
public final class ManualSourceNavigator implements SourceNavigator {
    private static final Pattern PATCHOULI_PATH = Pattern.compile(
            "^(?:assets|data)/([^/]+)/patchouli_books/([^/]+)(?:/[a-z]{2}_[a-z]{2})?/(.+?)(?:\\.json|\\.md)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GUIDE_PATH = Pattern.compile(
            "^(?:assets|data)/([^/]+)/(.+?)(?:\\.md|\\.json)$",
            Pattern.CASE_INSENSITIVE
    );
    private final AppSourceNavigator appNavigator = new AppSourceNavigator();

    @Override
    public boolean open(SourceReference source) {
        if (source == null || Minecraft.getInstance().player == null) {
            return false;
        }

        try {
            if (AppSourceNavigator.isAppSource(source)) {
                return appNavigator.open(source);
            }
            PatchouliTarget patchouli = patchouliTarget(source.sourcePath());
            if (patchouli != null) {
                return openPatchouli(patchouli);
            }

            GuideTarget guide = guideTarget(source);
            if (guide != null) {
                return openGuideMe(guide);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ModPedia.LOGGER.warn("打开手册来源失败：{}", source.documentId(), exception);
        }
        return false;
    }

    private boolean openPatchouli(PatchouliTarget target) throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("vazkii.patchouli.api.PatchouliAPI");
        Object api = apiClass.getMethod("get").invoke(null);
        Class<?> apiType = Class.forName("vazkii.patchouli.api.PatchouliAPI$IPatchouliAPI");
        if ((boolean) apiType.getMethod("isStub").invoke(api)) {
            return false;
        }

        if (target.entryPath() == null) {
            apiType.getMethod("openBookGUI", ResourceLocation.class)
                    .invoke(api, target.bookId());
        } else {
            ResourceLocation entryId = ResourceLocation.fromNamespaceAndPath(
                    target.bookId().getNamespace(),
                    target.entryPath()
            );
            apiType.getMethod("openBookEntry", ResourceLocation.class, ResourceLocation.class, int.class)
                    .invoke(api, target.bookId(), entryId, 0);
        }
        return true;
    }

    private boolean openGuideMe(GuideTarget target) throws ReflectiveOperationException {
        Class<?> guidesClass = Class.forName("guideme.Guides");
        Class<?> guideClass = Class.forName("guideme.Guide");
        Method getAll = guidesClass.getMethod("getAll");
        Collection<?> guides = (Collection<?>) getAll.invoke(null);
        Method getId = guideClass.getMethod("getId");
        Method getFolder = guideClass.getMethod("getContentRootFolder");
        Method pageExists = guideClass.getMethod("pageExists", ResourceLocation.class);
        Object fallbackGuideId = null;

        for (Object guide : guides) {
            ResourceLocation guideId = (ResourceLocation) getId.invoke(guide);
            if (!target.namespace().equals(guideId.getNamespace())) {
                continue;
            }
            if (fallbackGuideId == null) {
                fallbackGuideId = guideId;
            }
            String folder = String.valueOf(getFolder.invoke(guide)).replace('\\', '/');
            for (ResourceLocation pageId : target.pageCandidates(folder)) {
                if (!(boolean) pageExists.invoke(guide, pageId)) {
                    continue;
                }
                Class<?> pageAnchorClass = Class.forName("guideme.PageAnchor");
                Object anchor = pageAnchorClass.getMethod("page", ResourceLocation.class)
                        .invoke(null, pageId);
                Class<?> guidesCommon = Class.forName("guideme.GuidesCommon");
                guidesCommon.getMethod("openGuide", Player.class, ResourceLocation.class, pageAnchorClass)
                        .invoke(null, Minecraft.getInstance().player, guideId, anchor);
                return true;
            }
        }

        // 即使来源页在资源重载期间暂时不可见，也先打开对应模组的 GuideME
        // 根页面，避免点击来源卡片后完全没有反馈。
        if (fallbackGuideId != null) {
            Class<?> guidesCommon = Class.forName("guideme.GuidesCommon");
            guidesCommon.getMethod("openGuide", Player.class, ResourceLocation.class)
                    .invoke(null, Minecraft.getInstance().player, fallbackGuideId);
            return true;
        }
        return false;
    }

    private PatchouliTarget patchouliTarget(String sourcePath) {
        Matcher matcher = PATCHOULI_PATH.matcher(normalize(sourcePath));
        if (!matcher.matches()) {
            return null;
        }
        String relative = removeExtension(matcher.group(3));
        String entryPath = relative.startsWith("entries/")
                ? relative.substring("entries/".length())
                : null;
        return new PatchouliTarget(
                ResourceLocation.fromNamespaceAndPath(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2)),
                entryPath
        );
    }

    private GuideTarget guideTarget(SourceReference source) {
        String normalized = normalize(source.sourcePath());
        Matcher matcher = GUIDE_PATH.matcher(normalized);
        if (!matcher.matches() || normalized.contains("/patchouli_books/")) {
            return null;
        }
        String namespace = matcher.group(1).toLowerCase(Locale.ROOT);
        // GuideME 的页面索引键保留 .md 后缀；这里不能像文档 ID 一样
        // 直接去掉扩展名，否则 pageExists 永远匹配不到页面。
        String relative = matcher.group(2);
        String documentPath = pathPart(source.documentId());
        return new GuideTarget(namespace, relative, documentPath);
    }

    private String pathPart(String documentId) {
        int separator = documentId == null ? -1 : documentId.indexOf(':');
        return separator < 0 ? "" : documentId.substring(separator + 1);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private String removeExtension(String value) {
        int extension = value.lastIndexOf('.');
        return extension > 0 ? value.substring(0, extension) : value;
    }

    static List<String> guidePageCandidatePaths(
            String namespace,
            String resourcePath,
            String documentPath,
            String folder
    ) {
        return new GuideTarget(namespace, resourcePath, documentPath)
                .pageCandidates(folder)
                .stream()
                .map(ResourceLocation::toString)
                .toList();
    }

    private record PatchouliTarget(ResourceLocation bookId, String entryPath) {
    }

    private record GuideTarget(String namespace, String resourcePath, String documentPath) {
        private List<ResourceLocation> pageCandidates(String folder) {
            Set<String> candidates = new LinkedHashSet<>();
            String normalizedFolder = folder == null ? "" : folder.replace('\\', '/');
            for (String prefix : List.of(normalizedFolder, "guides/" + normalizedFolder, "guideme_guides/" + normalizedFolder)) {
                if (!prefix.isBlank() && resourcePath.startsWith(prefix + "/")) {
                    addPageCandidate(candidates, resourcePath.substring(prefix.length() + 1));
                }
            }
            addPageCandidate(candidates, resourcePath);
            if (!documentPath.isBlank()) {
                addPageCandidate(candidates, documentPath);
                if (!normalizedFolder.isBlank() && documentPath.startsWith(normalizedFolder + "/")) {
                    addPageCandidate(candidates, documentPath.substring(normalizedFolder.length() + 1));
                }
            }

            List<ResourceLocation> result = new ArrayList<>();
            for (String candidate : candidates) {
                if (!candidate.isBlank()) {
                    result.add(ResourceLocation.fromNamespaceAndPath(namespace, candidate));
                }
            }
            return result;
        }

        private static void addPageCandidate(Set<String> candidates, String candidate) {
            String normalized = candidate == null ? "" : candidate.replace('\\', '/').replaceAll("^/+", "");
            if (normalized.isBlank()) {
                return;
            }
            candidates.add(normalized.endsWith(".md") ? normalized : normalized + ".md");
        }
    }
}

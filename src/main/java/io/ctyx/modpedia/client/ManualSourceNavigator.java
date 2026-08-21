package io.ctyx.modpedia.client;

import io.ctyx.modpedia.api.SourceReference;
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
        Method getDefaultNamespace = optionalMethod(guideClass, "getDefaultNamespace");
        Method getPages = optionalMethod(guideClass, "getPages");
        Method getParsedPageId = optionalParsedPageId();
        Object fallbackGuideId = null;

        for (Object guide : guides) {
            ResourceLocation guideId = (ResourceLocation) getId.invoke(guide);
            String folder = String.valueOf(getFolder.invoke(guide)).replace('\\', '/');
            String defaultNamespace = getDefaultNamespace == null
                    ? ""
                    : String.valueOf(getDefaultNamespace.invoke(guide));
            if (fallbackGuideId == null
                    && (target.matchesFolder(folder)
                    || target.namespace().equals(guideId.getNamespace())
                    || target.namespace().equals(defaultNamespace))) {
                fallbackGuideId = guideId;
            }

            // 先从 GuideME 已加载的页面集合中寻找真实 ID。扩展手册的页面
            // namespace、语言目录和资源文件夹不一定与书籍 ID 相同，不能只
            // 依赖手动拼出来的 namespace/path。
            ResourceLocation loadedPageId = findLoadedPageId(guide, target, folder, getPages, getParsedPageId);
            if (loadedPageId != null) {
                openGuidePage(guideId, loadedPageId);
                return true;
            }

            // GuideME 的书籍可以由一个模组注册，但页面资源由其它内容模组提供。
            // 页面 ID 的 namespace 应优先使用来源模组，再尝试书籍默认 namespace
            // 和书籍 ID namespace；不能用 guideId.namespace 过滤掉扩展手册。
            for (String pageNamespace : target.pageNamespaces(guideId, defaultNamespace)) {
                for (ResourceLocation pageId : target.pageCandidates(pageNamespace, folder)) {
                    if (!(boolean) pageExists.invoke(guide, pageId)) {
                        continue;
                    }
                    openGuidePage(guideId, pageId);
                    return true;
                }
            }
        }

        // 即使来源页在资源重载期间暂时不可见，也先打开对应模组的 GuideME
            // 根页面，避免点击正文来源标注后完全没有反馈。
        if (fallbackGuideId != null) {
            Class<?> guidesCommon = Class.forName("guideme.GuidesCommon");
            guidesCommon.getMethod("openGuide", Player.class, ResourceLocation.class)
                    .invoke(null, Minecraft.getInstance().player, fallbackGuideId);
            return true;
        }
        return false;
    }

    private Method optionalParsedPageId() {
        try {
            return Class.forName("guideme.compiler.ParsedGuidePage").getMethod("getId");
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return null;
        }
    }

    private ResourceLocation findLoadedPageId(
            Object guide,
            GuideTarget target,
            String folder,
            Method getPages,
            Method getParsedPageId
    ) {
        if (getPages == null || getParsedPageId == null || !target.matchesFolder(folder)) {
            return null;
        }
        try {
            Object pagesValue = getPages.invoke(guide);
            if (!(pagesValue instanceof Collection<?> pages)) {
                return null;
            }
            for (Object page : pages) {
                Object pageIdValue = getParsedPageId.invoke(page);
                if (pageIdValue instanceof ResourceLocation pageId && target.matchesPage(pageId, folder)) {
                    return pageId;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 页面集合可能在资源重载过程中尚未就绪，继续使用 pageExists 和根页回退。
        }
        return null;
    }

    private void openGuidePage(ResourceLocation guideId, ResourceLocation pageId) throws ReflectiveOperationException {
        Class<?> pageAnchorClass = Class.forName("guideme.PageAnchor");
        Object anchor = pageAnchorClass.getMethod("page", ResourceLocation.class)
                .invoke(null, pageId);
        Class<?> guidesCommon = Class.forName("guideme.GuidesCommon");
        guidesCommon.getMethod("openGuide", Player.class, ResourceLocation.class, pageAnchorClass)
                .invoke(null, Minecraft.getInstance().player, guideId, anchor);
    }

    private Method optionalMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
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
        return value == null ? "" : value.replace('\\', '/').replaceAll("^/+", "");
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
                .pageCandidates(namespace, folder)
                .stream()
                .map(ResourceLocation::toString)
                .toList();
    }

    /** 使用完整来源对象生成 GuideME 页面候选，覆盖 manifest 中的 assets/data 路径。 */
    static List<String> guidePageCandidatePaths(SourceReference source, String folder) {
        if (source == null) {
            return List.of();
        }
        GuideTarget target = new ManualSourceNavigator().guideTarget(source);
        if (target == null) {
            return List.of();
        }
        return target.pageCandidates(target.namespace(), folder).stream()
                .map(ResourceLocation::toString)
                .toList();
    }

    static boolean guideFolderMatches(String resourcePath, String folder) {
        return new GuideTarget("", resourcePath, "").matchesFolder(folder);
    }

    private record PatchouliTarget(ResourceLocation bookId, String entryPath) {
    }

    private record GuideTarget(String namespace, String resourcePath, String documentPath) {
        private boolean matchesFolder(String folder) {
            String normalizedFolder = folder == null ? "" : folder.replace('\\', '/');
            return !normalizedFolder.isBlank()
                    && (resourcePath.startsWith(normalizedFolder + "/")
                    || documentPath.startsWith(normalizedFolder + "/"));
        }

        private boolean matchesPage(ResourceLocation pageId, String folder) {
            if (pageId == null) {
                return false;
            }
            for (String candidate : pageCandidatePaths(folder)) {
                if (pageId.getPath().equals(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private List<String> pageCandidatePaths(String folder) {
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
            return List.copyOf(candidates);
        }

        private List<String> pageNamespaces(ResourceLocation guideId, String defaultNamespace) {
            Set<String> namespaces = new LinkedHashSet<>();
            if (namespace != null && !namespace.isBlank()) {
                namespaces.add(namespace);
            }
            if (defaultNamespace != null && !defaultNamespace.isBlank()) {
                namespaces.add(defaultNamespace);
            }
            if (guideId != null && !guideId.getNamespace().isBlank()) {
                namespaces.add(guideId.getNamespace());
            }
            return List.copyOf(namespaces);
        }

        private List<ResourceLocation> pageCandidates(String pageNamespace, String folder) {
            List<ResourceLocation> result = new ArrayList<>();
            for (String candidate : pageCandidatePaths(folder)) {
                if (!candidate.isBlank()) {
                    result.add(ResourceLocation.fromNamespaceAndPath(pageNamespace, candidate));
                }
            }
            return result;
        }

        private static void addPageCandidate(Set<String> candidates, String candidate) {
            String normalized = candidate == null ? "" : candidate.replace('\\', '/').replaceAll("^/+", "");
            if (normalized.isBlank()) {
                return;
            }
            String withExtension = normalized.endsWith(".md") ? normalized : normalized + ".md";
            candidates.add(withExtension);

            // GuideME 将 `_zh_cn/`、`_en_us/` 等目录作为翻译来源，加载后
            // 页面索引仍使用不带语言目录的基础页面 ID。
            Matcher languageDirectory = Pattern.compile("^_[a-z]{2}_[a-z]{2}/(.+)$", Pattern.CASE_INSENSITIVE)
                    .matcher(withExtension);
            if (languageDirectory.matches()) {
                candidates.add(languageDirectory.group(1));
            }
        }
    }
}

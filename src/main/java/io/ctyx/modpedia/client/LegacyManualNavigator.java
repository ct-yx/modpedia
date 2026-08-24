package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiOpenEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 1.12.2 可选手册跳转适配。
 *
 * <p>Patchouli、Guide API、FTB Guides 和 Mantle/TConstruct 都是软依赖，因此这里不直接引用它们
 * 的类。打开手册前记住助手页面；手册关闭时通过 GuiOpenEvent 恢复助手，避免
 * 旧版 GUI 直接退回游戏画面。</p>
 */
public final class LegacyManualNavigator {
    private static GuiScreen returnScreen;
    private static boolean waitingForReturn;

    private LegacyManualNavigator() {
    }

    public static boolean open(GuiScreen assistant, LegacySourceReference source) {
        if (assistant == null || source == null) {
            return false;
        }
        if (openPatchouli(assistant, source)) {
            return true;
        }
        if (openFtbGuides(assistant, source)) {
            return true;
        }
        if (openGuideApi(assistant, source)) {
            return true;
        }
        if (openMantle(assistant, source)) {
            return true;
        }
        if (openForestry(assistant, source)) {
            return true;
        }
        if (openThaumcraftResearch(assistant, source)) {
            return true;
        }
        if (openLogisticsPipes(assistant, source)) {
            return true;
        }
        return false;
    }

    /**
     * 只按 1.12.2 实际支持的资源路径判断来源是否具备跳转入口。
     * GuideME、Modonomicon 等高版本格式不会命中这里。
     */
    public static boolean supports(LegacySourceReference source) {
        String path = normalizedPath(source).replace("!", "");
        if (path.contains("patchouli_books/") || isGuideApiPath(path)
                || isFtbGuidesPath(path)) {
            return true;
        }
        if (isMantleBookPath(path)) {
            return true;
        }
        if (isForestryPath(path)) {
            return true;
        }
        if (isResearchPath(path)) {
            return true;
        }
        return isLogisticsPipesPath(path);
    }

    /** 供无图形自测试验证 Mantle/TConstruct 页面路径转换。 */
    static String mantlePageIdForTest(String path) {
        return mantlePageId(path == null ? ""
                : path.replace('\\', '/').replace("!", "").toLowerCase());
    }

    /** 供无图形自测试验证实例根目录 Patchouli 书籍的命名空间回退。 */
    static String patchouliBookIdForTest(String path) {
        return patchouliBookId(new LegacySourceReference(
                "test", "test", path, ""));
    }

    /** 供无图形自测试验证 Forestry 分类/条目路径转换。 */
    static String forestryPageForTest(String path) {
        return forestryPage(path == null ? ""
                : path.replace('\\', '/').replace("!", "").toLowerCase());
    }

    /** 供无图形自测试验证 Logistics Pipes 页面路径转换。 */
    static String logisticsPipesPageForTest(String path) {
        return logisticsPipesPage(path == null ? ""
                : path.replace('\\', '/').replace("!", "").toLowerCase());
    }

    public static void handleGuiOpen(GuiOpenEvent event) {
        if (!waitingForReturn || event == null) {
            return;
        }
        GuiScreen gui = event.getGui();
        if (gui == null) {
            GuiScreen screen = returnScreen;
            returnScreen = null;
            waitingForReturn = false;
            if (screen instanceof LegacyAssistantScreen) {
                ((LegacyAssistantScreen) screen).resumeAfterManualNavigation();
            }
            event.setGui(screen);
        }
    }

    private static boolean openPatchouli(GuiScreen assistant, LegacySourceReference source) {
        String bookId = patchouliBookId(source);
        if (bookId.isEmpty()) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("vazkii.patchouli.api.PatchouliAPI");
            Field field = apiClass.getField("instance");
            Object api = field.get(null);
            if (api == null) {
                return false;
            }
            remember(assistant);
            if (openPatchouliEntry(bookId, patchouliEntryPath(source))) {
                return true;
            }
            Method open = api.getClass().getMethod("openBookGUI", ResourceLocation.class);
            open.invoke(api, new ResourceLocation(bookId));
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    /**
     * Patchouli 1.12 的来源路径包含完整的 entries 相对路径。优先打开具体条目，
     * 只有运行时目录没有该条目时才回退到书籍首页。
     */
    private static boolean openPatchouliEntry(String bookId, String entryPath) {
        if (entryPath.isEmpty()) {
            return false;
        }
        try {
            Class<?> bookClass = Class.forName("vazkii.patchouli.common.book.Book");
            Class<?> registryClass = Class.forName("vazkii.patchouli.common.book.BookRegistry");
            Object registry = registryClass.getField("INSTANCE").get(null);
            Object booksValue = registryClass.getField("books").get(registry);
            if (!(booksValue instanceof Map)) {
                return false;
            }
            ResourceLocation bookResource = new ResourceLocation(bookId);
            Object book = ((Map<?, ?>) booksValue).get(bookResource);
            if (book == null) {
                return false;
            }

            Object contents = bookClass.getField("contents").get(book);
            if (contents == null) {
                return false;
            }
            Class<?> contentsClass = Class.forName("vazkii.patchouli.client.book.BookContents");
            Object entriesValue = contentsClass.getField("entries").get(contents);
            if (!(entriesValue instanceof Map)) {
                return false;
            }
            ResourceLocation entryResource = new ResourceLocation(bookResource.getResourceDomain(),
                    entryPath);
            Object entry = ((Map<?, ?>) entriesValue).get(entryResource);
            if (entry == null) {
                return false;
            }

            Class<?> guiBook = Class.forName("vazkii.patchouli.client.book.gui.GuiBook");
            Class<?> guiEntry = Class.forName("vazkii.patchouli.client.book.gui.GuiBookEntry");
            Object screen = guiEntry.getConstructor(bookClass,
                    Class.forName("vazkii.patchouli.client.book.BookEntry"))
                    .newInstance(book, entry);
            contentsClass.getMethod("openLexiconGui", guiBook, boolean.class)
                    .invoke(contents, screen, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean openFtbGuides(GuiScreen assistant, LegacySourceReference source) {
        String path = normalizedPath(source);
        if (path.indexOf("ftbguides") < 0 && path.indexOf("ftb_guides") < 0
                && !source.getDocumentId().toLowerCase().contains("ftbguides")) {
            return false;
        }
        try {
            Class<?> guides = Class.forName("com.feed_the_beast.mods.ftbguides.FTBGuides");
            Method open = guides.getMethod("openGuidesGui", String.class);
            remember(assistant);
            open.invoke(null, guidePage(path));
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    private static boolean openGuideApi(GuiScreen assistant, LegacySourceReference source) {
        String path = normalizedPath(source);
        if (path.indexOf("guideapi") < 0 && path.indexOf("guide_api") < 0
                && path.indexOf("assets/chisel_guide/") < 0
                && path.indexOf("assets/bloodmagicguide/") < 0
                && path.indexOf("assets/bloodarsenalguide/") < 0
                && path.indexOf("assets/bloodmagic/books/") < 0) {
            return false;
        }
        try {
            Class<?> guideApi = Class.forName("amerifrance.guideapi.api.GuideAPI");
            Object booksValue = guideApi.getMethod("getBooks").invoke(null);
            if (!(booksValue instanceof Map)) {
                return false;
            }
            Object selected = null;
            for (Object value : ((Map<?, ?>) booksValue).values()) {
                if (value == null) {
                    continue;
                }
                Object resource = value.getClass().getMethod("getRegistryName").invoke(value);
                String id = resource == null ? "" : resource.toString().toLowerCase();
                if (selected == null) {
                    selected = value;
                }
                if (!id.isEmpty() && path.contains(id)) {
                    selected = value;
                    break;
                }
            }
            if (selected == null) {
                return false;
            }
            Class<?> bookClass = Class.forName("amerifrance.guideapi.api.impl.Book");
            Class<?> guiHome = Class.forName("amerifrance.guideapi.gui.GuiHome");
            Object stack = guideApi.getMethod("getStackFromBook", bookClass)
                    .invoke(null, selected);
            Constructor<?> constructor = guiHome.getConstructor(bookClass,
                    Class.forName("net.minecraft.entity.player.EntityPlayer"),
                    Class.forName("net.minecraft.item.ItemStack"));
            remember(assistant);
            GuiScreen screen = (GuiScreen) constructor.newInstance(selected,
                    Minecraft.getMinecraft().player, stack);
            Minecraft.getMinecraft().displayGuiScreen(screen);
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    private static boolean openMantle(GuiScreen assistant, LegacySourceReference source) {
        String path = normalizedPath(source).replace("!", "");
        MantleBookDescriptor descriptor = mantleBookDescriptor(path);
        if (descriptor == null) {
            return false;
        }
        String pageId = mantlePageId(path);
        try {
            Class<?> bookData = Class.forName("slimeknights.mantle.client.book.data.BookData");
            Object book = Class.forName(descriptor.bookClassName).getField("INSTANCE").get(null);
            if (book == null) {
                return false;
            }
            // BookData 的静态实例在客户端资源重载前可能尚未 load。直接先调用
            // findPageNumber 会访问空的 sections，导致来源按钮悄悄回退为不可用。
            // Mantle 自己的 openGui 也会执行同样的惰性加载，这里在定位页面前复用
            // 该生命周期。
            bookData.getMethod("load").invoke(book);
            int page = -1;
            if (isMantlePageId(pageId)) {
                page = ((Integer) bookData.getMethod("findPageNumber", String.class)
                        .invoke(book, pageId)).intValue();
            }
            Item item = Item.getByNameOrId(descriptor.itemId);
            if (item == null) {
                return false;
            }
            Class<?> guiBook = Class.forName("slimeknights.mantle.client.gui.book.GuiBook");
            Constructor<?> constructor = guiBook.getConstructor(bookData, ItemStack.class);
            GuiScreen screen = (GuiScreen) constructor.newInstance(book, new ItemStack(item));
            remember(assistant);
            Minecraft.getMinecraft().displayGuiScreen(screen);
            if (page >= 0) {
                guiBook.getMethod("openPage", int.class).invoke(screen, page);
            }
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    private static boolean openForestry(GuiScreen assistant, LegacySourceReference source) {
        String path = normalizedPath(source).replace("!", "");
        if (!isForestryPath(path)) {
            return false;
        }
        try {
            Class<?> bookManager = Class.forName("forestry.api.book.BookManager");
            Object loader = bookManager.getField("loader").get(null);
            if (loader == null) {
                return false;
            }
            Object book = loader.getClass().getMethod("loadBook").invoke(loader);
            if (book == null) {
                return false;
            }

            String page = forestryPage(path);
            GuiScreen screen;
            if (!page.isEmpty()) {
                String[] parts = page.split("/", 2);
                Object category = book.getClass().getMethod("getCategory", String.class)
                        .invoke(book, parts[0]);
                Object entry = category == null ? null
                        : category.getClass().getMethod("getEntry", String.class)
                        .invoke(category, parts[1]);
                if (category != null && entry != null) {
                    Object parent = entry.getClass().getMethod("getParent").invoke(entry);
                    Class<?> pages = Class.forName("forestry.book.gui.GuiForestryBookPages");
                    screen = (GuiScreen) pages.getConstructor(
                            Class.forName("forestry.api.book.IForesterBook"),
                            Class.forName("forestry.api.book.IBookCategory"),
                            Class.forName("forestry.api.book.IBookEntry"),
                            Class.forName("forestry.api.book.IBookEntry"))
                            .newInstance(book, category, entry, parent);
                } else {
                    screen = forestryHome(book);
                }
            } else {
                screen = forestryHome(book);
            }
            if (screen == null) {
                return false;
            }
            remember(assistant);
            Minecraft.getMinecraft().displayGuiScreen(screen);
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    private static GuiScreen forestryHome(Object book) throws Exception {
        Class<?> categories = Class.forName("forestry.book.gui.GuiForestryBookCategories");
        return (GuiScreen) categories.getConstructor(
                Class.forName("forestry.api.book.IForesterBook")).newInstance(book);
    }

    private static boolean openThaumcraftResearch(GuiScreen assistant,
            LegacySourceReference source) {
        String path = normalizedPath(source).replace("!", "");
        if (!isResearchPath(path)) {
            return false;
        }
        try {
            Class<?> browser = Class.forName("thaumcraft.client.gui.GuiResearchBrowser");
            GuiScreen screen = (GuiScreen) browser.getConstructor().newInstance();
            remember(assistant);
            Minecraft.getMinecraft().displayGuiScreen(screen);
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    private static boolean openLogisticsPipes(GuiScreen assistant,
            LegacySourceReference source) {
        String path = normalizedPath(source).replace("!", "");
        if (!isLogisticsPipesPath(path)) {
            return false;
        }
        try {
            String pagePath = logisticsPipesPage(path);
            Class<?> pageData = Class.forName(
                    "network.rs485.logisticspipes.gui.guidebook.PageData");
            Object data = pageData.getConstructor(String.class).newInstance(pagePath);
            Class<?> page = Class.forName("network.rs485.logisticspipes.gui.guidebook.Page");
            Object currentPage = page.getConstructor(pageData).newInstance(data);
            Class<?> state = Class.forName(
                    "network.rs485.logisticspipes.guidebook.ItemGuideBook$GuideBookState");
            Object bookState = state.getConstructor(EntityEquipmentSlot.class, page, List.class)
                    .newInstance(EntityEquipmentSlot.MAINHAND, currentPage,
                            Collections.emptyList());
            Class<?> gui = Class.forName(
                    "network.rs485.logisticspipes.gui.guidebook.GuiGuideBook");
            GuiScreen screen = (GuiScreen) gui.getConstructor(state).newInstance(bookState);
            remember(assistant);
            Minecraft.getMinecraft().displayGuiScreen(screen);
            return true;
        } catch (Throwable ignored) {
            clearPending();
            return false;
        }
    }

    private static boolean isMantlePageId(String pageId) {
        if (pageId == null) {
            return false;
        }
        int dot = pageId.indexOf('.');
        return dot > 0 && dot < pageId.length() - 1;
    }

    private static boolean isGuideApiPath(String path) {
        return path.indexOf("guideapi") >= 0
                || path.indexOf("guide_api") >= 0
                || path.indexOf("assets/chisel_guide/") >= 0
                || path.indexOf("assets/bloodmagicguide/") >= 0
                || path.indexOf("assets/bloodarsenalguide/") >= 0
                || path.indexOf("assets/bloodmagic/books/") >= 0;
    }

    private static boolean isFtbGuidesPath(String path) {
        return path.indexOf("ftbguides/") >= 0 || path.indexOf("ftb_guides/") >= 0;
    }

    private static boolean isMantleBookPath(String path) {
        return mantleBookDescriptor(path) != null;
    }

    private static MantleBookDescriptor mantleBookDescriptor(String path) {
        if (containsAny(path, "assets/conarm/book/", "conarm:book:")) {
            return new MantleBookDescriptor(
                    "c4.conarm.lib.book.ArmoryBook", "conarm:book");
        }
        if (containsAny(path, "assets/enderio/eiobook/", "enderio:eiobook:")) {
            return new MantleBookDescriptor(
                    "crazypants.enderio.integration.tic.book.EioBook",
                    "enderio:item_eio_book");
        }
        if (containsAny(path,
                "assets/tconstruct/book/", "assets/tcomplement/book/",
                "assets/taiga/book/", "assets/tconevo/book/",
                "assets/toolprogression/book/", "assets/enderio/book/",
                "tconstruct:book:", "tcomplement:book:", "taiga:book:",
                "tconevo:book:", "toolprogression:book:", "enderio:book:")) {
            return new MantleBookDescriptor(
                    "slimeknights.tconstruct.library.book.TinkerBook",
                    "tconstruct:book");
        }
        return null;
    }

    private static boolean isForestryPath(String path) {
        return path.indexOf("assets/forestry/manual/") >= 0
                || path.indexOf("forestry:manual:") >= 0;
    }

    private static String forestryPage(String path) {
        String rest = afterMarker(path, "assets/forestry/manual/");
        if (rest.isEmpty()) {
            rest = afterMarker(path, "forestry:manual:");
        }
        if (rest.isEmpty()) {
            return "";
        }
        String[] parts = rest.split("/");
        if (parts.length > 0 && parts[0].matches("[a-z]{2}_[a-z]{2}")) {
            rest = rest.substring(parts[0].length() + 1);
            parts = rest.split("/");
        }
        if (parts.length != 2 || !parts[1].endsWith(".json")) {
            return "";
        }
        return parts[0] + "/" + parts[1].substring(0, parts[1].length() - 5);
    }

    private static boolean isResearchPath(String path) {
        return path.indexOf("/research/") >= 0 && path.endsWith(".json");
    }

    private static boolean isLogisticsPipesPath(String path) {
        return (path.indexOf("assets/logisticspipes/book/") >= 0
                || path.indexOf("logisticspipes:book:") >= 0)
                && (path.endsWith(".md") || path.endsWith(".markdown"));
    }

    private static String logisticsPipesPage(String path) {
        String rest = afterMarker(path, "assets/logisticspipes/book/");
        if (rest.isEmpty()) {
            rest = afterMarker(path, "logisticspipes:book:");
        }
        if (rest.isEmpty()) {
            return "/main_menu.md";
        }
        String[] parts = rest.split("/");
        if (parts.length > 0 && parts[0].matches("[a-z]{2}_[a-z]{2}")) {
            rest = rest.substring(parts[0].length() + 1);
        }
        return rest.startsWith("/") ? rest : "/" + rest;
    }

    private static String afterMarker(String path, String marker) {
        int index = path.indexOf(marker);
        return index < 0 ? "" : path.substring(index + marker.length());
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.indexOf(candidate) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void remember(GuiScreen assistant) {
        returnScreen = assistant;
        waitingForReturn = true;
        if (assistant instanceof LegacyAssistantScreen) {
            ((LegacyAssistantScreen) assistant).beginManualNavigation();
        }
    }

    private static void clearPending() {
        if (returnScreen instanceof LegacyAssistantScreen) {
            ((LegacyAssistantScreen) returnScreen).cancelManualNavigation();
        }
        returnScreen = null;
        waitingForReturn = false;
    }

    private static String normalizedPath(LegacySourceReference source) {
        return sourcePath(source);
    }

    private static String sourcePath(LegacySourceReference source) {
        String path = source == null ? "" : source.getSourcePath();
        if (path == null || path.trim().isEmpty()) {
            path = source == null ? "" : source.getDocumentId();
        }
        path = path.replace('\\', '/').toLowerCase();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private static String patchouliBookId(LegacySourceReference source) {
        String path = sourcePath(source);
        String marker = "patchouli_books/";
        int index = path.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String before = path.substring(0, index);
        int assets = before.lastIndexOf("assets/");
        int data = before.lastIndexOf("data/");
        int root = Math.max(assets, data);
        String namespace;
        if (root >= 0) {
            int offset = root == assets ? root + "assets/".length()
                    : root + "data/".length();
            namespace = between(before, offset, before.length());
        } else {
            int colon = before.lastIndexOf(':');
            namespace = colon < 0 ? "" : before.substring(colon + 1);
        }
        String rest = path.substring(index + marker.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return "";
        }
        // 1.12.2 Patchouli 允许整合包把书籍放在实例根目录的
        // patchouli_books/ 下。这类资源实际由 patchouli 命名空间注册，
        // source_path 不包含 assets/<namespace>/，因此不能把空命名空间传给
        // ResourceLocation。
        if (namespace.isEmpty() && (index == 0 || "assets/".equals(before)
                || "data/".equals(before))) {
            namespace = "patchouli";
        }
        if (namespace.isEmpty()) {
            return "";
        }
        String book = rest.substring(0, slash).replace("!", "");
        return namespace + ":" + book;
    }

    private static String patchouliEntryPath(LegacySourceReference source) {
        String path = sourcePath(source);
        int marker = path.indexOf("/entries/");
        if (marker < 0) {
            return "";
        }
        String entry = path.substring(marker + "/entries/".length());
        if (entry.endsWith(".json")) {
            entry = entry.substring(0, entry.length() - ".json".length());
        }
        return entry;
    }

    private static String guidePage(String path) {
        int marker = path.indexOf("ftbguides/");
        String page = marker >= 0 ? path.substring(marker + "ftbguides/".length()) : path;
        page = page.replaceFirst("^assets/", "");
        if (page.endsWith(".json")) {
            page = page.substring(0, page.length() - 5);
        }
        if (page.endsWith("/index")) {
            page = page.substring(0, page.length() - 6);
        }
        return page;
    }

    private static String mantlePageId(String path) {
        String rest = "";
        String[] pathPrefixes = {
                "assets/tconstruct/book/", "assets/conarm/book/",
                "assets/tcomplement/book/", "assets/taiga/book/",
                "assets/tconevo/book/", "assets/toolprogression/book/",
                "assets/enderio/book/", "assets/enderio/eiobook/"
        };
        for (String prefix : pathPrefixes) {
            rest = afterMarker(path, prefix);
            if (!rest.isEmpty()) {
                break;
            }
        }
        if (rest.isEmpty()) {
            String[] resourcePrefixes = {
                    "tconstruct:book:", "conarm:book:", "tcomplement:book:",
                    "taiga:book:", "tconevo:book:", "toolprogression:book:",
                    "enderio:book:", "enderio:eiobook:"
            };
            for (String prefix : resourcePrefixes) {
                rest = afterMarker(path, prefix);
                if (!rest.isEmpty()) {
                    break;
                }
            }
        }
        if (rest.isEmpty()) {
            return "";
        }
        String[] parts = rest.split("/");
        if (parts.length > 0 && parts[0].matches("[a-z]{2}_[a-z]{2}")) {
            rest = rest.substring(parts[0].length() + 1);
        }
        if (rest.endsWith(".json")) {
            rest = rest.substring(0, rest.length() - ".json".length());
        }
        return rest.replace('/', '.');
    }

    private static final class MantleBookDescriptor {
        private final String bookClassName;
        private final String itemId;

        private MantleBookDescriptor(String bookClassName, String itemId) {
            this.bookClassName = bookClassName;
            this.itemId = itemId;
        }
    }

    private static String between(String value, int start, int end) {
        if (start < 0 || start >= end || start >= value.length()) {
            return "";
        }
        String result = value.substring(start, Math.min(end, value.length()));
        int slash = result.indexOf('/');
        return slash < 0 ? result : result.substring(0, slash);
    }
}

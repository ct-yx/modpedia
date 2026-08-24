package io.ctyx.modpedia.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.12.2 助手界面。
 *
 * <p>历史、设置和回答都在同一个 Screen 内切换，避免旧版 GUI 嵌套时出现层级
 * 穿透。设置和会话数据通过本地 Worker IPC 读取，API Key 只在内存字段中短暂
 * 使用；失去 Worker 时仍保留原来的本地 Markdown 搜索回退。</p>
 */
public final class LegacyAssistantScreen extends GuiScreen {
    private static final Pattern ITEM_TOKEN = Pattern.compile(
            "\\[\\[item:([^\\]|]+)(?:\\|([^\\]|]*))?(?:\\|meta=[^\\]]+)?\\]\\]"
    );
    private static final Pattern RECIPE_TOKEN = Pattern.compile(
            "\\[\\[recipe:([^\\]|]+)(?:\\|([^\\]|]*))?\\]\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_RECIPE_FIELD = Pattern.compile(
            "(?i)(?:\\*\\*(?:recipe(?:2)?|制作|配方|合成)\\*\\*|(?:recipe(?:2)?|制作|配方|合成))"
                    + "\\s*[：:]?\\s*([^\\s`|\\]\\)]+)");
    private static final int SEND = 1;
    private static final int INSERT = 2;
    private static final int CLOSE = 3;
    private static final int HISTORY = 4;
    private static final int SETTINGS = 5;
    private static final int CANCEL = 6;
    private static final int BACK = 7;
    private static final int SETTINGS_SAVE = 20;
    private static final int SETTINGS_FETCH_MODELS = 21;
    private static final int SETTINGS_TEST_CONNECTION = 22;
    private static final int SETTINGS_MODE_AI = 23;
    private static final int SETTINGS_MODE_SEARCH = 24;
    private static final int SETTINGS_STREAMING = 25;
    private static final int SETTINGS_FORMAT = 26;
    private static final int SETTINGS_INTENSITY = 27;
    private static final int HISTORY_NEW = 30;
    private static final int HISTORY_CLEAR = 31;
    private static final int HISTORY_RENAME = 32;
    private static final int HISTORY_RENAME_SAVE = 33;
    private static final int HISTORY_RENAME_CANCEL = 34;
    private static final int MODEL_BASE = 100;
    private static final int HISTORY_SELECT_BASE = 200;
    private static final int HISTORY_DELETE_BASE = 300;
    private static final int SETTINGS_MODE_BUTTON = 14;
    private static final int SETTINGS_FIELD_START = 48;
    private static final int SETTINGS_PROTOCOL = 150;
    private static final int SETTINGS_CONTROLS = 172;
    private static final int SETTINGS_LIST_LABEL = 224;
    private static final int SETTINGS_LIST_ROW = 242;

    private final GuiScreen previousScreen;
    private final LegacyTargetStore.Target frozenTarget;
    private final List<TokenHit> tokenHits = new ArrayList<TokenHit>();
    private final List<RecipeHit> recipeHits = new ArrayList<RecipeHit>();
    private final List<SourceHit> sourceHits = new ArrayList<SourceHit>();
    private final List<FollowUpHit> followUpHits = new ArrayList<FollowUpHit>();
    private final List<SuggestionHit> suggestionHits = new ArrayList<SuggestionHit>();
    private final List<ModelOption> models = new ArrayList<ModelOption>();
    private final List<ConversationSummary> conversations = new ArrayList<ConversationSummary>();
    private final List<LegacyChatMessage> messages = new ArrayList<LegacyChatMessage>();
    /** 已经在本次客户端会话中读取过的对话，切换时先用它立即更新 UI。 */
    private final Map<String, List<LegacyChatMessage>> conversationMessageCache =
            new HashMap<String, List<LegacyChatMessage>>();
    private final Set<String> finishedRequestIds = new LinkedHashSet<String>();
    private final LegacyScrollModel answerScroll = new LegacyScrollModel();
    private final LegacyScrollModel settingsScroll = new LegacyScrollModel();
    private final LegacyScrollModel historyScroll = new LegacyScrollModel();

    private Panel panel = Panel.MAIN;
    private GuiTextField input;
    private GuiTextField endpointField;
    private GuiTextField modelField;
    private GuiTextField apiKeyField;
    private String assistantDraft = "";
    private String errorMessage = "";
    private String status = "就绪";
    private String lastPrompt = "";
    private String activeConversationId = "";
    private String activeConversationTitle = "";
    private boolean renamingConversation;
    private GuiTextField renameField;
    private String activeRequestId;
    private boolean busy;
    private boolean settingsBusy;
    private boolean settingsLoaded;
    private boolean settingsDirty;
    private boolean retryAvailable;
    private boolean onlySearch;
    private boolean streaming = true;
    private String apiFormat = "CHAT_COMPLETIONS";
    private String intensity = "STANDARD";
    private boolean closed;
    private boolean manualNavigationPending;
    private boolean scrollToEnd;
    private boolean draggingScrollbar;
    private long conversationSelectionToken;
    private int cachedMessageHash;
    private int cachedMessageWidth = -1;
    private boolean cachedMessageIds;
    private List<MessageLayout> cachedMessageLayouts = new ArrayList<MessageLayout>();
    private int layoutWidth = -1;
    private int layoutHeight = -1;

    public LegacyAssistantScreen(GuiScreen previousScreen, LegacyTargetStore.Target frozenTarget) {
        this.previousScreen = previousScreen;
        this.frozenTarget = frozenTarget;
    }

    @Override
    public void initGui() {
        int panelWidth = panelWidth();
        int left = panelLeft(panelWidth);
        LegacyPanelLayout.Layout layout = currentLayout();
        LegacyPanelLayout.Rect footer = layout.footer();
        input = new GuiTextField(10, fontRenderer, left + 12, footer.y() + 28,
                Math.max(80, panelWidth - 150), 20);
        input.setMaxStringLength(4000);
        input.setFocused(true);

        endpointField = new GuiTextField(11, fontRenderer, left + 12, 0, panelWidth - 24, 20);
        modelField = new GuiTextField(12, fontRenderer, left + 12, 0, panelWidth - 24, 20);
        apiKeyField = new GuiTextField(13, fontRenderer, left + 12, 0, panelWidth - 24, 20);
        renameField = new GuiTextField(14, fontRenderer, left + 12, 0,
                Math.max(80, panelWidth - 150), 20);
        endpointField.setMaxStringLength(500);
        modelField.setMaxStringLength(200);
        apiKeyField.setMaxStringLength(500);
        renameField.setMaxStringLength(120);
        endpointField.setCanLoseFocus(true);
        modelField.setCanLoseFocus(true);
        apiKeyField.setCanLoseFocus(true);
        renameField.setCanLoseFocus(true);
        rebuildFields();
        rebuildButtons();

        if (LegacyWorkerBridge.get().isReady()) {
            loadSettings();
            loadConversations(true);
        } else {
            status = "Worker 未就绪：可使用本地搜索";
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        refreshLayoutIfNeeded();
        LegacyPanelLayout.Layout layout = currentLayout();
        LegacyPanelLayout.Rect window = layout.window();
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        int panelWidth = panelWidth();
        int left = window.x();
        int top = window.y();
        drawRect(window.x(), window.y(), window.right(), window.bottom(), 0xF0181820);
        drawRect(layout.header().x(), layout.header().y(), layout.header().right(),
                layout.header().bottom(), 0xFF252A36);
        drawString(fontRenderer, panel == Panel.MAIN ? "ModPedia · 1.12.2"
                : panel == Panel.SETTINGS ? "助手设置" : "历史会话", left + 12, top + 9, 0xFFFFFF);
        drawString(fontRenderer, status, left + 12, layout.header().bottom() + 6, 0xAAB7C4);

        beginScissor(viewport);
        if (panel == Panel.MAIN) {
            drawMain(left, panelWidth, layout);
        } else if (panel == Panel.SETTINGS) {
            drawSettings(left, panelWidth, layout);
        } else {
            drawHistory(left, layout);
        }
        endScissor();
        drawScrollbar(viewport, currentScroll());
        drawFixedFooter(left, panelWidth, layout);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == CLOSE) {
            if (closeExitsAssistant(panel)) {
                close();
            } else {
                returnToMainPanel();
            }
        } else if (button.id == HISTORY && panel == Panel.MAIN) {
            panel = Panel.HISTORY;
            input.setFocused(false);
            rebuildFields();
            rebuildButtons();
            loadConversations(false);
        } else if (button.id == SETTINGS && panel == Panel.MAIN) {
            panel = Panel.SETTINGS;
            input.setFocused(false);
            rebuildFields();
            rebuildButtons();
            if (!settingsLoaded) {
                loadSettings();
            }
        } else if (button.id == BACK) {
            returnToMainPanel();
        } else if (button.id == SEND) {
            send();
        } else if (button.id == CANCEL) {
            cancelRequest();
        } else if (button.id == INSERT) {
            insertTarget();
        } else if (button.id == SETTINGS_SAVE) {
            saveSettings();
        } else if (button.id == SETTINGS_FETCH_MODELS) {
            fetchModels();
        } else if (button.id == SETTINGS_TEST_CONNECTION) {
            testConnection();
        } else if (button.id == SETTINGS_MODE_AI) {
            onlySearch = false;
            settingsDirty = true;
            status = "模式已修改，请点击保存";
            rebuildButtons();
            rebuildFields();
        } else if (button.id == SETTINGS_MODE_SEARCH) {
            onlySearch = true;
            settingsDirty = true;
            status = "模式已修改，请点击保存";
            rebuildButtons();
            rebuildFields();
        } else if (button.id == SETTINGS_STREAMING) {
            streaming = !streaming;
            settingsDirty = true;
            rebuildButtons();
        } else if (button.id == SETTINGS_FORMAT) {
            apiFormat = next(apiFormat, new String[]{
                    "CHAT_COMPLETIONS", "NATIVE_MESSAGES", "RESPONSES", "GENERATE_CONTENT"
            });
            settingsDirty = true;
            rebuildButtons();
        } else if (button.id == SETTINGS_INTENSITY) {
            intensity = next(intensity, new String[]{"FAST", "STANDARD", "DEEP", "CUSTOM"});
            settingsDirty = true;
            rebuildButtons();
        } else if (button.id >= MODEL_BASE && button.id < MODEL_BASE + models.size()) {
            modelField.setText(models.get(button.id - MODEL_BASE).id);
            modelField.setCursorPositionEnd();
        } else if (button.id == HISTORY_NEW) {
            newConversation();
        } else if (button.id == HISTORY_CLEAR) {
            clearConversation();
        } else if (button.id == HISTORY_RENAME) {
            beginRename();
        } else if (button.id == HISTORY_RENAME_SAVE) {
            saveRename();
        } else if (button.id == HISTORY_RENAME_CANCEL) {
            cancelRename();
        } else if (button.id >= HISTORY_SELECT_BASE
                && button.id < HISTORY_SELECT_BASE + conversations.size()) {
            selectConversation(conversations.get(button.id - HISTORY_SELECT_BASE).id);
        } else if (button.id >= HISTORY_DELETE_BASE
                && button.id < HISTORY_DELETE_BASE + conversations.size()) {
            deleteConversation(conversations.get(button.id - HISTORY_DELETE_BASE).id);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (panel == Panel.HISTORY && renamingConversation) {
                cancelRename();
                return;
            }
            if (panel != Panel.MAIN) {
                returnToMainPanel();
            } else {
                close();
            }
            return;
        }
        if (panel == Panel.MAIN) {
            if (keyCode == Keyboard.KEY_RETURN && !busy) {
                send();
                return;
            }
            if (input != null && input.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        } else if (panel == Panel.SETTINGS) {
            if (keyCode == Keyboard.KEY_RETURN) {
                saveSettings();
                return;
            }
            if (endpointField.textboxKeyTyped(typedChar, keyCode)
                    || modelField.textboxKeyTyped(typedChar, keyCode)
                    || apiKeyField.textboxKeyTyped(typedChar, keyCode)) {
                settingsDirty = true;
                return;
            }
        } else if (panel == Panel.HISTORY && renamingConversation && renameField != null) {
            if (keyCode == Keyboard.KEY_RETURN) {
                saveRename();
                return;
            }
            if (renameField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 0 && beginScrollbarDrag(mouseX, mouseY)) {
            return;
        }
        if (panel == Panel.MAIN && mouseButton == 0) {
            for (RecipeHit hit : recipeHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if (LegacyJeiBridge.showRecipe(hit.recipeValue)) {
                        return;
                    }
                    status = LegacyJeiBridge.isAvailable()
                            ? "JEI/HEI 尚未初始化或没有该配方"
                            : "未检测到 JEI/HEI，无法打开配方";
                    return;
                }
            }
            for (TokenHit hit : tokenHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if (LegacyJeiBridge.showItem(hit.itemValue)) {
                        return;
                    }
                    status = LegacyJeiBridge.isAvailable()
                            ? "JEI/HEI 尚未初始化或没有该物品的配方"
                            : "未检测到 JEI/HEI，无法打开配方";
                    return;
                }
            }
        }
        if (panel == Panel.MAIN && mouseButton == 0) {
            for (SourceHit hit : sourceHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if (LegacyManualNavigator.open(this, hit.source)) {
                        return;
                    }
                    status = "未找到可打开的手册页面";
                    return;
                }
            }
            for (FollowUpHit hit : followUpHits) {
                if (hit.contains(mouseX, mouseY)) {
                    input.setText(hit.question);
                    input.setCursorPositionEnd();
                    input.setFocused(true);
                    status = "已填入追问";
                    return;
                }
            }
            for (SuggestionHit hit : suggestionHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if ("如何开始使用 ModPedia？".equals(hit.query)) {
                        openBuiltInGuide();
                        return;
                    }
                    input.setText(hit.query);
                    input.setCursorPositionEnd();
                    input.setFocused(true);
                    status = "已填入建议问题";
                    return;
                }
            }
        }
        if (panel == Panel.HISTORY && mouseButton == 0) {
            LegacyPanelLayout.Rect viewport = currentLayout().contentViewport();
            int left = currentLayout().window().x();
            int panelWidth = currentLayout().window().width();
            int row = viewport.y() - historyScroll.offset() + 8;
            int index = (mouseY - row) / 24;
            if (index >= 0 && index < conversations.size()
                    && mouseY >= row + index * 24
                    && mouseY < row + index * 24 + 20
                    && mouseX >= left + 10
                    && mouseX < left + panelWidth - 128) {
                selectConversation(conversations.get(index).id);
                return;
            }
        }
        if (panel == Panel.MAIN && input != null) {
            input.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (panel == Panel.SETTINGS) {
            endpointField.mouseClicked(mouseX, mouseY, mouseButton);
            modelField.mouseClicked(mouseX, mouseY, mouseButton);
            apiKeyField.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (panel == Panel.HISTORY && renamingConversation && renameField != null) {
            renameField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton,
            long timeSinceLastClick) {
        if (draggingScrollbar && clickedMouseButton == 0) {
            LegacyPanelLayout.Rect viewport = currentLayout().contentViewport();
            currentScroll().dragTo(mouseY - viewport.y(), viewport.height());
            rebuildFields();
            rebuildButtons();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        draggingScrollbar = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int displayWidth = Minecraft.getMinecraft().displayWidth;
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        if (displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / displayWidth;
        int mouseY = height - Mouse.getEventY() * height / displayHeight - 1;
        LegacyPanelLayout.Rect viewport = currentLayout().contentViewport();
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }
        if (panel == Panel.MAIN) {
            answerScroll.scroll(wheel, 18);
            scrollToEnd = false;
        } else if (panel == Panel.SETTINGS) {
            settingsScroll.scroll(wheel, 18);
        } else {
            historyScroll.scroll(wheel, 24);
        }
        rebuildFields();
        rebuildButtons();
    }

    private LegacyScrollModel currentScroll() {
        if (panel == Panel.SETTINGS) {
            return settingsScroll;
        }
        if (panel == Panel.HISTORY) {
            return historyScroll;
        }
        return answerScroll;
    }

    private boolean beginScrollbarDrag(int mouseX, int mouseY) {
        LegacyPanelLayout.Rect viewport = currentLayout().contentViewport();
        LegacyScrollModel scroll = currentScroll();
        if (!scroll.canScroll() || mouseX < viewport.right() - 7
                || mouseX >= viewport.right() || mouseY < viewport.y()
                || mouseY >= viewport.bottom()) {
            return false;
        }
        draggingScrollbar = true;
        scroll.dragTo(mouseY - viewport.y(), viewport.height());
        rebuildFields();
        rebuildButtons();
        return true;
    }

    private void drawScrollbar(LegacyPanelLayout.Rect viewport, LegacyScrollModel scroll) {
        if (scroll == null || !scroll.canScroll() || viewport.height() <= 0) {
            return;
        }
        int trackX = viewport.right() - 5;
        int trackTop = viewport.y();
        int trackBottom = viewport.bottom();
        int thumb = scroll.thumbSize(viewport.height());
        int thumbTop = trackTop + scroll.thumbOffset(viewport.height());
        drawRect(trackX, trackTop, trackX + 2, trackBottom, 0x553F4A59);
        drawRect(trackX, thumbTop, trackX + 2, thumbTop + thumb, 0xFF86A8C7);
    }

    @Override
    public void onGuiClosed() {
        if (manualNavigationPending) {
            // 打开可选手册会先触发当前 Screen 的 onGuiClosed；这只是临时让位，
            // 不能取消请求、释放冻结目标或把同一个助手实例标记为永久关闭。
            super.onGuiClosed();
            return;
        }
        closed = true;
        if (activeRequestId != null) {
            LegacyWorkerBridge.get().cancelChat(activeRequestId);
        }
        LegacyTargetStore.get().release();
        super.onGuiClosed();
    }

    void beginManualNavigation() {
        manualNavigationPending = true;
    }

    void cancelManualNavigation() {
        manualNavigationPending = false;
    }

    void resumeAfterManualNavigation() {
        manualNavigationPending = false;
        closed = false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawMain(int left, int panelWidth, LegacyPanelLayout.Layout layout) {
        tokenHits.clear();
        recipeHits.clear();
        sourceHits.clear();
        followUpHits.clear();
        suggestionHits.clear();
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        int textWidth = Math.max(40, panelWidth - 32);
        List<MessageLayout> layouts = messageLayouts(textWidth);
        int contentHeight = 10;
        for (MessageLayout message : layouts) {
            contentHeight += message.height + 8;
        }
        if (!errorMessage.isEmpty()) {
            contentHeight += 18;
        }
        answerScroll.configure(contentHeight, viewport.height());
        if (scrollToEnd) {
            answerScroll.setOffset(answerScroll.maxOffset());
            scrollToEnd = false;
        }
        int y = viewport.y() + 4 - answerScroll.offset();
        if (layouts.isEmpty()) {
            drawString(fontRenderer, "输入问题后按 Enter，或点击发送。", left + 12, y + 18, 0xFFB9C5D0);
            drawString(fontRenderer, "Worker 未就绪时会回退到本地 Markdown 搜索。",
                    left + 12, y + 36, 0xFF8D99A6);
            drawWelcomeSuggestions(left, y + 58, panelWidth, viewport);
        }
        for (MessageLayout message : layouts) {
            drawMessageLayout(message, left, y, viewport);
            y += message.height + 8;
        }
        if (!errorMessage.isEmpty() && y + 18 >= viewport.y() && y <= viewport.bottom()) {
            drawString(fontRenderer, "§c" + errorMessage, left + 12, y, 0xFFFFB0A0);
        }
    }

    private List<MessageLayout> messageLayouts(int availableWidth) {
        int hash = 17;
        hash = hash * 31 + availableWidth;
        hash = hash * 31 + (showIds() ? 1 : 0);
        hash = hash * 31 + (assistantDraft == null ? 0 : assistantDraft.hashCode());
        for (LegacyChatMessage message : messages) {
            hash = hash * 31 + message.getRole().hashCode();
            hash = hash * 31 + message.getMarkdown().hashCode();
            hash = hash * 31 + message.getSources().hashCode();
            hash = hash * 31 + message.getFollowUpQuestions().hashCode();
        }
        if (hash == cachedMessageHash && availableWidth == cachedMessageWidth
                && cachedMessageIds == showIds()) {
            return cachedMessageLayouts;
        }
        cachedMessageHash = hash;
        cachedMessageWidth = availableWidth;
        cachedMessageIds = showIds();
        cachedMessageLayouts = buildMessageLayouts(availableWidth);
        return cachedMessageLayouts;
    }

    private List<MessageLayout> buildMessageLayouts(int availableWidth) {
        List<MessageLayout> result = new ArrayList<MessageLayout>();
        for (LegacyChatMessage message : messages) {
            result.add(buildMessageLayout(message, availableWidth));
        }
        if (!assistantDraft.isEmpty()) {
            result.add(buildMessageLayout(new LegacyChatMessage(
                    LegacyChatMessage.Role.ASSISTANT, assistantDraft), availableWidth));
        }
        return result;
    }

    private MessageLayout buildMessageLayout(LegacyChatMessage message, int availableWidth) {
        List<LegacyMarkdownRenderer.RenderedLine> rendered = LegacyMarkdownRenderer.layout(
                message.getMarkdown(), message.getSources(), showIds());
        List<WrappedLine> lines = new ArrayList<WrappedLine>();
        int naturalWidth = 0;
        for (LegacyMarkdownRenderer.RenderedLine line : rendered) {
            naturalWidth = Math.max(naturalWidth, fontRenderer.getStringWidth(line.getFormattedText()));
        }
        int bubbleWidth;
        if (message.getRole() == LegacyChatMessage.Role.USER) {
            bubbleWidth = Math.min(availableWidth, Math.max(150, naturalWidth + 24));
        } else {
            // 助手正文不占满整个视口。旧版 FontRenderer 在高 GUI Scale、Unicode
            // 字体或 Cleanroom 的字体替换下，实际字面宽度可能比布局测量更大；
            // 保留约 22% 的右侧余量可以避免长段落贴边或被裁切，同时仍让正文
            // 使用主要空间。
            bubbleWidth = Math.min(availableWidth,
                    Math.max(160, (int) Math.floor(availableWidth * 0.78D)));
        }
        int lineWidth = Math.max(40, bubbleWidth - 20);
        int height = 12;
        for (LegacyMarkdownRenderer.RenderedLine line : rendered) {
            List<String> wrapped = fontRenderer.listFormattedStringToWidth(
                    line.getFormattedText(), lineWidth);
            if (wrapped.isEmpty()) {
                wrapped = new ArrayList<String>();
                wrapped.add(" ");
            }
            for (String value : wrapped) {
                lines.add(new WrappedLine(value, line));
                height += 10;
            }
            if (!line.getAnnotations().isEmpty()) {
                height += line.getAnnotations().size() * 18;
            }
        }
        if (message.getRole() == LegacyChatMessage.Role.ASSISTANT
                && !message.getFollowUpQuestions().isEmpty()) {
            height += 14 + message.getFollowUpQuestions().size() * 22;
        }
        return new MessageLayout(message, bubbleWidth, height, lines);
    }

    private void drawMessageLayout(MessageLayout message, int left, int y,
            LegacyPanelLayout.Rect viewport) {
        int bubbleX = message.message.getRole() == LegacyChatMessage.Role.USER
                ? left + panelWidth() - message.width - 12 : left + 12;
        if (y + message.height < viewport.y() || y > viewport.bottom()) {
            return;
        }
        drawRect(bubbleX, y, bubbleX + message.width, y + message.height,
                message.message.getRole() == LegacyChatMessage.Role.USER
                        ? 0xCC26364A : 0xCC1D222C);
        drawRect(bubbleX, y, bubbleX + message.width, y + 1,
                message.message.getRole() == LegacyChatMessage.Role.USER
                        ? 0xFF5C88B8 : 0xFF46515F);
        int lineY = y + 6;
        for (int index = 0; index < message.lines.size(); index++) {
            WrappedLine line = message.lines.get(index);
            if (lineY + 10 >= viewport.y() && lineY <= viewport.bottom()) {
                drawString(fontRenderer, line.formattedText, bubbleX + 10, lineY, 0xFFE8E8E8);
                collectTokenHits(line.source.getSource().getText(), line.formattedText,
                        bubbleX + 10, lineY, showIds());
                collectRecipeHits(line.source.getSource().getText(), line.formattedText,
                        bubbleX + 10, lineY);
            }
            lineY += 10;
            boolean lastWrappedLine = index + 1 >= message.lines.size()
                    || message.lines.get(index + 1).source != line.source;
            if (lastWrappedLine && !line.source.getAnnotations().isEmpty()) {
                for (LegacySourceReference source : line.source.getAnnotations()) {
                    if (lineY + 16 >= viewport.y() && lineY <= viewport.bottom()) {
                        int chipWidth = Math.min(message.width - 20,
                                Math.max(72, fontRenderer.getStringWidth(
                                        "来源 · " + source.displayLabel()) + 16));
                        int chipX = bubbleX + 10;
                        drawRect(chipX, lineY, chipX + chipWidth, lineY + 15, 0xCC203B52);
                        drawString(fontRenderer,
                                fontRenderer.trimStringToWidth("来源 · " + source.displayLabel(),
                                        Math.max(20, chipWidth - 12)),
                                chipX + 6, lineY + 3,
                                LegacyManualNavigator.supports(source)
                                        ? 0xFF91D5FF : 0xFF9AA4AE);
                        if (LegacyManualNavigator.supports(source)) {
                            sourceHits.add(new SourceHit(chipX, lineY, chipWidth, 15, source));
                        }
                    }
                    lineY += 18;
                }
            }
        }
        if (message.message.getRole() == LegacyChatMessage.Role.ASSISTANT
                && !message.message.getFollowUpQuestions().isEmpty()) {
            if (lineY + 10 >= viewport.y() && lineY <= viewport.bottom()) {
                drawString(fontRenderer, "可继续询问", bubbleX + 10, lineY, 0xFF8D99A6);
            }
            lineY += 14;
            for (String question : message.message.getFollowUpQuestions()) {
                int chipX = bubbleX + 10;
                int chipWidth = message.width - 20;
                if (lineY + 19 >= viewport.y() && lineY <= viewport.bottom()) {
                    drawRect(chipX, lineY, chipX + chipWidth, lineY + 19, 0xCC26364A);
                    drawString(fontRenderer,
                            fontRenderer.trimStringToWidth(question, Math.max(20, chipWidth - 12)),
                            chipX + 6, lineY + 5, 0xFFC5DDF2);
                    followUpHits.add(new FollowUpHit(chipX, lineY, chipWidth, 19, question));
                }
                lineY += 22;
            }
        }
    }

    private void drawSettings(int left, int panelWidth, LegacyPanelLayout.Layout layout) {
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        settingsScroll.configure(settingsContentHeight(viewport), viewport.height());
        int origin = viewport.y() - settingsScroll.offset();
        int label = 0xFFB9C5D0;
        int fieldY = settingsFieldY(origin, 0);
        drawString(fontRenderer, "工作模式", left + 12, origin, label);
        drawString(fontRenderer, onlySearch ? "当前：本地搜索" : "当前：AI + 工具",
                left + 120, origin, 0xFFE8E8E8);

        if (endpointField.getVisible()) {
            drawString(fontRenderer, "API 地址", left + 12, fieldY - 12, label);
            endpointField.drawTextBox();
        }
        fieldY = settingsFieldY(origin, 1);
        if (modelField.getVisible()) {
            drawString(fontRenderer, "模型", left + 12, fieldY - 12, label);
            modelField.drawTextBox();
        }
        fieldY = settingsFieldY(origin, 2);
        if (apiKeyField.getVisible()) {
            drawString(fontRenderer, "API Key", left + 12, fieldY - 12, label);
            drawMaskedField(apiKeyField);
        }

        int listLabel = settingsListLabelY(origin);
        int listRow = settingsListRowY(origin);
        drawString(fontRenderer, "模型列表（点击右侧“使用”填入）", left + 12, listLabel, label);
        if (models.isEmpty()) {
            drawString(fontRenderer, settingsBusy ? "正在读取……" : "尚未获取模型列表",
                    left + 12, listRow, 0xFF8D99A6);
        } else {
            int first = Math.max(0, (viewport.y() - listRow) / 18);
            int last = Math.min(models.size(), (viewport.bottom() - listRow + 17) / 18);
            for (int index = first; index < last; index++) {
                drawString(fontRenderer, models.get(index).id,
                        left + 14, listRow + index * 18, 0xFFB9DFFF);
            }
        }
    }

    private void drawHistory(int left, LegacyPanelLayout.Layout layout) {
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        historyScroll.configure(historyContentHeight(), viewport.height());
        int origin = viewport.y() - historyScroll.offset();
        if (conversations.isEmpty()) {
            drawString(fontRenderer, "暂无历史会话", left + 12, origin + 8, 0xFFB9C5D0);
            return;
        }
        int panelWidth = panelWidth();
        int titleWidth = Math.max(40, panelWidth - 142);
        int row = origin + 8;
        int first = Math.max(0, (viewport.y() - row) / 24);
        int last = Math.min(conversations.size(), (viewport.bottom() - row + 23) / 24);
        for (int index = first; index < last; index++) {
            ConversationSummary summary = conversations.get(index);
            String title = fontRenderer.trimStringToWidth(summary.title, titleWidth);
            if (!title.equals(summary.title)) {
                title += "…";
            }
            drawString(fontRenderer, title, left + 12, row + index * 24, 0xFFE8E8E8);
            drawString(fontRenderer, summary.messageCount + " 条消息", left + 12,
                    row + 10 + index * 24, 0xFF8D99A6);
        }
    }

    private void drawWelcomeSuggestions(int left, int top, int panelWidth,
            LegacyPanelLayout.Rect viewport) {
        List<String> suggestions = new ArrayList<String>();
        suggestions.add("如何开始使用 ModPedia？");
        int y = top;
        int width = Math.max(80, panelWidth - 32);
        for (String question : suggestions) {
            if (y + 21 > viewport.bottom()) {
                break;
            }
            int x = left + 12;
            drawRect(x, y, x + width, y + 20, 0xCC26364A);
            drawString(fontRenderer, fontRenderer.trimStringToWidth(question, width - 12),
                    x + 6, y + 6, 0xFFC5DDF2);
            suggestionHits.add(new SuggestionHit(x, y, width, 20, question));
            y += 24;
        }
    }

    private void drawFixedFooter(int left, int panelWidth, LegacyPanelLayout.Layout layout) {
        LegacyPanelLayout.Rect footer = layout.footer();
        if (panel == Panel.MAIN) {
            if (frozenTarget != null && !frozenTarget.getItemId().isEmpty()) {
                drawString(fontRenderer, "当前目标：" + frozenTarget.getDisplayName()
                        + "（点击“插入”后才会写入问题）", left + 12, footer.y() + 2, 0xFF91D5FF);
            }
            drawString(fontRenderer, busy ? "处理中……" : "按 Shift+左键点击回答中的物品可打开配方",
                    left + 12, footer.y() + 14, 0xFF8892A0);
            input.drawTextBox();
        } else if (panel == Panel.SETTINGS) {
            String message;
            int color;
            if (!LegacyWorkerBridge.get().isReady()) {
                message = "Worker 未连接，设置读写和模型测试暂不可用。";
                color = 0xFFFFB0A0;
            } else if (settingsLoaded) {
                message = settingsDirty
                        ? "有未保存设置；API Key 由 Worker 加密保存。"
                        : "API Key 已隐藏；保存后由 Worker 加密保存。";
                color = 0xFF8D99A6;
            } else {
                message = "设置尚未读取。";
                color = 0xFF8D99A6;
            }
            drawString(fontRenderer, message, left + 12, footer.y() + 10, color);
        } else if (panel == Panel.HISTORY && renamingConversation && renameField != null) {
            drawString(fontRenderer, "重命名会话", left + 12, footer.y() - 2, 0xFFB9C5D0);
            if (renameField.getVisible()) {
                renameField.drawTextBox();
            }
        }
    }

    private void beginScissor(LegacyPanelLayout.Rect rectangle) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int displayWidth = minecraft.displayWidth;
        int displayHeight = minecraft.displayHeight;
        if (displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(minecraft);
        int scale = Math.max(1, resolution.getScaleFactor());
        int x = rectangle.x() * scale;
        int y = displayHeight - rectangle.bottom() * scale;
        int width = rectangle.width() * scale;
        int height = rectangle.height() * scale;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, Math.max(0, y), width, Math.max(1, height));
    }

    private void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void refreshLayoutIfNeeded() {
        if (layoutWidth == width && layoutHeight == height) {
            return;
        }
        layoutWidth = width;
        layoutHeight = height;
        if (input != null) {
            rebuildFields();
            rebuildButtons();
        }
    }

    private LegacyPanelLayout.Layout currentLayout() {
        return LegacyPanelLayout.calculate(width, height);
    }

    private int settingsContentHeight(LegacyPanelLayout.Rect viewport) {
        int rows = Math.max(1, models.size());
        return Math.max(viewport.height(), settingsListRowY(0) + rows * 18 + 12);
    }

    private int settingsModeY(int origin) {
        return origin + SETTINGS_MODE_BUTTON;
    }

    private int settingsFieldY(int origin, int index) {
        return origin + SETTINGS_FIELD_START + Math.max(0, index) * 32;
    }

    private int settingsProtocolY(int origin) {
        return origin + SETTINGS_PROTOCOL;
    }

    private int settingsControlsY(int origin) {
        return origin + SETTINGS_CONTROLS;
    }

    private int settingsListLabelY(int origin) {
        return origin + SETTINGS_LIST_LABEL;
    }

    private int settingsListRowY(int origin) {
        return settingsListLabelY(origin) + 18;
    }

    private int historyContentHeight() {
        LegacyPanelLayout.Rect viewport = currentLayout().contentViewport();
        return Math.max(viewport.height(), 8 + Math.max(1, conversations.size()) * 24 + 10);
    }

    private void drawMaskedField(GuiTextField field) {
        if (field.isFocused()) {
            field.drawTextBox();
            return;
        }
        drawRect(field.x, field.y, field.x + field.width, field.y + field.height, 0xFF101217);
        String value = field.getText();
        if (!value.isEmpty()) {
            StringBuilder masked = new StringBuilder();
            int length = Math.min(value.length(), Math.max(4, (field.width - 8) / 6));
            for (int index = 0; index < length; index++) {
                masked.append('•');
            }
            drawString(fontRenderer, masked.toString(), field.x + 4, field.y + 6, 0xFFD8DDE2);
        }
    }

    private void send() {
        if (busy || input == null) {
            return;
        }
        String typedPrompt = input.getText().trim();
        if (typedPrompt.isEmpty() && !retryAvailable) {
            return;
        }
        final String prompt = typedPrompt.isEmpty() ? lastPrompt : typedPrompt;
        if (prompt.isEmpty()) {
            return;
        }
        boolean retry = typedPrompt.isEmpty() && retryAvailable;
        lastPrompt = prompt;
        retryAvailable = false;
        busy = true;
        status = onlySearch ? "正在搜索" : "正在处理";
        errorMessage = "";
        assistantDraft = "";
        if (!retry) {
            messages.add(new LegacyChatMessage(LegacyChatMessage.Role.USER, prompt));
        }
        // 发送动作在本地先完成，避免 Worker 首个事件到达前输入框仍显示旧问题。
        input.setText("");
        input.setCursorPositionEnd();
        input.setFocused(true);
        scrollToEnd = true;
        if (LegacyWorkerBridge.get().isReady()) {
            activeRequestId = LegacyWorkerBridge.get().startChatRequest(
                    prompt,
                    Minecraft.getMinecraft().gameSettings.language,
                    activeConversationId,
                    new Consumer<JsonObject>() {
                        @Override
                        public void accept(JsonObject event) {
                            handleWorkerEvent(event);
                        }
                    }
            );
            if (activeRequestId != null) {
                rebuildButtons();
                return;
            }
        }
        final Path knowledge = configKnowledgeRoot();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String localAnswer = LegacyLocalSearch.search(knowledge, prompt);
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (closed) {
                            return;
                        }
                        messages.add(new LegacyChatMessage(
                                LegacyChatMessage.Role.ASSISTANT, localAnswer));
                        assistantDraft = "";
                        status = "本地搜索回退";
                        busy = false;
                        activeRequestId = null;
                        retryAvailable = false;
                        rebuildButtons();
                    }
                });
            }
        }, "ModPedia-1.12-local-search").start();
    }

    private void cancelRequest() {
        if (!busy || activeRequestId == null) {
            return;
        }
        status = "正在取消";
        LegacyWorkerBridge.get().cancelChat(activeRequestId);
    }

    private void handleWorkerEvent(JsonObject event) {
        if (closed || event == null) {
            return;
        }
        String eventRequestId = value(event, "request_id");
        if (!eventRequestId.isEmpty() && finishedRequestIds.contains(eventRequestId)) {
            return;
        }
        if (activeRequestId != null && !eventRequestId.isEmpty()
                && !activeRequestId.equals(eventRequestId)) {
            return;
        }
        if (!busy && activeRequestId == null) {
            return;
        }
        String type = value(event, "type");
        if ("text_delta".equals(type)) {
            assistantDraft += value(event, "text");
            scrollToEnd = true;
            status = onlySearch ? "正在整理搜索结果" : "正在生成回答";
        } else if ("status".equals(type)) {
            status = value(event, "message");
        } else if ("completed".equals(type)) {
            boolean hasMessages = event.has("messages") && event.get("messages").isJsonArray();
            String finalAnswer = LegacyConversationPayload.answerText(event, assistantDraft);
            if (hasMessages) {
                applyConversationState(event, finalAnswer);
            }
            if (!hasMessages && !finalAnswer.isEmpty()) {
                messages.add(new LegacyChatMessage(
                        LegacyChatMessage.Role.ASSISTANT, finalAnswer,
                        parseSources(event), parseStrings(event, "follow_up_questions")));
            } else if (!hasMessages && !assistantDraft.isEmpty()) {
                messages.add(new LegacyChatMessage(
                        LegacyChatMessage.Role.ASSISTANT, assistantDraft,
                        parseSources(event), parseStrings(event, "follow_up_questions")));
            }
            assistantDraft = "";
            errorMessage = "";
            status = "完成";
            busy = false;
            rememberFinishedRequest(eventRequestId);
            activeRequestId = null;
            retryAvailable = false;
            rebuildButtons();
        } else if ("error".equals(type)) {
            if (!assistantDraft.isEmpty()) {
                messages.add(new LegacyChatMessage(
                        LegacyChatMessage.Role.ASSISTANT, assistantDraft));
            }
            assistantDraft = "";
            errorMessage = value(event, "message");
            status = isTimeoutMessage(errorMessage) ? "请求超时，可点击重试" : "请求失败，可点击重试";
            busy = false;
            rememberFinishedRequest(eventRequestId);
            activeRequestId = null;
            retryAvailable = !lastPrompt.isEmpty();
            rebuildButtons();
        } else if ("cancelled".equals(type)) {
            if (!assistantDraft.isEmpty()) {
                messages.add(new LegacyChatMessage(
                        LegacyChatMessage.Role.ASSISTANT, assistantDraft));
            }
            assistantDraft = "";
            errorMessage = "";
            status = "已取消，可点击重试";
            busy = false;
            rememberFinishedRequest(eventRequestId);
            activeRequestId = null;
            retryAvailable = !lastPrompt.isEmpty();
            rebuildButtons();
        }
    }

    private void rememberFinishedRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        finishedRequestIds.add(requestId);
        while (finishedRequestIds.size() > 32) {
            finishedRequestIds.remove(finishedRequestIds.iterator().next());
        }
    }

    private void insertTarget() {
        if (frozenTarget == null || frozenTarget.getItemId().isEmpty() || input == null) {
            return;
        }
        String token = "[[item:" + frozenTarget.getItemId() + "|"
                + frozenTarget.getDisplayName() + "]]";
        input.writeText(token);
    }

    private void loadSettings() {
        if (!LegacyWorkerBridge.get().isReady() || settingsBusy) {
            return;
        }
        settingsBusy = true;
        status = "正在读取设置";
        LegacyWorkerBridge.get().loadSettings().whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                settingsBusy = false;
                if (closed) {
                    return;
                }
                if (failure != null || event == null || !event.has("settings")) {
                    status = "设置读取失败";
                    return;
                }
                applySettings(event.getAsJsonObject("settings"));
                settingsLoaded = true;
                settingsDirty = false;
                status = "设置已读取";
                rebuildFields();
                rebuildButtons();
            }
        }));
    }

    private void saveSettings() {
        if (!LegacyWorkerBridge.get().isReady() || settingsBusy) {
            status = "Worker 未连接，设置未保存";
            return;
        }
        settingsBusy = true;
        status = "正在保存设置";
        LegacyWorkerBridge.get().saveSettings(settingsJson()).whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                settingsBusy = false;
                if (failure != null || event == null || "error".equals(value(event, "type"))) {
                    status = failure == null ? value(event, "message") : "设置保存失败";
                    return;
                }
                settingsLoaded = true;
                settingsDirty = false;
                status = "设置已保存";
                rebuildFields();
                rebuildButtons();
            }
        }));
    }

    private void fetchModels() {
        if (onlySearch) {
            status = "仅搜索模式不需要模型列表";
            return;
        }
        if (!LegacyWorkerBridge.get().isReady() || settingsBusy) {
            status = "Worker 未连接，模型列表不可用";
            return;
        }
        settingsBusy = true;
        status = "正在获取模型列表";
        LegacyWorkerBridge.get().fetchModels(settingsJson()).whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                settingsBusy = false;
                if (failure != null || event == null || "error".equals(value(event, "type"))) {
                    status = failure == null ? value(event, "message") : "模型列表获取失败";
                    return;
                }
                models.clear();
                JsonElement values = event.get("models");
                if (values != null && values.isJsonArray()) {
                    for (JsonElement element : values.getAsJsonArray()) {
                        if (element.isJsonObject()) {
                            JsonObject model = element.getAsJsonObject();
                            models.add(new ModelOption(value(model, "id"), value(model, "owned_by")));
                        }
                    }
                }
                status = "已获取 " + models.size() + " 个模型";
                rebuildButtons();
            }
        }));
    }

    private void testConnection() {
        if (onlySearch) {
            status = "仅搜索模式不需要连接测试";
            return;
        }
        if (!LegacyWorkerBridge.get().isReady() || settingsBusy) {
            status = "Worker 未连接，连接测试不可用";
            return;
        }
        settingsBusy = true;
        status = "正在测试连接";
        LegacyWorkerBridge.get().testConnection(settingsJson()).whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                settingsBusy = false;
                if (failure != null || event == null || "error".equals(value(event, "type"))) {
                    status = failure == null ? value(event, "message") : "连接测试失败";
                } else {
                    status = value(event, "message");
                    if (status.isEmpty()) {
                        status = "连接测试通过";
                    }
                }
                rebuildButtons();
            }
        }));
    }

    private void loadConversations(final boolean restoreLastConversation) {
        if (!LegacyWorkerBridge.get().isReady()) {
            return;
        }
        LegacyWorkerBridge.get().listConversations().whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (closed || failure != null || event == null) {
                    return;
                }
                applyConversationState(event);
                rebuildButtons();
                if (restoreLastConversation && messages.isEmpty() && !conversations.isEmpty()) {
                    // 新建但未发送的内存草稿不应遮住上次有效会话；只在助手首次打开
                    // 时恢复，不影响用户主动进入历史页浏览或新建草稿。
                    selectConversation(conversations.get(0).id);
                }
            }
        }));
    }

    private void openBuiltInGuide() {
        final String prompt = "如何开始使用 ModPedia？";
        if (!LegacyWorkerBridge.get().isReady()) {
            input.setText(prompt);
            input.setCursorPositionEnd();
            input.setFocused(true);
            status = "Worker 未就绪，请稍后再试";
            return;
        }
        status = "正在打开 ModPedia 使用说明";
        LegacyWorkerBridge.get().newConversation().whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (closed || failure != null || event == null) {
                    status = "使用说明会话创建失败";
                    return;
                }
                applyConversationState(event);
                lastPrompt = "";
                retryAvailable = false;
                assistantDraft = "";
                errorMessage = "";
                panel = Panel.MAIN;
                input.setText(prompt);
                input.setCursorPositionEnd();
                input.setFocused(true);
                rebuildFields();
                rebuildButtons();
                send();
            }
        }));
    }

    private void newConversation() {
        if (!LegacyWorkerBridge.get().isReady()) {
            status = "Worker 未连接，无法新建会话";
            return;
        }
        LegacyWorkerBridge.get().newConversation().whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (failure == null && event != null) {
                    applyConversationState(event);
                    lastPrompt = "";
                    retryAvailable = false;
                    assistantDraft = "";
                    errorMessage = "";
                    status = "新会话";
                    panel = Panel.MAIN;
                    input.setFocused(true);
                    rebuildFields();
                    rebuildButtons();
                }
            }
        }));
    }

    private void selectConversation(String conversationId) {
        if (!LegacyWorkerBridge.get().isReady()) {
            return;
        }
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        final long selectionToken = ++conversationSelectionToken;
        String selectedId = conversationId.trim();
        if (!activeConversationId.isEmpty() && !messages.isEmpty()) {
            conversationMessageCache.put(activeConversationId,
                    new ArrayList<LegacyChatMessage>(messages));
        }
        ConversationSummary selected = findConversation(selectedId);
        List<LegacyChatMessage> cached = conversationMessageCache.get(selectedId);
        activeConversationId = selectedId;
        if (selected != null) {
            activeConversationTitle = selected.title;
        }
        panel = Panel.MAIN;
        input.setFocused(true);
        assistantDraft = "";
        errorMessage = "";
        retryAvailable = false;
        if (cached != null) {
            messages.clear();
            messages.addAll(cached);
            status = "会话已切换";
            scrollToEnd = true;
        } else {
            // 没有本地缓存时也立即回到主页面，避免历史页停留在“正在切换”；
            // 收到 Worker 状态后再填充真实消息。
            messages.clear();
            status = "正在加载会话";
        }
        rebuildFields();
        rebuildButtons();
        LegacyWorkerBridge.get().selectConversation(selectedId).whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (closed || selectionToken != conversationSelectionToken) {
                    return;
                }
                if (failure != null || event == null) {
                    status = "会话切换失败";
                    rebuildButtons();
                    return;
                }
                applyConversationState(event);
                lastPrompt = "";
                retryAvailable = false;
                assistantDraft = "";
                errorMessage = "";
                status = "会话已切换";
                panel = Panel.MAIN;
                input.setFocused(true);
                rebuildFields();
                rebuildButtons();
            }
        }));
    }

    private ConversationSummary findConversation(String conversationId) {
        for (ConversationSummary summary : conversations) {
            if (conversationId.equals(summary.id)) {
                return summary;
            }
        }
        return null;
    }

    private void deleteConversation(String conversationId) {
        if (!LegacyWorkerBridge.get().isReady()) {
            return;
        }
        LegacyWorkerBridge.get().deleteConversation(conversationId).whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (failure == null && event != null) {
                    applyConversationState(event);
                    lastPrompt = "";
                    retryAvailable = false;
                    assistantDraft = "";
                    errorMessage = "";
                    status = "会话已删除";
                    rebuildButtons();
                }
            }
        }));
    }

    private void clearConversation() {
        if (!LegacyWorkerBridge.get().isReady()) {
            return;
        }
        LegacyWorkerBridge.get().clearConversation().whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (failure == null && event != null) {
                    applyConversationState(event);
                    lastPrompt = "";
                    retryAvailable = false;
                    assistantDraft = "";
                    errorMessage = "";
                    status = "会话已清空";
                    rebuildButtons();
                }
            }
        }));
    }

    private void beginRename() {
        if (activeConversationId.isEmpty() || renameField == null) {
            status = "没有可重命名的会话";
            return;
        }
        renamingConversation = true;
        renameField.setText(activeConversationTitle);
        renameField.setCursorPositionEnd();
        renameField.setFocused(true);
        status = "请输入新的会话名称";
        rebuildFields();
        rebuildButtons();
    }

    private void cancelRename() {
        renamingConversation = false;
        if (renameField != null) {
            renameField.setText("");
            renameField.setFocused(false);
        }
        status = "已取消重命名";
        rebuildFields();
        rebuildButtons();
    }

    private void saveRename() {
        if (!renamingConversation || renameField == null || activeConversationId.isEmpty()) {
            return;
        }
        String title = renameField.getText().trim();
        if (title.isEmpty()) {
            status = "会话名称不能为空";
            return;
        }
        renamingConversation = false;
        renameField.setFocused(false);
        status = "正在保存会话名称";
        LegacyWorkerBridge.get().renameConversation(activeConversationId, title)
                .whenComplete((event, failure) -> onClient(new Runnable() {
                    @Override
                    public void run() {
                        if (failure != null || event == null) {
                            status = "会话名称保存失败";
                        } else {
                            applyConversationState(event);
                            status = "会话名称已保存";
                        }
                        rebuildFields();
                        rebuildButtons();
                    }
                }));
        rebuildFields();
        rebuildButtons();
    }

    private void applySettings(JsonObject settings) {
        onlySearch = "SEARCH_ONLY".equalsIgnoreCase(value(settings, "mode"));
        streaming = !settings.has("streaming") || settings.get("streaming").getAsBoolean();
        apiFormat = enumOrDefault(value(settings, "api_format"),
                new String[]{"CHAT_COMPLETIONS", "NATIVE_MESSAGES", "RESPONSES", "GENERATE_CONTENT"},
                "CHAT_COMPLETIONS");
        intensity = enumOrDefault(value(settings, "intensity"),
                new String[]{"FAST", "STANDARD", "DEEP", "CUSTOM"}, "STANDARD");
        endpointField.setText(value(settings, "endpoint"));
        modelField.setText(value(settings, "model"));
        apiKeyField.setText(value(settings, "api_key"));
    }

    private JsonObject settingsJson() {
        JsonObject settings = new JsonObject();
        settings.addProperty("mode", onlySearch ? "SEARCH_ONLY" : "AI");
        settings.addProperty("api_format", apiFormat);
        settings.addProperty("endpoint", endpointField.getText().trim());
        settings.addProperty("model", modelField.getText().trim());
        settings.addProperty("api_key", apiKeyField.getText());
        settings.addProperty("streaming", streaming);
        settings.addProperty("intensity", intensity);
        settings.addProperty("max_rounds", intensity.equals("FAST") ? 1 : intensity.equals("DEEP") ? 5 : 3);
        settings.addProperty("max_results", intensity.equals("FAST") ? 4 : intensity.equals("DEEP") ? 12 : 8);
        settings.addProperty("max_context_chars", intensity.equals("FAST") ? 8000
                : intensity.equals("DEEP") ? 28000 : 16000);
        settings.addProperty("timeout_seconds", 90);
        return settings;
    }

    private void applyConversationState(JsonObject event) {
        applyConversationState(event, "");
    }

    private void applyConversationState(JsonObject event, String fallbackAnswer) {
        if (!activeConversationId.isEmpty() && !messages.isEmpty()) {
            conversationMessageCache.put(activeConversationId,
                    new ArrayList<LegacyChatMessage>(messages));
        }
        String conversationId = value(event, "active_conversation_id");
        if (!conversationId.isEmpty()) {
            activeConversationId = conversationId;
        }
        String conversationTitle = value(event, "active_title");
        if (!conversationTitle.isEmpty()) {
            activeConversationTitle = conversationTitle;
        }
        JsonElement values = event.get("conversations");
        if (values != null && values.isJsonArray()) {
            conversations.clear();
            for (JsonElement element : values.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject summary = element.getAsJsonObject();
                    int messageCount = intValue(summary, "message_count", 0);
                    String summaryId = value(summary, "id");
                    if (messageCount > 0 && !summaryId.isEmpty()) {
                        conversations.add(new ConversationSummary(
                                summaryId, value(summary, "title"), messageCount
                        ));
                    }
                }
            }
        }
        JsonElement messageValues = event.get("messages");
        boolean hasMessageArray = messageValues != null && messageValues.isJsonArray();
        List<LegacyChatMessage> loadedMessages = new ArrayList<LegacyChatMessage>();
        if (hasMessageArray) {
            for (JsonElement element : messageValues.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                loadedMessages.add(new LegacyChatMessage(
                        roleOf(value(message, "role")),
                        LegacyConversationPayload.messageText(message),
                        parseSources(message),
                        parseStrings(message, "follow_up_questions")));
            }
        }
        if (hasMessageArray && (!loadedMessages.isEmpty() || fallbackAnswer.isEmpty())) {
            this.messages.clear();
            this.messages.addAll(loadedMessages);
        }
        if (!fallbackAnswer.isEmpty()
                && !hasAssistantMessage(hasMessageArray ? loadedMessages : this.messages)) {
            appendFallbackAssistant(fallbackAnswer, event);
        }
        if (!activeConversationId.isEmpty()) {
            if (messages.isEmpty()) {
                conversationMessageCache.remove(activeConversationId);
            } else {
                conversationMessageCache.put(activeConversationId,
                        new ArrayList<LegacyChatMessage>(messages));
            }
        }
        lastPrompt = "";
        for (int index = this.messages.size() - 1; index >= 0; index--) {
            LegacyChatMessage message = this.messages.get(index);
            if (message.getRole() == LegacyChatMessage.Role.USER) {
                lastPrompt = message.getMarkdown();
                break;
            }
        }
        assistantDraft = "";
        errorMessage = "";
        scrollToEnd = true;
    }

    private boolean hasAssistantMessage(List<LegacyChatMessage> values) {
        for (LegacyChatMessage message : values) {
            if (message.getRole() == LegacyChatMessage.Role.ASSISTANT
                    && !message.getMarkdown().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void appendFallbackAssistant(String answer, JsonObject event) {
        List<LegacySourceReference> sources = parseSources(event);
        List<String> followUps = parseStrings(event, "follow_up_questions");
        for (int index = messages.size() - 1; index >= 0; index--) {
            LegacyChatMessage message = messages.get(index);
            if (message.getRole() == LegacyChatMessage.Role.ASSISTANT
                    && message.getMarkdown().trim().isEmpty()) {
                messages.set(index, new LegacyChatMessage(
                        LegacyChatMessage.Role.ASSISTANT,
                        answer,
                        message.getSources().isEmpty() ? sources : message.getSources(),
                        message.getFollowUpQuestions().isEmpty()
                                ? followUps : message.getFollowUpQuestions()));
                return;
            }
        }
        messages.add(new LegacyChatMessage(
                LegacyChatMessage.Role.ASSISTANT, answer, sources, followUps));
    }

    private LegacyChatMessage.Role roleOf(String value) {
        if ("USER".equalsIgnoreCase(value) || "user".equalsIgnoreCase(value)) {
            return LegacyChatMessage.Role.USER;
        }
        if ("ASSISTANT".equalsIgnoreCase(value)
                || "assistant".equalsIgnoreCase(value)) {
            return LegacyChatMessage.Role.ASSISTANT;
        }
        return LegacyChatMessage.Role.SYSTEM;
    }

    private List<LegacySourceReference> parseSources(JsonObject object) {
        List<LegacySourceReference> result = new ArrayList<LegacySourceReference>();
        JsonElement values = object == null ? null : object.get("sources");
        if (values == null || !values.isJsonArray()) {
            return result;
        }
        for (JsonElement element : values.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            result.add(new LegacySourceReference(
                    value(source, "document_id"),
                    value(source, "title"),
                    value(source, "source_path"),
                    value(source, "annotation").isEmpty()
                            ? value(source, "label") : value(source, "annotation")));
        }
        return result;
    }

    private List<String> parseStrings(JsonObject object, String key) {
        List<String> result = new ArrayList<String>();
        JsonElement values = object == null ? null : object.get(key);
        if (values == null || !values.isJsonArray()) {
            return result;
        }
        for (JsonElement element : values.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    result.add(value.trim());
                }
            }
        }
        return result;
    }

    private void rebuildFields() {
        if (input == null) {
            return;
        }
        LegacyPanelLayout.Layout layout = currentLayout();
        LegacyPanelLayout.Rect window = layout.window();
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        int panelWidth = window.width();
        int left = window.x();
        int origin = viewport.y() - settingsScroll.offset();
        int y = settingsFieldY(origin, 0);
        endpointField.x = left + 12;
        endpointField.y = y;
        endpointField.width = panelWidth - 24;
        y = settingsFieldY(origin, 1);
        modelField.x = left + 12;
        modelField.y = y;
        modelField.width = panelWidth - 24;
        y = settingsFieldY(origin, 2);
        apiKeyField.x = left + 12;
        apiKeyField.y = y;
        apiKeyField.width = panelWidth - 24;
        input.x = left + 12;
        input.y = layout.footer().y() + 28;
        input.width = Math.max(80, panelWidth - 150);
        input.setVisible(panel == Panel.MAIN);
        endpointField.setVisible(panel == Panel.SETTINGS
                && fieldVisible(endpointField.y, endpointField.height, viewport));
        modelField.setVisible(panel == Panel.SETTINGS
                && fieldVisible(modelField.y, modelField.height, viewport));
        apiKeyField.setVisible(panel == Panel.SETTINGS
                && fieldVisible(apiKeyField.y, apiKeyField.height, viewport));
        if (!endpointField.getVisible()) {
            endpointField.setFocused(false);
        }
        if (!modelField.getVisible()) {
            modelField.setFocused(false);
        }
        if (!apiKeyField.getVisible()) {
            apiKeyField.setFocused(false);
        }
        boolean aiEnabled = !onlySearch;
        endpointField.setEnabled(aiEnabled);
        modelField.setEnabled(aiEnabled);
        apiKeyField.setEnabled(aiEnabled);
        if (renameField != null) {
            LegacyPanelLayout.Rect footer = layout.footer();
            renameField.x = left + 12;
            renameField.y = footer.y() + 4;
            renameField.width = Math.max(80, panelWidth - 136);
            renameField.setVisible(panel == Panel.HISTORY && renamingConversation);
            if (!renameField.getVisible()) {
                renameField.setFocused(false);
            }
        }
    }

    private void rebuildButtons() {
        if (buttonList == null) {
            return;
        }
        LegacyPanelLayout.Layout layout = currentLayout();
        LegacyPanelLayout.Rect window = layout.window();
        LegacyPanelLayout.Rect viewport = layout.contentViewport();
        LegacyPanelLayout.Rect footer = layout.footer();
        int panelWidth = window.width();
        int left = window.x();
        int top = window.y();
        int footerY = footer.y() + 28;
        buttonList.clear();
        buttonList.add(new GuiButton(CLOSE, left + panelWidth - 58, top + 5, 46, 20,
                panel == Panel.MAIN ? "关闭" : "返回"));
        if (panel == Panel.MAIN) {
            buttonList.add(new GuiButton(HISTORY, left + panelWidth - 164, top + 5, 48, 20, "历史"));
            buttonList.add(new GuiButton(SETTINGS, left + panelWidth - 110, top + 5, 48, 20, "设置"));
            if (busy) {
                GuiButton cancel = new GuiButton(CANCEL, left + panelWidth - 132, footerY, 58, 20, "取消");
                cancel.enabled = activeRequestId != null;
                buttonList.add(cancel);
            } else {
                buttonList.add(new GuiButton(SEND, left + panelWidth - 132, footerY, 58, 20,
                        retryAvailable ? "重试" : "发送"));
            }
            buttonList.add(new GuiButton(INSERT, left + panelWidth - 70, footerY, 58, 20, "插入"));
        } else if (panel == Panel.SETTINGS) {
            GuiButton save = new GuiButton(SETTINGS_SAVE, left + 12, footerY, 58, 20, "保存");
            save.enabled = !settingsBusy && LegacyWorkerBridge.get().isReady();
            buttonList.add(save);
            GuiButton fetch = new GuiButton(SETTINGS_FETCH_MODELS, left + 76, footerY, 78, 20, "获取模型");
            fetch.enabled = !settingsBusy && !onlySearch && LegacyWorkerBridge.get().isReady();
            buttonList.add(fetch);
            GuiButton test = new GuiButton(SETTINGS_TEST_CONNECTION, left + 160, footerY, 78, 20, "测试连接");
            test.enabled = !settingsBusy && !onlySearch && LegacyWorkerBridge.get().isReady();
            buttonList.add(test);
            int origin = viewport.y() - settingsScroll.offset();
            addScrollableButton(new GuiButton(SETTINGS_MODE_AI, left + 12,
                    settingsModeY(origin), 90, 20,
                    onlySearch ? "AI 回答" : "AI 回答 ✓"), viewport);
            addScrollableButton(new GuiButton(SETTINGS_MODE_SEARCH, left + 108,
                    settingsModeY(origin), 90, 20,
                    onlySearch ? "仅搜索 ✓" : "仅搜索"), viewport);
            int controlsTop = settingsControlsY(origin);
            int columnWidth = Math.max(80, (panelWidth - 36) / 2);
            int secondColumn = left + 12 + columnWidth + 6;
            int secondWidth = Math.max(80, panelWidth - 30 - columnWidth);
            GuiButton stream = new GuiButton(SETTINGS_STREAMING, left + 12, controlsTop,
                    columnWidth, 20,
                    streaming ? "流式：开" : "流式：关");
            stream.enabled = !onlySearch;
            addScrollableButton(stream, viewport);
            GuiButton format = new GuiButton(SETTINGS_FORMAT, secondColumn, controlsTop,
                    secondWidth, 20,
                    "协议：" + pretty(apiFormat));
            format.enabled = !onlySearch;
            addScrollableButton(format, viewport);
            addScrollableButton(new GuiButton(SETTINGS_INTENSITY, left + 12, controlsTop + 24,
                    columnWidth, 20, "强度：" + pretty(intensity)), viewport);
            int row = settingsListRowY(origin);
            for (int index = 0; index < models.size(); index++) {
                addScrollableButton(new GuiButton(MODEL_BASE + index, left + panelWidth - 120,
                        row + index * 18 - 4, 108, 18, "使用"), viewport);
            }
        } else {
            buttonList.add(new GuiButton(HISTORY_NEW, left + 68, top + 5, 58, 20, "新建"));
            buttonList.add(new GuiButton(HISTORY_CLEAR, left + 132, top + 5, 58, 20, "清空"));
            if (renamingConversation) {
                buttonList.add(new GuiButton(HISTORY_RENAME_SAVE, left + panelWidth - 118,
                        footerY, 52, 20, "保存"));
                buttonList.add(new GuiButton(HISTORY_RENAME_CANCEL, left + panelWidth - 62,
                        footerY, 52, 20, "取消"));
            } else {
                GuiButton rename = new GuiButton(HISTORY_RENAME, left + panelWidth - 118,
                        footerY, 108, 20, "重命名当前");
                rename.enabled = !activeConversationId.isEmpty();
                buttonList.add(rename);
            }
            int row = viewport.y() - historyScroll.offset() + 8;
            for (int index = 0; index < conversations.size(); index++) {
                addScrollableButton(new GuiButton(HISTORY_SELECT_BASE + index,
                        left + panelWidth - 120, row + index * 24 - 4, 52, 18, "打开"), viewport);
                addScrollableButton(new GuiButton(HISTORY_DELETE_BASE + index,
                        left + panelWidth - 62, row + index * 24 - 4, 52, 18, "删除"), viewport);
            }
        }
    }

    private void addScrollableButton(GuiButton button, LegacyPanelLayout.Rect viewport) {
        button.visible = fieldVisible(button.y, button.height, viewport);
        buttonList.add(button);
    }

    private boolean fieldVisible(int y, int fieldHeight, LegacyPanelLayout.Rect viewport) {
        // 控件绘制在 scissor 之外执行（GuiScreen.super），因此只显示完整落在
        // viewport 内的控件，避免按钮半截穿出滚动区域。
        return y >= viewport.y() && y + fieldHeight <= viewport.bottom();
    }

    private void close() {
        Minecraft.getMinecraft().displayGuiScreen(previousScreen);
    }

    static boolean closeExitsAssistant(Panel current) {
        return current == Panel.MAIN;
    }

    private void returnToMainPanel() {
        panel = Panel.MAIN;
        renamingConversation = false;
        if (renameField != null) {
            renameField.setText("");
            renameField.setFocused(false);
        }
        if (input != null) {
            input.setFocused(true);
        }
        rebuildFields();
        rebuildButtons();
    }

    private void collectTokenHits(String originalLine, String formattedLine, int x, int y,
            boolean showIds) {
        Matcher matcher = ITEM_TOKEN.matcher(originalLine == null ? "" : originalLine);
        StringBuilder visible = new StringBuilder();
        List<Integer> formattedOffsets = new ArrayList<Integer>();
        for (int index = 0; index < (formattedLine == null ? 0 : formattedLine.length()); index++) {
            char value = formattedLine.charAt(index);
            if (value == '\u00a7' && index + 1 < formattedLine.length()) {
                index++;
                continue;
            }
            visible.append(value);
            formattedOffsets.add(index);
        }
        int searchFrom = 0;
        while (matcher.find()) {
            String raw = matcher.group(0);
            String shown = LegacyMarkdownRenderer.visibleText(raw, showIds);
            int plainStart = visible.indexOf(shown, searchFrom);
            if (plainStart < 0 || shown.isEmpty() || plainStart >= formattedOffsets.size()) {
                continue;
            }
            int plainEnd = Math.min(visible.length(), plainStart + shown.length());
            int formattedStart = formattedOffsets.get(plainStart);
            int formattedEnd = plainEnd <= plainStart
                    ? formattedStart + 1
                    : formattedOffsets.get(plainEnd - 1) + 1;
            int start = fontRenderer.getStringWidth(formattedLine.substring(0, formattedStart));
            int tokenWidth = Math.max(8, fontRenderer.getStringWidth(
                    formattedLine.substring(formattedStart, formattedEnd)));
            tokenHits.add(new TokenHit(x + start, y, tokenWidth, 10, raw));
            searchFrom = plainEnd;
        }
    }

    /**
     * 为手册转换留下的配方字段建立命中区域。
     *
     * <p>新编译的文档使用 {@code [[recipe:id|显示文本]]}；旧的已经导入的
     * 文档仍可能是 {@code **recipe**：id}，两种格式都要支持，否则用户必须
     * 等待整库重建后才能打开旧手册中的配方。</p>
     */
    private void collectRecipeHits(String originalLine, String formattedLine, int x, int y) {
        String original = originalLine == null ? "" : originalLine;
        String formatted = formattedLine == null ? "" : formattedLine;
        StringBuilder visible = new StringBuilder();
        List<Integer> formattedOffsets = new ArrayList<Integer>();
        for (int index = 0; index < formatted.length(); index++) {
            char value = formatted.charAt(index);
            if (value == '\u00a7' && index + 1 < formatted.length()) {
                index++;
                continue;
            }
            visible.append(value);
            formattedOffsets.add(index);
        }

        int searchFrom = 0;
        boolean foundToken = false;
        Matcher token = RECIPE_TOKEN.matcher(original);
        while (token.find()) {
            foundToken = true;
            String recipeId = token.group(1) == null ? "" : token.group(1).trim();
            String explicit = token.group(2);
            String shown = explicit == null || explicit.trim().isEmpty()
                    ? recipeId : explicit.trim();
            searchFrom = addRecipeHit(visible, formatted, formattedOffsets, searchFrom,
                    shown, recipeId, x, y);
        }

        // 兼容重建前已经存在于 config/modpedia/knowledge 中的旧转换结果。
        if (!foundToken) {
            Matcher legacy = LEGACY_RECIPE_FIELD.matcher(original);
            while (legacy.find()) {
                String recipeId = legacy.group(1) == null ? "" : legacy.group(1).trim();
                searchFrom = addRecipeHit(visible, formatted, formattedOffsets, searchFrom,
                        recipeId, recipeId, x, y);
            }
        }
    }

    private int addRecipeHit(StringBuilder visible, String formatted,
            List<Integer> formattedOffsets, int searchFrom, String shown, String recipeId,
            int x, int y) {
        if (shown == null || shown.isEmpty() || recipeId == null || recipeId.isEmpty()) {
            return searchFrom;
        }
        int plainStart = visible.indexOf(shown, searchFrom);
        if (plainStart < 0 || plainStart >= formattedOffsets.size()) {
            return searchFrom;
        }
        int plainEnd = Math.min(visible.length(), plainStart + shown.length());
        int formattedStart = formattedOffsets.get(plainStart);
        int formattedEnd = plainEnd <= plainStart
                ? formattedStart + 1 : formattedOffsets.get(plainEnd - 1) + 1;
        int start = fontRenderer.getStringWidth(formatted.substring(0, formattedStart));
        int recipeWidth = Math.max(8, fontRenderer.getStringWidth(
                formatted.substring(formattedStart, formattedEnd)));
        recipeHits.add(new RecipeHit(x + start, y, recipeWidth, 10, recipeId));
        return plainEnd;
    }

    private void onClient(Runnable runnable) {
        try {
            Minecraft.getMinecraft().addScheduledTask(runnable);
        } catch (Throwable ignored) {
            // GUI 已关闭时丢弃异步回调。
        }
    }

    private int panelWidth() {
        return currentLayout().window().width();
    }

    private int panelLeft(int panelWidth) {
        return (width - panelWidth) / 2;
    }

    private boolean showIds() {
        return GuiScreen.isCtrlKeyDown();
    }

    private Path configKnowledgeRoot() {
        return Minecraft.getMinecraft().mcDataDir.toPath().toAbsolutePath().normalize()
                .resolve("config/modpedia/knowledge");
    }

    private String value(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String enumOrDefault(String value, String[] values, String fallback) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return fallback;
    }

    private String next(String current, String[] values) {
        int index = Arrays.asList(values).indexOf(current);
        return values[(index + 1 + values.length) % values.length];
    }

    private String pretty(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private boolean isTimeoutMessage(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return value.contains("timeout") || value.contains("timed out") || value.contains("超时");
    }

    private static final class TokenHit {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String itemValue;

        private TokenHit(int x, int y, int width, int height, String itemValue) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.itemValue = itemValue;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private static final class RecipeHit {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String recipeValue;

        private RecipeHit(int x, int y, int width, int height, String recipeValue) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.recipeValue = recipeValue;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private static final class SourceHit {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final LegacySourceReference source;

        private SourceHit(int x, int y, int width, int height, LegacySourceReference source) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.source = source;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width
                    && mouseY >= y && mouseY <= y + height;
        }
    }

    private static final class FollowUpHit {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String question;

        private FollowUpHit(int x, int y, int width, int height, String question) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.question = question;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width
                    && mouseY >= y && mouseY <= y + height;
        }
    }

    private static final class SuggestionHit {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String query;

        private SuggestionHit(int x, int y, int width, int height, String query) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.query = query;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width
                    && mouseY >= y && mouseY <= y + height;
        }
    }

    private static final class WrappedLine {
        private final String formattedText;
        private final LegacyMarkdownRenderer.RenderedLine source;

        private WrappedLine(String formattedText, LegacyMarkdownRenderer.RenderedLine source) {
            this.formattedText = formattedText;
            this.source = source;
        }
    }

    private static final class MessageLayout {
        private final LegacyChatMessage message;
        private final int width;
        private final int height;
        private final List<WrappedLine> lines;

        private MessageLayout(LegacyChatMessage message, int width, int height,
                List<WrappedLine> lines) {
            this.message = message;
            this.width = width;
            this.height = height;
            this.lines = lines;
        }
    }

    private static final class ModelOption {
        private final String id;
        private final String owner;

        private ModelOption(String id, String owner) {
            this.id = id == null ? "" : id;
            this.owner = owner == null ? "" : owner;
        }
    }

    private static final class ConversationSummary {
        private final String id;
        private final String title;
        private final int messageCount;

        private ConversationSummary(String id, String title, int messageCount) {
            this.id = id == null ? "" : id;
            this.title = title == null || title.isEmpty() ? "未命名会话" : title;
            this.messageCount = messageCount;
        }
    }

    enum Panel {
        MAIN,
        SETTINGS,
        HISTORY
    }
}

package io.ctyx.modpedia.client;

import io.ctyx.modpedia.knowledge.KnowledgeStatus;
import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 可移动、可缩放且不暂停游戏的半透明助手浮窗。 */
public final class AssistantScreen extends Screen {
    private static final int HEADER_HEIGHT = FloatingAssistantWindow.HEADER_HEIGHT;
    private static final int CONTENT_PADDING = FloatingAssistantWindow.CONTENT_PADDING;
    private static final int INPUT_HEIGHT = FloatingAssistantWindow.INPUT_HEIGHT;
    private static final int RESIZE_HANDLE_SIZE = FloatingAssistantWindow.RESIZE_HANDLE_SIZE;
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int SUBTLE_TEXT_COLOR = 0xFFB8C3D3;
    private static final int ERROR_COLOR = 0xFFFFB4AB;

    private final Screen previousScreen;
    private final AssistantSession session;
    private final SourceNavigator sourceNavigator;
    private final AssistantWindowConfig windowConfig = new AssistantWindowConfig();
    private final java.util.function.Consumer<AssistantUiState> stateListener = this::onStateChanged;

    private WindowBounds bounds;
    private AssistantInput input;
    private AssistantGlassConfig.Style glassStyle = AssistantGlassConfig.load();
    private AssistantUiState currentState;
    private boolean listening;
    private boolean dragging;
    private boolean resizing;
    private WindowBounds.ResizeEdge resizeEdge = WindowBounds.ResizeEdge.NONE;
    private WindowBounds dragStartBounds;
    private int dragStartX;
    private int dragStartY;
    private double scrollOffset;
    private boolean scrollToEnd = true;
    private List<SourceCard> sourceCards = List.of();
    private List<SuggestionHit> suggestionHits = List.of();
    private SourceReference previewSource;
    private String previewStatus = "";
    private Bounds retryBounds;
    private SecondaryPanel secondaryPanel = SecondaryPanel.NONE;
    private Bounds historyDrawerBounds;
    private Bounds historyListBounds;
    private Bounds historyNewBounds;
    private Bounds historyRenameBounds;
    private Bounds historyDeleteBounds;
    private List<HistoryHit> historyHits = List.of();
    private double historyScrollOffset;
    private AiSettingsPanel settingsPanel;
    private final List<AbstractWidget> settingsContentWidgets = new ArrayList<>();
    private final List<AbstractWidget> settingsFooterWidgets = new ArrayList<>();
    private WindowBounds.ResizeEdge cursorEdge = WindowBounds.ResizeEdge.NONE;
    private long cursorHandle;

    public AssistantScreen(Screen previousScreen, AssistantSession session) {
        this(previousScreen, session, source -> {
            // 默认构造器保留给纯 UI 测试，不绑定可选手册模组。
            return false;
        });
    }

    public AssistantScreen(Screen previousScreen, AssistantSession session, SourceNavigator sourceNavigator) {
        super(Component.translatable("screen.modpedia.title"));
        this.previousScreen = previousScreen;
        this.session = session;
        this.sourceNavigator = sourceNavigator;
        this.currentState = session.state();
    }

    @Override
    protected void init() {
        glassStyle = AssistantGlassConfig.load();
        String draft = input == null ? "" : input.getValue();
        if (bounds == null) {
            bounds = windowConfig.load(WindowBounds.defaultFor(width, height));
        }
        bounds = bounds.clampTo(width, height);
        clearWidgets();
        settingsContentWidgets.clear();
        settingsFooterWidgets.clear();

        Bounds inputBounds = inputFieldBounds();
        input = new AssistantInput(
                font,
                inputBounds.left(),
                inputBounds.top(),
                inputBounds.width(),
                inputBounds.height(),
                Component.translatable("screen.modpedia.input_placeholder"),
                Component.translatable("screen.modpedia.input_narration")
        );
        input.setValue(draft);
        input.setValueListener(value -> {
            // 保持输入组件的状态更新，不提前触发模拟会话。
        });
        // 二级页面打开时，主页面输入框只保留为底层状态，不参与绘制和事件分发。
        if (secondaryPanel == SecondaryPanel.NONE) {
            addRenderableWidget(input);
        }
        if (secondaryPanel == SecondaryPanel.SETTINGS) {
            if (settingsPanel == null) {
                settingsPanel = new AiSettingsPanel();
            }
            Bounds settings = secondaryPageBounds();
            settingsPanel.init(this, font, settings.left(), settings.top(), settings.width(), settings.bottom());
            setInitialFocus(settingsPanel.initialFocus());
        } else if (secondaryPanel == SecondaryPanel.NONE) {
            setInitialFocus(input);
        }

        if (!listening) {
            session.addListener(stateListener);
            listening = true;
        }
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (bounds != null) {
            bounds = bounds.clampTo(width, height);
        }
        super.resize(minecraft, width, height);
    }

    @Override
    public void tick() {
        super.tick();
        if (secondaryPanel == SecondaryPanel.NONE && input != null && input.isFocused()) {
            input.setFocused(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderWindow(graphics, mouseX, mouseY);
        // 不调用 super.render，避免 Screen 的底层背景再次覆盖浮窗。
        // 二级页面控件单独在自己的裁剪区域内绘制，避免脱离原窗口。
        if (secondaryPanel == SecondaryPanel.SETTINGS) {
            renderSettingsWidgets(graphics, mouseX, mouseY, partialTick);
        } else if (secondaryPanel == SecondaryPanel.NONE) {
            for (var renderable : renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        if (previewSource != null) {
            renderSourcePreview(graphics);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (previewSource != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            previewSource = null;
            return true;
        }
        if (secondaryPanel == SecondaryPanel.SETTINGS && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeSettingsPanel();
            return true;
        }
        if (secondaryPanel == SecondaryPanel.HISTORY && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeSecondaryPanel();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (input != null && input.isFocused()) {
                input.setFocused(false);
                return true;
            }
            onClose();
            return true;
        }
        if (secondaryPanel == SecondaryPanel.NONE && keyCode == GLFW.GLFW_KEY_ENTER && !hasShiftDown()) {
            if (input != null && input.isFocused()) {
                submitInput();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_1) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (previewSource != null) {
            if (previewCloseBounds().contains(mouseX, mouseY)) {
                previewSource = null;
                return true;
            }
            if (previewOpenBounds().contains(mouseX, mouseY)) {
                boolean opened = sourceNavigator.open(previewSource);
                previewStatus = opened ? "opened" : "unavailable";
                if (opened) {
                    previewSource = null;
                }
                return true;
            }
            if (!previewBounds().contains(mouseX, mouseY)) {
                previewSource = null;
            }
            return true;
        }
        if (historyBounds().contains(mouseX, mouseY)) {
            toggleSecondaryPanel(SecondaryPanel.HISTORY);
            return true;
        }
        if (settingsBounds().contains(mouseX, mouseY)) {
            toggleSecondaryPanel(SecondaryPanel.SETTINGS);
            return true;
        }
        // 主窗口关闭按钮优先于二级页面的点击外部逻辑。
        if (closeBounds().contains(mouseX, mouseY)) {
            onClose();
            return true;
        }

        // 二级页面打开时仍允许拖动和缩放原始窗口；二级页面会随 WindowBounds 同步重排。
        WindowBounds.ResizeEdge edge = resizeEdgeAt(mouseX, mouseY);
        if (edge != WindowBounds.ResizeEdge.NONE) {
            resizing = true;
            resizeEdge = edge;
            dragStartBounds = bounds;
            dragStartX = (int) mouseX;
            dragStartY = (int) mouseY;
            return true;
        }
        if (titleBounds().contains(mouseX, mouseY)) {
            dragging = true;
            dragStartBounds = bounds;
            dragStartX = (int) mouseX;
            dragStartY = (int) mouseY;
            return true;
        }

        if (secondaryPanel == SecondaryPanel.SETTINGS) {
            if (settingsPanel != null && settingsPanel.closeContains(mouseX, mouseY)) {
                closeSettingsPanel();
                return true;
            }
            if (settingsPanel != null && settingsPanel.pageContains(mouseX, mouseY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
            closeSettingsPanel();
            return true;
        }
        if (secondaryPanel == SecondaryPanel.HISTORY) {
            if (historyDrawerBounds != null && historyDrawerBounds.contains(mouseX, mouseY)) {
                if (secondaryPageCloseBounds().contains(mouseX, mouseY)) {
                    closeSecondaryPanel();
                    return true;
                }
                if (historyNewBounds != null && historyNewBounds.contains(mouseX, mouseY)) {
                    session.newConversation();
                    closeSecondaryPanel();
                    scrollToEnd = true;
                    return true;
                }
                if (historyRenameBounds != null && historyRenameBounds.contains(mouseX, mouseY)) {
                    String id = session.activeConversationId();
                    if (!id.isBlank()) {
                        minecraft.setScreen(new ConversationRenameScreen(
                                this,
                                session,
                                id,
                                session.activeConversationTitle()
                        ));
                    }
                    return true;
                }
                if (historyDeleteBounds != null && historyDeleteBounds.contains(mouseX, mouseY)) {
                    String id = session.activeConversationId();
                    if (!id.isBlank()) {
                        session.deleteConversation(id);
                    }
                    return true;
                }
                for (HistoryHit hit : historyHits) {
                    if (hit.bounds().contains(mouseX, mouseY)) {
                        session.selectConversation(hit.summary().id());
                        closeSecondaryPanel();
                        scrollToEnd = true;
                        return true;
                    }
                }
                return true;
            }
            closeSecondaryPanel();
            return true;
        }
        if (sendBounds().contains(mouseX, mouseY)) {
            if (session.isLoading()) {
                session.cancel();
            } else {
                submitInput();
            }
            return true;
        }
        if (retryBounds != null && retryBounds.contains(mouseX, mouseY)) {
            session.retry();
            scrollToEnd = true;
            return true;
        }
        for (SourceCard card : sourceCards) {
            if (card.contains(mouseX, mouseY)) {
                openSource(card.source());
                return true;
            }
        }
        for (SuggestionHit hit : suggestionHits) {
            if (hit.bounds().contains(mouseX, mouseY)) {
                input.setValue(hit.text());
                input.setFocused(true);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && dragStartBounds != null) {
            int nextX = dragStartBounds.x() + (int) mouseX - dragStartX;
            int nextY = dragStartBounds.y() + (int) mouseY - dragStartY;
            bounds = new WindowBounds(nextX, nextY, dragStartBounds.width(), dragStartBounds.height())
                    .clampTo(width, height);
            rebuildWidgets();
            return true;
        }
        if (resizing && dragStartBounds != null) {
            bounds = dragStartBounds.resize(
                    resizeEdge,
                    (int) mouseX - dragStartX,
                    (int) mouseY - dragStartY,
                    width,
                    height
            );
            rebuildWidgets();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasMoving = dragging || resizing;
        dragging = false;
        resizing = false;
        resizeEdge = WindowBounds.ResizeEdge.NONE;
        dragStartBounds = null;
        if (wasMoving) {
            if (bounds != null) {
                windowConfig.save(bounds);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (secondaryPanel == SecondaryPanel.SETTINGS && settingsPanel != null) {
            if (settingsPanel.contentContains(mouseX, mouseY)) {
                settingsPanel.scrollBy(scrollY);
                return true;
            }
            return true;
        }
        if (secondaryPanel == SecondaryPanel.HISTORY) {
            if (historyListBounds != null && historyListBounds.contains(mouseX, mouseY)) {
                historyScrollOffset = Math.max(0, historyScrollOffset - scrollY * 24.0);
                return true;
            }
            return true;
        }
        if (messageBounds().contains(mouseX, mouseY)) {
            scrollOffset -= scrollY * 24.0;
            scrollToEnd = false;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (bounds != null) {
            windowConfig.save(bounds);
        }
        resetCursor();
        minecraft.setScreen(previousScreen);
    }

    @Override
    public void removed() {
        if (listening) {
            session.removeListener(stateListener);
            listening = false;
        }
        if (bounds != null) {
            windowConfig.save(bounds);
        }
        resetCursor();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        updateCursor(resizeEdgeAt(mouseX, mouseY));
        super.mouseMoved(mouseX, mouseY);
    }

    private void renderWindow(GuiGraphics graphics, int mouseX, int mouseY) {
        FloatingAssistantWindow.renderSurface(
                graphics,
                bounds,
                glassStyle,
                FloatingAssistantWindow.prefersOpaqueSurface(minecraft)
        );
        FloatingAssistantWindow.renderHeader(
                graphics,
                font,
                bounds,
                Component.translatable("screen.modpedia.title"),
                statusText(),
                glassStyle,
                TEXT_COLOR,
                SUBTLE_TEXT_COLOR
        );
        renderHeaderActions(graphics, mouseX, mouseY);

        // 二级页面是原助手窗口内容之上的一层；底层消息和输入栏仍先绘制，
        // 页面背景再用不透明表面覆盖，避免两个 Screen/交互层互相穿透。
        drawMessages(graphics);
        drawInputChrome(graphics);
        if (secondaryPanel == SecondaryPanel.HISTORY) {
            renderHistoryPage(graphics, mouseX, mouseY);
        } else if (secondaryPanel == SecondaryPanel.SETTINGS && settingsPanel != null) {
            settingsPanel.render(
                    graphics,
                    glassStyle,
                    FloatingAssistantWindow.prefersOpaqueSurface(minecraft),
                    mouseX,
                    mouseY
            );
        }
        WindowBounds.ResizeEdge edge = resizeEdgeAt(mouseX, mouseY);
        FloatingAssistantWindow.renderResizeHandles(graphics, bounds, edge, glassStyle);
        updateCursor(edge);
    }

    private void drawInputChrome(GuiGraphics graphics) {
        Bounds row = inputRowBounds();
        Bounds send = sendBounds();
        graphics.renderOutline(row.left() - 1, row.top() - 1, row.width() + 2, row.height() + 2, glassStyle.glowInnerColor());
        int color = input != null && input.hasText() && !session.isLoading()
                ? glassStyle.accentColor()
                : SUBTLE_TEXT_COLOR;
        graphics.drawCenteredString(
                font,
                Component.literal(session.isLoading() ? "×" : "↑"),
                send.left() + send.width() / 2,
                send.top() + (send.height() - font.lineHeight) / 2,
                color
        );
    }

    private void drawMessages(GuiGraphics graphics) {
        Bounds area = messageBounds();
        List<ChatMessage> visibleMessages = visibleMessages();
        List<MessageBubble> layouts = MessageList.layout(
                visibleMessages,
                font,
                area.width() - CONTENT_PADDING * 2
        );
        int messageContentHeight = layouts.isEmpty() ? 0 : layouts.get(layouts.size() - 1).bottom();
        int contentHeight = messageContentHeight + (layouts.isEmpty() ? 0 : stateNoticeHeight());
        int maxScroll = Math.max(0, contentHeight - area.height());
        if (scrollToEnd) {
            scrollOffset = maxScroll;
            scrollToEnd = false;
        }
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        sourceCards = new ArrayList<>();
        suggestionHits = new ArrayList<>();
        retryBounds = null;

        graphics.enableScissor(area.left(), area.top(), area.right(), area.bottom());
        if (layouts.isEmpty()) {
            drawEmptyState(graphics, area);
        } else {
            for (MessageBubble layout : layouts) {
                drawMessage(graphics, layout, area, (int) scrollOffset);
            }
            drawStateNotice(graphics, area, messageContentHeight, (int) scrollOffset);
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int trackHeight = Math.max(18, area.height() * area.height() / Math.max(area.height(), contentHeight));
            int trackY = area.top() + (area.height() - trackHeight) * (int) scrollOffset / maxScroll;
            graphics.fill(area.right() - 4, area.top(), area.right() - 2, area.bottom(), 0x2F000000);
            graphics.fill(area.right() - 4, trackY, area.right() - 2, trackY + trackHeight, 0x8F9CC7FF);
        }
    }

    private void drawEmptyState(GuiGraphics graphics, Bounds area) {
        int centerX = area.left() + area.width() / 2;
        int titleY = area.top() + Math.max(12, Math.min(26, area.height() / 3 - 26));
        graphics.drawCenteredString(font, Component.translatable("screen.modpedia.welcome"), centerX, titleY, TEXT_COLOR);
        graphics.drawCenteredString(font, Component.translatable("screen.modpedia.welcome_hint"), centerX, titleY + 18, SUBTLE_TEXT_COLOR);

        String[] suggestions = {
                "如何开始使用这个模组？",
                "这个机器需要什么材料？",
                "帮我查找相关手册内容"
        };
        List<SuggestionHit> hits = new ArrayList<>();
        int y = titleY + 52;
        for (String suggestion : suggestions) {
            if (y + 22 > area.bottom() - 8) {
                break;
            }
            int suggestionWidth = Math.min(area.width() - 24, Math.max(150, font.width(suggestion) + 24));
            int x = centerX - suggestionWidth / 2;
            graphics.fill(
                    x,
                    y,
                    x + suggestionWidth,
                    y + 22,
                    FloatingAssistantWindow.prefersOpaqueSurface(minecraft)
                            ? glassStyle.opaqueAssistantBubbleColor()
                            : glassStyle.assistantBubbleColor()
            );
            graphics.renderOutline(x, y, suggestionWidth, 22, glassStyle.glowInnerColor());
            graphics.drawCenteredString(font, Component.literal(suggestion), centerX, y + 7, SUBTLE_TEXT_COLOR);
            hits.add(new SuggestionHit(new Bounds(x, y, suggestionWidth, 22), suggestion));
            y += 28;
        }
        suggestionHits = hits;
    }

    private void drawMessage(GuiGraphics graphics, MessageBubble layout, Bounds area, int scroll) {
        ChatMessage message = layout.message();
        int y = area.top() + layout.top() - scroll;
        int bubbleX = message.role() == MessageRole.USER
                ? area.right() - CONTENT_PADDING - layout.width()
                : area.left() + CONTENT_PADDING;
        boolean opaqueSurface = FloatingAssistantWindow.prefersOpaqueSurface(minecraft);
        int bubbleColor = opaqueSurface
                ? (message.role() == MessageRole.USER
                ? glassStyle.opaqueUserBubbleColor()
                : glassStyle.opaqueAssistantBubbleColor())
                : (message.role() == MessageRole.USER
                ? glassStyle.userBubbleColor()
                : glassStyle.assistantBubbleColor());
        graphics.fill(bubbleX, y, bubbleX + layout.width(), y + layout.height(), bubbleColor);
        graphics.renderOutline(bubbleX, y, layout.width(), layout.height(), glassStyle.glowInnerColor());

        int textY = y + 7;
        for (var line : layout.lines()) {
            graphics.drawString(font, line, bubbleX + 12, textY, TEXT_COLOR, false);
            textY += font.lineHeight;
        }
        for (SourceReference source : message.sources()) {
            int sourceTop = textY + 3;
            SourceCard card = new SourceCard(
                    source,
                    bubbleX + 8,
                    sourceTop - 2,
                    layout.width() - 16,
                    16
            );
            card.render(
                    graphics,
                    font,
                    opaqueSurface ? glassStyle.opaqueSourceColor() : glassStyle.sourceColor(),
                    glassStyle.accentColor()
            );
            sourceCards = append(sourceCards, card);
            textY += 16;
        }
    }

    private void drawStateNotice(GuiGraphics graphics, Bounds area, int contentHeight, int scroll) {
        int y = area.top() + contentHeight - scroll;
        if (currentState instanceof AssistantUiState.Conversation conversation && conversation.noResult()) {
            graphics.drawString(
                    font,
                    Component.translatable("screen.modpedia.no_result"),
                    area.left() + 12,
                    y,
                    SUBTLE_TEXT_COLOR,
                    false
            );
        } else if (currentState instanceof AssistantUiState.Loading loading) {
            Component phase = phaseComponent(loading.phase());
            graphics.drawString(
                    font,
                    phase,
                    area.left() + 12,
                    y,
                    glassStyle.accentColor(),
                    false
            );
        } else if (currentState instanceof AssistantUiState.Error error) {
            graphics.drawString(font, Component.literal(error.message()), area.left() + 12, y, ERROR_COLOR, false);
            graphics.drawString(
                    font,
                    Component.translatable("screen.modpedia.retry_hint"),
                    area.left() + 12,
                    y + font.lineHeight,
                    SUBTLE_TEXT_COLOR,
                    false
            );
            int retryY = Math.min(area.bottom() - 24, y + font.lineHeight * 2 + 6);
            retryBounds = new Bounds(area.left() + 12, retryY, 64, 20);
            graphics.fill(
                    retryBounds.left(),
                    retryBounds.top(),
                    retryBounds.right(),
                    retryBounds.bottom(),
                    FloatingAssistantWindow.prefersOpaqueSurface(minecraft)
                            ? glassStyle.opaqueAssistantBubbleColor()
                            : glassStyle.assistantBubbleColor()
            );
            graphics.renderOutline(
                    retryBounds.left(),
                    retryBounds.top(),
                    retryBounds.width(),
                    retryBounds.height(),
                    glassStyle.glowInnerColor()
            );
            graphics.drawCenteredString(
                    font,
                    Component.translatable("screen.modpedia.retry"),
                    retryBounds.left() + retryBounds.width() / 2,
                    retryBounds.top() + 6,
                    glassStyle.accentColor()
            );
        }
    }

    private int stateNoticeHeight() {
        if (currentState instanceof AssistantUiState.Error) {
            return font.lineHeight * 3 + 14;
        }
        if (currentState instanceof AssistantUiState.Loading
                || currentState instanceof AssistantUiState.Conversation conversation && conversation.noResult()) {
            return font.lineHeight + 8;
        }
        return 0;
    }

    private List<ChatMessage> visibleMessages() {
        if (!(currentState instanceof AssistantUiState.Loading loading)
                || loading.assistantDraft().isBlank()) {
            return currentState.messages();
        }
        List<ChatMessage> messages = new ArrayList<>(currentState.messages());
        messages.add(new ChatMessage(MessageRole.ASSISTANT, loading.assistantDraft(), List.of()));
        return messages;
    }

    private Component phaseComponent(String phase) {
        if (phase == null || phase.isBlank()) {
            return Component.translatable("screen.modpedia.loading");
        }
        return phase.startsWith("screen.modpedia.")
                ? Component.translatable(phase)
                : Component.literal(phase);
    }

    private void renderHeaderActions(GuiGraphics graphics, int mouseX, int mouseY) {
        drawHeaderAction(graphics, historyBounds(), Component.translatable("screen.modpedia.history"),
                secondaryPanel == SecondaryPanel.HISTORY, mouseX, mouseY);
        drawHeaderAction(graphics, settingsBounds(), Component.translatable("screen.modpedia.settings"),
                secondaryPanel == SecondaryPanel.SETTINGS, mouseX, mouseY);
    }

    private void drawHeaderAction(
            GuiGraphics graphics,
            Bounds area,
            Component label,
            boolean selected,
            int mouseX,
            int mouseY
    ) {
        boolean hovered = area.contains(mouseX, mouseY);
        if (selected || hovered) {
            graphics.fill(area.left(), area.top(), area.right(), area.bottom(), glassStyle.glowInnerColor());
        }
        graphics.drawCenteredString(
                font,
                label,
                area.left() + area.width() / 2,
                area.top() + (area.height() - font.lineHeight) / 2,
                selected || hovered ? TEXT_COLOR : SUBTLE_TEXT_COLOR
        );
    }

    private void renderHistoryPage(GuiGraphics graphics, int mouseX, int mouseY) {
        Bounds page = secondaryPageBounds();
        historyDrawerBounds = page;
        graphics.fill(page.left(), page.top(), page.right(), page.bottom(), glassStyle.opaquePanelColor());
        graphics.renderOutline(page.left(), page.top(), page.width(), page.height(), glassStyle.outlineColor());
        graphics.drawString(font, Component.translatable("screen.modpedia.history"), page.left() + 12, page.top() + 10, TEXT_COLOR, false);
        graphics.drawString(font, Component.literal("×"), page.right() - 22, page.top() + 8, TEXT_COLOR, false);

        historyNewBounds = new Bounds(page.left() + 10, page.top() + 30, Math.max(1, page.width() - 20), 20);
        drawDrawerButton(graphics, historyNewBounds, Component.translatable("screen.modpedia.new_conversation"), mouseX, mouseY);

        historyHits = new ArrayList<>();
        List<ConversationSummary> summaries = session.conversations();
        Bounds list = new Bounds(
                page.left() + 8,
                historyNewBounds.bottom() + 8,
                Math.max(1, page.width() - 16),
                Math.max(1, page.bottom() - 36 - (historyNewBounds.bottom() + 8))
        );
        historyListBounds = list;
        int rowStep = 31;
        int contentHeight = Math.max(list.height(), summaries.size() * rowStep);
        double maxScroll = Math.max(0, contentHeight - list.height());
        historyScrollOffset = Math.max(0, Math.min(maxScroll, historyScrollOffset));
        graphics.enableScissor(list.left(), list.top(), list.right(), list.bottom());
        if (summaries.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.modpedia.no_conversations"), list.left() + 4, list.top() + 4, SUBTLE_TEXT_COLOR, false);
        } else {
            for (int index = 0; index < summaries.size(); index++) {
                ConversationSummary summary = summaries.get(index);
                int y = list.top() + index * rowStep - (int) historyScrollOffset;
                Bounds row = new Bounds(list.left(), y, list.width(), 28);
                boolean selected = summary.id().equals(session.activeConversationId());
                if (selected || row.contains(mouseX, mouseY)) {
                    graphics.fill(row.left(), row.top(), row.right(), row.bottom(), glassStyle.glowInnerColor());
                }
                String title = font.plainSubstrByWidth(summary.title(), Math.max(1, row.width() - 12));
                graphics.drawString(font, Component.literal(title), row.left() + 6, row.top() + 4,
                        selected ? TEXT_COLOR : SUBTLE_TEXT_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.modpedia.message_count", summary.messageCount()),
                        row.left() + 6, row.top() + 16, SUBTLE_TEXT_COLOR, false);
                if (row.bottom() > list.top() && row.top() < list.bottom()) {
                    historyHits.add(new HistoryHit(row, summary));
                }
            }
        }
        graphics.disableScissor();

        historyRenameBounds = new Bounds(page.left() + 8, page.bottom() - 28,
                Math.max(1, (page.width() - 24) / 2), 20);
        historyDeleteBounds = new Bounds(historyRenameBounds.right() + 8, page.bottom() - 28,
                Math.max(1, page.width() - historyRenameBounds.width() - 24), 20);
        drawDrawerButton(graphics, historyRenameBounds, Component.translatable("screen.modpedia.rename"), mouseX, mouseY);
        drawDrawerButton(graphics, historyDeleteBounds, Component.translatable("screen.modpedia.delete"), mouseX, mouseY);
    }

    private void drawDrawerButton(GuiGraphics graphics, Bounds area, Component label, int mouseX, int mouseY) {
        if (area.contains(mouseX, mouseY)) {
            graphics.fill(area.left(), area.top(), area.right(), area.bottom(), glassStyle.glowInnerColor());
        }
        graphics.renderOutline(area.left(), area.top(), area.width(), area.height(), glassStyle.glowInnerColor());
        graphics.drawCenteredString(font, label, area.left() + area.width() / 2,
                area.top() + 6, TEXT_COLOR);
    }

    private void renderSourcePreview(GuiGraphics graphics) {
        Bounds preview = previewBounds();
        graphics.fill(preview.left() - 4, preview.top() - 4, preview.right() + 4, preview.bottom() + 4, 0xB8000000);
        graphics.fill(preview.left(), preview.top(), preview.right(), preview.bottom(), 0xF0222B38);
        graphics.renderOutline(preview.left(), preview.top(), preview.width(), preview.height(), glassStyle.outlineColor());
        graphics.drawString(font, Component.translatable("screen.modpedia.source_preview"), preview.left() + 16, preview.top() + 14, TEXT_COLOR, false);
        graphics.drawString(font, Component.literal("×"), preview.right() - 24, preview.top() + 14, TEXT_COLOR, false);
        graphics.drawString(font, Component.literal(previewSource.title()), preview.left() + 16, preview.top() + 44, glassStyle.accentColor(), false);
        graphics.drawString(font, Component.literal("模组：" + previewSource.sourceMod()), preview.left() + 16, preview.top() + 64, SUBTLE_TEXT_COLOR, false);
        drawWrapped(graphics, "文档 ID：" + previewSource.documentId(), preview.left() + 16, preview.top() + 88, preview.width() - 32, SUBTLE_TEXT_COLOR);
        drawWrapped(graphics, "路径：" + previewSource.sourcePath(), preview.left() + 16, preview.top() + 128, preview.width() - 32, SUBTLE_TEXT_COLOR);
        Bounds open = previewOpenBounds();
        graphics.fill(open.left(), open.top(), open.right(), open.bottom(), glassStyle.assistantBubbleColor());
        graphics.renderOutline(open.left(), open.top(), open.width(), open.height(), glassStyle.glowInnerColor());
        graphics.drawCenteredString(font, Component.translatable("screen.modpedia.open_source"), open.left() + open.width() / 2, open.top() + 6, glassStyle.accentColor());
        Component hint = previewStatus.isBlank()
                ? Component.translatable("screen.modpedia.source_preview_hint")
                : Component.translatable("screen.modpedia.source_navigation_unavailable");
        graphics.drawString(font, hint, preview.left() + 16, open.top() - 14, SUBTLE_TEXT_COLOR, false);
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        int lineY = y;
        for (var line : font.split(Component.literal(text), width)) {
            graphics.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight;
        }
    }

    private String statusText() {
        KnowledgeStatus status = KnowledgeUpdateService.status();
        String text;
        if (status.updating()) {
            text = "知识库更新中 · " + status.sourceCount() + " 来源 · " + status.documentCount() + " 文档";
        } else if (!status.error().isBlank()) {
            text = "知识库状态异常 · " + status.sourceCount() + " 来源 · " + status.documentCount() + " 文档";
        } else if (status.documentCount() > 0) {
            String updated = status.lastUpdated().replace('T', ' ');
            if (updated.length() > 16) {
                updated = updated.substring(0, 16);
            }
            text = "知识库就绪 · " + status.sourceCount() + " 来源 · " + status.documentCount()
                    + " 文档 · " + updated;
        } else {
            text = "知识库尚未生成 · 0 来源 · 0 文档";
        }
        return font == null ? text : font.plainSubstrByWidth(text, Math.max(40, bounds.width() - 150));
    }

    private void submitInput() {
        if (input == null || input.getValue().isBlank() || session.isLoading()) {
            return;
        }
        session.submit(input.getValue());
        input.setValue("");
        input.setFocused(true);
        scrollToEnd = true;
    }

    private void onStateChanged(AssistantUiState state) {
        currentState = state;
        scrollToEnd = true;
    }

    void addSettingsContentWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
        settingsContentWidgets.add(widget);
    }

    void addSettingsFooterWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
        settingsFooterWidgets.add(widget);
    }

    private void renderSettingsWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (settingsPanel == null) {
            return;
        }
        Bounds content = new Bounds(
                settingsPanel.contentLeft(),
                settingsPanel.contentTop(),
                settingsPanel.contentWidth(),
                settingsPanel.contentHeight()
        );
        graphics.enableScissor(content.left(), content.top(), content.right(), content.bottom());
        for (AbstractWidget widget : settingsContentWidgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();
        Bounds page = secondaryPageBounds();
        graphics.enableScissor(page.left(), page.top(), page.right(), page.bottom());
        for (AbstractWidget widget : settingsFooterWidgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();
    }

    void rebuildAssistantWidgets() {
        rebuildWidgets();
    }

    boolean settingsPanelOpen() {
        return secondaryPanel == SecondaryPanel.SETTINGS;
    }

    void closeSettingsPanel() {
        if (secondaryPanel == SecondaryPanel.SETTINGS) {
            closeSecondaryPanel();
        }
    }

    private void openSettingsPanel() {
        secondaryPanel = SecondaryPanel.SETTINGS;
        if (settingsPanel == null) {
            settingsPanel = new AiSettingsPanel();
        }
        rebuildWidgets();
    }

    private void toggleSecondaryPanel(SecondaryPanel panel) {
        secondaryPanel = secondaryPanel == panel ? SecondaryPanel.NONE : panel;
        if (secondaryPanel == SecondaryPanel.HISTORY) {
            historyScrollOffset = 0;
        }
        rebuildWidgets();
    }

    private void closeSecondaryPanel() {
        secondaryPanel = SecondaryPanel.NONE;
        rebuildWidgets();
    }

    private void openSource(SourceReference source) {
        previewSource = source;
        previewStatus = "";
    }

    private WindowBounds.ResizeEdge resizeEdgeAt(double mouseX, double mouseY) {
        if (bounds == null) {
            return WindowBounds.ResizeEdge.NONE;
        }
        int left = bounds.x();
        int top = bounds.y();
        int right = left + bounds.width();
        int bottom = top + bounds.height();
        boolean horizontal = mouseX >= left - RESIZE_HANDLE_SIZE && mouseX <= right + RESIZE_HANDLE_SIZE;
        boolean vertical = mouseY >= top - RESIZE_HANDLE_SIZE && mouseY <= bottom + RESIZE_HANDLE_SIZE;
        if (!horizontal || !vertical) {
            return WindowBounds.ResizeEdge.NONE;
        }
        boolean nearLeft = Math.abs(mouseX - left) <= RESIZE_HANDLE_SIZE;
        boolean nearRight = Math.abs(mouseX - right) <= RESIZE_HANDLE_SIZE;
        boolean nearTop = Math.abs(mouseY - top) <= RESIZE_HANDLE_SIZE;
        boolean nearBottom = Math.abs(mouseY - bottom) <= RESIZE_HANDLE_SIZE;
        if (nearTop && nearLeft) return WindowBounds.ResizeEdge.TOP_LEFT;
        if (nearTop && nearRight) return WindowBounds.ResizeEdge.TOP_RIGHT;
        if (nearBottom && nearLeft) return WindowBounds.ResizeEdge.BOTTOM_LEFT;
        if (nearBottom && nearRight) return WindowBounds.ResizeEdge.BOTTOM_RIGHT;
        if (nearLeft) return WindowBounds.ResizeEdge.LEFT;
        if (nearRight) return WindowBounds.ResizeEdge.RIGHT;
        if (nearTop) return WindowBounds.ResizeEdge.TOP;
        if (nearBottom) return WindowBounds.ResizeEdge.BOTTOM;
        return WindowBounds.ResizeEdge.NONE;
    }

    private void updateCursor(WindowBounds.ResizeEdge edge) {
        if (minecraft == null || edge == cursorEdge) {
            return;
        }
        resetCursor();
        cursorEdge = edge;
        if (edge == WindowBounds.ResizeEdge.NONE) {
            return;
        }
        int shape = switch (edge) {
            case LEFT, RIGHT -> GLFW.GLFW_RESIZE_EW_CURSOR;
            case TOP, BOTTOM -> GLFW.GLFW_RESIZE_NS_CURSOR;
            case TOP_LEFT, BOTTOM_RIGHT -> GLFW.GLFW_RESIZE_NWSE_CURSOR;
            case TOP_RIGHT, BOTTOM_LEFT -> GLFW.GLFW_RESIZE_NESW_CURSOR;
            case NONE -> GLFW.GLFW_ARROW_CURSOR;
        };
        cursorHandle = GLFW.glfwCreateStandardCursor(shape);
        GLFW.glfwSetCursor(minecraft.getWindow().getWindow(), cursorHandle);
    }

    private void resetCursor() {
        if (minecraft != null && minecraft.getWindow() != null) {
            GLFW.glfwSetCursor(minecraft.getWindow().getWindow(), 0L);
        }
        if (cursorHandle != 0L) {
            GLFW.glfwDestroyCursor(cursorHandle);
            cursorHandle = 0L;
        }
        cursorEdge = WindowBounds.ResizeEdge.NONE;
    }

    private Bounds titleBounds() {
        return new Bounds(bounds.x() + 8, bounds.y() + 4, Math.max(24, bounds.width() - 148), HEADER_HEIGHT - 8);
    }

    private Bounds historyBounds() {
        return new Bounds(bounds.x() + bounds.width() - 116, bounds.y() + 4, 38, HEADER_HEIGHT - 8);
    }

    private Bounds settingsBounds() {
        return new Bounds(bounds.x() + bounds.width() - 76, bounds.y() + 4, 38, HEADER_HEIGHT - 8);
    }

    private Bounds secondaryPageBounds() {
        // 二级页面使用原窗口的完整内容宽度，而不是另开一个可能越界的 Screen。
        // 这样在窄窗口和缩放过程中，所有控件都天然锁定在主窗口内部。
        int innerPadding = Math.min(6, Math.max(2, bounds.width() / 24));
        int pageWidth = Math.max(1, bounds.width() - innerPadding * 2);
        int top = bounds.y() + HEADER_HEIGHT;
        int bottom = bounds.y() + bounds.height() - innerPadding;
        return new Bounds(
                bounds.x() + innerPadding,
                top,
                pageWidth,
                Math.max(1, bottom - top)
        );
    }

    private Bounds secondaryPageCloseBounds() {
        Bounds page = secondaryPageBounds();
        return new Bounds(page.right() - 32, page.top(), 32, HEADER_HEIGHT);
    }

    private Bounds closeBounds() {
        return new Bounds(bounds.x() + bounds.width() - 36, bounds.y() + 4, 32, HEADER_HEIGHT - 8);
    }

    private Bounds messageBounds() {
        return new Bounds(
                bounds.x(),
                bounds.y() + HEADER_HEIGHT,
                bounds.width(),
                Math.max(1, bounds.height() - HEADER_HEIGHT - INPUT_HEIGHT)
        );
    }

    private Bounds sendBounds() {
        Bounds row = inputRowBounds();
        return new Bounds(
                row.right() - FloatingAssistantWindow.SEND_BUTTON_SIZE,
                row.top(),
                FloatingAssistantWindow.SEND_BUTTON_SIZE,
                row.height()
        );
    }

    private Bounds inputRowBounds() {
        int height = Math.min(FloatingAssistantWindow.INPUT_FIELD_HEIGHT, Math.max(20, INPUT_HEIGHT - 12));
        int availableWidth = Math.max(112, bounds.width() - CONTENT_PADDING * 2);
        int rowWidth = Math.min(availableWidth, Math.max(112, availableWidth / 2));
        return new Bounds(
                bounds.x() + CONTENT_PADDING,
                bounds.y() + bounds.height() - INPUT_HEIGHT + (INPUT_HEIGHT - height) / 2,
                rowWidth,
                height
        );
    }

    private Bounds inputFieldBounds() {
        Bounds row = inputRowBounds();
        return new Bounds(
                row.left(),
                row.top(),
                Math.max(80, row.width() - FloatingAssistantWindow.SEND_BUTTON_SIZE - FloatingAssistantWindow.INPUT_GAP),
                row.height()
        );
    }

    private Bounds previewBounds() {
        return new Bounds(
                bounds.x() + 18,
                bounds.y() + HEADER_HEIGHT + 18,
                Math.max(120, bounds.width() - 36),
                Math.max(80, bounds.height() - HEADER_HEIGHT - 24)
        );
    }

    private Bounds previewCloseBounds() {
        Bounds preview = previewBounds();
        return new Bounds(preview.right() - 36, preview.top() + 4, 32, HEADER_HEIGHT - 8);
    }

    private Bounds previewOpenBounds() {
        Bounds preview = previewBounds();
        return new Bounds(
                preview.left() + 16,
                preview.bottom() - 34,
                Math.min(112, Math.max(96, preview.width() - 32)),
                20
        );
    }

    private static <T> List<T> append(List<T> original, T value) {
        List<T> copy = new ArrayList<>(original);
        copy.add(value);
        return copy;
    }

    private record Bounds(int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }

        boolean contains(double x, double y) {
            return x >= left && x < right() && y >= top && y < bottom();
        }
    }

    private record SuggestionHit(Bounds bounds, String text) {
    }

    private record HistoryHit(Bounds bounds, ConversationSummary summary) {
    }

    private enum SecondaryPanel {
        NONE,
        HISTORY,
        SETTINGS
    }
}

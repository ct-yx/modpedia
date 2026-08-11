package io.ctyx.modpedia.client;

import io.ctyx.modpedia.knowledge.KnowledgeStatus;
import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 可移动、可缩放且不暂停游戏的半透明助手浮窗。 */
public final class AssistantScreen extends Screen {
    private static final int CONTENT_PADDING = FloatingAssistantWindow.CONTENT_PADDING;
    private static final int INPUT_HEIGHT = FloatingAssistantWindow.INPUT_HEIGHT;
    private static final int COLLAPSED_INPUT_HEIGHT = FloatingAssistantWindow.COLLAPSED_INPUT_HEIGHT;
    private static final int RESIZE_HANDLE_SIZE = FloatingAssistantWindow.RESIZE_HANDLE_SIZE;
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int SUBTLE_TEXT_COLOR = 0xFFB8C3D3;
    private static final int ERROR_COLOR = 0xFFFFB4AB;
    private static final int TARGET_INSERT_WIDTH = 18;

    private final Screen previousScreen;
    private final AssistantSession session;
    private final SourceNavigator sourceNavigator;
    private final AssistantWindowConfig windowConfig = new AssistantWindowConfig();
    private final java.util.function.Consumer<AssistantUiState> stateListener = this::onStateChanged;

    private WindowBounds bounds;
    private AssistantInput input;
    private boolean inputExpanded;
    private boolean targetButtonVisible;
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
    private List<ItemHit> itemHits = List.of();
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
        if (!draft.isBlank()) {
            inputExpanded = true;
        }
        targetButtonVisible = inputExpanded
                && secondaryPanel == SecondaryPanel.NONE
                && JadeTargetStore.current() != null;
        constrainBounds();
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
        // 默认只显示一条窄提示；真正的 EditBox 只在输入区展开后加入控件层。
        if (secondaryPanel == SecondaryPanel.NONE && inputExpanded) {
            addRenderableWidget(input);
        }
        if (secondaryPanel == SecondaryPanel.SETTINGS) {
            if (settingsPanel == null) {
                settingsPanel = new AiSettingsPanel();
            }
            Bounds settings = secondaryPageBounds();
            settingsPanel.init(
                    this,
                    font,
                    glassStyle,
                    settings.left(),
                    settings.top(),
                    settings.width(),
                    settings.bottom()
            );
            setInitialFocus(settingsPanel.initialFocus());
        } else if (secondaryPanel == SecondaryPanel.NONE && inputExpanded) {
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
        boolean shouldShowTargetButton = inputExpanded
                && secondaryPanel == SecondaryPanel.NONE
                && JadeTargetStore.current() != null;
        if (shouldShowTargetButton != targetButtonVisible) {
            targetButtonVisible = shouldShowTargetButton;
            rebuildWidgets();
        }
        if (secondaryPanel == SecondaryPanel.NONE && inputExpanded && input != null && input.isFocused()) {
            input.setFocused(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 配置文件可能来自旧版本，或客户端窗口刚刚完成 GUI 缩放。
        // 每帧先约束一次，避免旧实例把二级页面绘制成全视口页面。
        constrainBounds();
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
            if (inputExpanded && input != null && input.isFocused()) {
                input.setFocused(false);
                if (!input.hasText()) {
                    inputExpanded = false;
                    rebuildWidgets();
                }
                return true;
            }
            onClose();
            return true;
        }
        if (secondaryPanel == SecondaryPanel.NONE && inputExpanded
                && keyCode == GLFW.GLFW_KEY_ENTER && !hasShiftDown()) {
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

        if (secondaryPanel == SecondaryPanel.NONE && !inputExpanded
                && inputTriggerBounds().contains(mouseX, mouseY)) {
            expandInput();
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
        if (inputExpanded && sendBounds().contains(mouseX, mouseY)) {
            if (session.isLoading()) {
                session.cancel();
            } else {
                submitInput();
            }
            return true;
        }
        if (inputExpanded && targetInsertBounds().contains(mouseX, mouseY)) {
            insertLookTarget();
            return true;
        }
        if (retryBounds != null && retryBounds.contains(mouseX, mouseY)) {
            session.retry();
            scrollToEnd = true;
            return true;
        }
        if (messageBounds().contains(mouseX, mouseY)) {
            if (hasShiftDown()) {
                for (ItemHit hit : itemHits) {
                    if (hit.bounds().contains(mouseX, mouseY)) {
                        JeiRecipeNavigator.open(hit.reference().id());
                        return true;
                    }
                }
            }
            for (SourceCard card : sourceCards) {
                if (card.contains(mouseX, mouseY)) {
                    openSourceOrPreview(card.source());
                    return true;
                }
            }
        }
        for (SuggestionHit hit : suggestionHits) {
            if (hit.bounds().contains(mouseX, mouseY)) {
                if (hit.directDocumentId() != null) {
                    input.setValue("");
                    input.setFocused(false);
                    inputExpanded = false;
                    session.showBuiltInGuide(hit.directDocumentId());
                    scrollToEnd = true;
                    rebuildWidgets();
                    return true;
                }
                input.setValue(hit.text());
                inputExpanded = true;
                rebuildWidgets();
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
        if (settingsPanel != null) {
            settingsPanel.cancelPendingModelRequest();
        }
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
                FloatingAssistantWindow.prefersOpaqueSurface(minecraft),
                surfaceInputHeight(),
                headerHeight()
        );
        FloatingAssistantWindow.renderHeader(
                graphics,
                font,
                bounds,
                Component.translatable("screen.modpedia.title"),
                statusText(),
                glassStyle,
                TEXT_COLOR,
                SUBTLE_TEXT_COLOR,
                headerHeight()
        );
        renderHeaderActions(graphics, mouseX, mouseY);

        // 二级页面不是透明抽屉：打开后直接跳过主内容和输入栏的绘制，
        // 只保留主窗口标题栏作为父层。这样即使渲染器/主题改变混合顺序，
        // 欢迎语、建议文字和输入控件也不会出现在二级页面文字之上。
        if (secondaryPanel == SecondaryPanel.NONE) {
            drawMessages(graphics, mouseX, mouseY);
            drawInputChrome(graphics, mouseX, mouseY);
        } else if (secondaryPanel == SecondaryPanel.HISTORY) {
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

    private void drawInputChrome(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!inputExpanded) {
            Bounds trigger = inputTriggerBounds();
            graphics.fill(
                    trigger.left(),
                    trigger.top(),
                    trigger.right(),
                    trigger.bottom(),
                    FloatingAssistantWindow.prefersOpaqueSurface(minecraft)
                            ? glassStyle.opaqueAssistantBubbleColor()
                            : glassStyle.assistantBubbleColor()
            );
            graphics.renderOutline(trigger.left(), trigger.top(), trigger.width(), trigger.height(), glassStyle.glowInnerColor());
            // 折叠态只保留一个小型入口，不再绘制占据底部三分之一宽度的
            // 输入提示文字；点击后才展开真正的输入框。
            graphics.drawCenteredString(
                    font,
                    Component.literal("↑"),
                    trigger.left() + trigger.width() / 2,
                    trigger.top() + Math.max(2, (trigger.height() - font.lineHeight) / 2),
                    SUBTLE_TEXT_COLOR
            );
            return;
        }
        Bounds row = inputRowBounds();
        Bounds send = sendBounds();
        graphics.renderOutline(row.left() - 1, row.top() - 1, row.width() + 2, row.height() + 2, glassStyle.glowInnerColor());
        Bounds targetButton = targetInsertBounds();
        JadeTargetStore.Target target = JadeTargetStore.current();
        if (targetButtonVisible && target != null) {
            boolean hovered = targetButton.contains(mouseX, mouseY);
            graphics.fill(
                    targetButton.left(),
                    targetButton.top(),
                    targetButton.right(),
                    targetButton.bottom(),
                    hovered ? glassStyle.glowInnerColor() : glassStyle.assistantBubbleColor()
            );
            graphics.renderOutline(
                    targetButton.left(),
                    targetButton.top(),
                    targetButton.width(),
                    targetButton.height(),
                    glassStyle.glowInnerColor()
            );
            graphics.drawCenteredString(
                    font,
                    Component.literal("⌖"),
                    targetButton.left() + targetButton.width() / 2,
                    targetButton.top() + Math.max(1, (targetButton.height() - font.lineHeight) / 2),
                    hovered ? TEXT_COLOR : glassStyle.accentColor()
            );
        }
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

    private void drawMessages(GuiGraphics graphics, int mouseX, int mouseY) {
        Bounds area = messageBounds();
        List<ChatMessage> visibleMessages = visibleMessages();
        List<MessageBubble> layouts = MessageList.layout(
                visibleMessages,
                font,
                area.width() - CONTENT_PADDING * 2,
                hasControlDown()
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
        itemHits = new ArrayList<>();
        suggestionHits = new ArrayList<>();
        retryBounds = null;

        graphics.enableScissor(area.left(), area.top(), area.right(), area.bottom());
        if (layouts.isEmpty()) {
            drawEmptyState(graphics, area);
        } else {
            for (MessageBubble layout : layouts) {
                drawMessage(graphics, layout, area, (int) scrollOffset, mouseX, mouseY);
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

        List<WelcomeSuggestion> suggestions = welcomeSuggestions();
        List<SuggestionHit> hits = new ArrayList<>();
        int y = titleY + 52;
        for (WelcomeSuggestion suggestion : suggestions) {
            if (y + 22 > area.bottom() - 8) {
                break;
            }
            int suggestionWidth = Math.min(area.width() - 24, Math.max(150, font.width(suggestion.label()) + 24));
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
            graphics.drawCenteredString(font, suggestion.label(), centerX, y + 7, SUBTLE_TEXT_COLOR);
            hits.add(new SuggestionHit(
                    new Bounds(x, y, suggestionWidth, 22),
                    suggestion.query(),
                    suggestion.directDocumentId()
            ));
            y += 28;
        }
        suggestionHits = hits;
    }

    /**
     * 欢迎页的建议必须是可执行的示例，而不是让模型猜“这个模组”指谁。
     * 第一项固定指向 ModPedia；内容模组示例只在对应模组已加载且能找到注册名称时出现。
     */
    private List<WelcomeSuggestion> welcomeSuggestions() {
        List<WelcomeSuggestion> suggestions = new ArrayList<>();
        suggestions.add(new WelcomeSuggestion(
                Component.translatable("screen.modpedia.suggestion.assistant"),
                Component.translatable("screen.modpedia.suggestion.assistant_query").getString(),
                BuiltInGuide.ASSISTANT_USAGE_DOCUMENT_ID
        ));
        addItemSuggestion(
                suggestions,
                "pneumaticcraft",
                "pneumaticcraft:pressure_tube",
                "screen.modpedia.suggestion.pressure_tube",
                "screen.modpedia.suggestion.pressure_tube_query"
        );
        addItemSuggestion(
                suggestions,
                "ae2",
                "ae2:controller",
                "screen.modpedia.suggestion.ae2_controller",
                "screen.modpedia.suggestion.ae2_controller_query"
        );
        return List.copyOf(suggestions);
    }

    private void addItemSuggestion(
            List<WelcomeSuggestion> suggestions,
            String modId,
            String itemId,
            String labelKey,
            String queryKey
    ) {
        if (!ModList.get().isLoaded(modId)) {
            return;
        }
        String displayName = registeredItemName(itemId);
        if (displayName.isBlank()) {
            return;
        }
        suggestions.add(new WelcomeSuggestion(
                Component.translatable(labelKey, displayName),
                Component.translatable(queryKey, displayName).getString(),
                null
        ));
    }

    private static String registeredItemName(String itemId) {
        try {
            ResourceLocation id = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
            if (item == Items.AIR) {
                return "";
            }
            String name = new net.minecraft.world.item.ItemStack(item).getHoverName().getString().strip();
            return name.equals(itemId) ? "" : name;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void drawMessage(
            GuiGraphics graphics,
            MessageBubble layout,
            Bounds area,
            int scroll,
            int mouseX,
            int mouseY
    ) {
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
        for (MarkdownRenderer.RenderedLine line : layout.lines()) {
            if (line.source().kind() == MarkdownLine.Kind.CODE) {
                graphics.fill(
                        bubbleX + 8,
                        textY - 1,
                        bubbleX + layout.width() - 8,
                        textY + font.lineHeight,
                        0x30152233
                );
            }
            graphics.drawString(font, line.sequence(), bubbleX + 12, textY, TEXT_COLOR, false);
                recordItemHits(line, bubbleX + 12, textY);
            textY += font.lineHeight;
            if (!line.annotations().isEmpty()) {
                int annotationWidth = Math.max(1, layout.width() - 20);
                List<SourceCard> cards = SourceCard.inlineLayout(
                        line.annotations(),
                        font,
                        bubbleX + 10,
                        textY,
                        annotationWidth
                );
                for (SourceCard card : cards) {
                    boolean hovered = card.contains(mouseX, mouseY);
                    card.render(
                            graphics,
                            font,
                            hovered
                                    ? glassStyle.glowInnerColor()
                                    : (opaqueSurface ? glassStyle.opaqueSourceColor() : glassStyle.sourceColor()),
                            hovered ? TEXT_COLOR : glassStyle.accentColor()
                    );
                    sourceCards = append(sourceCards, card);
                }
                textY += SourceCard.inlineHeight(line.annotations(), font, annotationWidth);
            }
        }

        if (layout.showFollowUps()) {
            textY += MessageBubble.sectionLabelGap();
            graphics.drawString(
                    font,
                    Component.translatable("screen.modpedia.follow_up_questions"),
                    bubbleX + 12,
                    textY,
                    SUBTLE_TEXT_COLOR,
                    false
            );
            textY += font.lineHeight + MessageBubble.sectionLabelGap();
            for (int index = 0; index < message.followUpQuestions().size(); index++) {
                String question = message.followUpQuestions().get(index);
                Bounds questionBounds = new Bounds(
                        bubbleX + 8,
                        textY,
                        Math.max(1, layout.width() - 16),
                        MessageBubble.followUpRowHeight()
                );
                boolean hovered = questionBounds.contains(mouseX, mouseY);
                graphics.fill(
                        questionBounds.left(),
                        questionBounds.top(),
                        questionBounds.right(),
                        questionBounds.bottom(),
                        hovered ? glassStyle.glowInnerColor() : glassStyle.assistantBubbleColor()
                );
                graphics.renderOutline(
                        questionBounds.left(),
                        questionBounds.top(),
                        questionBounds.width(),
                        questionBounds.height(),
                        glassStyle.glowInnerColor()
                );
                String label = font.plainSubstrByWidth(
                        "› " + question,
                        Math.max(1, questionBounds.width() - 10)
                );
                graphics.drawString(
                        font,
                        Component.literal(label),
                        questionBounds.left() + 5,
                        questionBounds.top() + Math.max(2, (questionBounds.height() - font.lineHeight) / 2),
                        hovered ? TEXT_COLOR : SUBTLE_TEXT_COLOR,
                        false
                );
                suggestionHits = append(
                        suggestionHits,
                        new SuggestionHit(questionBounds, question, null)
                );
                textY += MessageBubble.followUpRowHeight();
                if (index + 1 < message.followUpQuestions().size()) {
                    textY += MessageBubble.followUpRowGap();
                }
            }
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
            List<FormattedCharSequence> errorLines = font.split(
                    Component.literal(error.message()),
                    Math.max(80, area.width() - 24)
            );
            for (int index = 0; index < errorLines.size(); index++) {
                graphics.drawString(
                        font,
                        errorLines.get(index),
                        area.left() + 12,
                        y + index * font.lineHeight,
                        ERROR_COLOR
                );
            }
            int hintY = y + errorLines.size() * font.lineHeight;
            graphics.drawString(
                    font,
                    Component.translatable("screen.modpedia.retry_hint"),
                    area.left() + 12,
                    hintY,
                    SUBTLE_TEXT_COLOR,
                    false
            );
            int retryY = Math.min(area.bottom() - 24, hintY + font.lineHeight + 6);
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
            Bounds area = messageBounds();
            int lines = font.split(
                    Component.literal(((AssistantUiState.Error) currentState).message()),
                    Math.max(80, area.width() - 24)
            ).size();
            return font.lineHeight * (Math.max(1, lines) + 2) + 14;
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
        AssistantSecondaryLayout.Rect pageRect = toLayoutRect(page);
        AssistantSecondaryLayout.History layout = AssistantSecondaryLayout.history(pageRect, font.lineHeight);
        historyNewBounds = fromLayoutRect(layout.newConversation());
        historyListBounds = fromLayoutRect(layout.list());
        historyRenameBounds = fromLayoutRect(layout.rename());
        historyDeleteBounds = fromLayoutRect(layout.delete());

        // 历史页是主窗口内的完整不透明层；scissor 同时约束文字、按钮和
        // 后续新增控件，底层欢迎语和输入框不会从页面边缘穿出。
        graphics.enableScissor(page.left(), page.top(), page.right(), page.bottom());
        graphics.fill(page.left(), page.top(), page.right(), page.bottom(), glassStyle.opaquePanelColor());
        graphics.renderOutline(page.left(), page.top(), page.width(), page.height(), glassStyle.outlineColor());
        int pagePadding = Math.max(6, (int) Math.round(12 * layout.scale()));
        graphics.drawString(font, Component.translatable("screen.modpedia.history"),
                page.left() + pagePadding, page.top() + Math.max(4, (int) Math.round(8 * layout.scale())),
                TEXT_COLOR, false);
        graphics.drawString(font, Component.literal("×"),
                page.right() - Math.max(16, (int) Math.round(22 * layout.scale())),
                page.top() + Math.max(4, (int) Math.round(6 * layout.scale())), TEXT_COLOR, false);

        drawDrawerButton(graphics, historyNewBounds, Component.translatable("screen.modpedia.new_conversation"), mouseX, mouseY);

        historyHits = new ArrayList<>();
        List<ConversationSummary> summaries = session.conversations();
        Bounds list = historyListBounds;
        int rowHeight = Math.max(font.lineHeight * 2 + 4, (int) Math.round(28 * layout.scale()));
        int rowStep = Math.max(rowHeight + 3, (int) Math.round(31 * layout.scale()));
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
                Bounds row = new Bounds(list.left(), y, list.width(), rowHeight);
                boolean selected = summary.id().equals(session.activeConversationId());
                if (selected || row.contains(mouseX, mouseY)) {
                    graphics.fill(row.left(), row.top(), row.right(), row.bottom(), glassStyle.glowInnerColor());
                }
                String title = font.plainSubstrByWidth(summary.title(), Math.max(1, row.width() - 12));
                int rowPadding = Math.max(3, (int) Math.round(5 * layout.scale()));
                graphics.drawString(font, Component.literal(title), row.left() + rowPadding, row.top() + rowPadding,
                        selected ? TEXT_COLOR : SUBTLE_TEXT_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.modpedia.message_count", summary.messageCount()),
                        row.left() + rowPadding, row.top() + rowPadding + font.lineHeight + 1,
                        SUBTLE_TEXT_COLOR, false);
                if (row.bottom() > list.top() && row.top() < list.bottom()) {
                    Bounds hitBounds = fromLayoutRect(AssistantSecondaryLayout.clip(toLayoutRect(row), toLayoutRect(list)));
                    if (hitBounds != null) {
                        historyHits.add(new HistoryHit(hitBounds, summary));
                    }
                }
            }
        }
        graphics.disableScissor();

        drawDrawerButton(graphics, historyRenameBounds, Component.translatable("screen.modpedia.rename"), mouseX, mouseY);
        drawDrawerButton(graphics, historyDeleteBounds, Component.translatable("screen.modpedia.delete"), mouseX, mouseY);
        graphics.disableScissor();
    }

    private void drawDrawerButton(GuiGraphics graphics, Bounds area, Component label, int mouseX, int mouseY) {
        if (area.contains(mouseX, mouseY)) {
            graphics.fill(area.left(), area.top(), area.right(), area.bottom(), glassStyle.glowInnerColor());
        }
        graphics.renderOutline(area.left(), area.top(), area.width(), area.height(), glassStyle.glowInnerColor());
        graphics.drawCenteredString(font, label, area.left() + area.width() / 2,
                area.top() + Math.max(0, (area.height() - font.lineHeight) / 2), TEXT_COLOR);
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
        Bounds page = secondaryPageBounds();
        graphics.enableScissor(page.left(), page.top(), page.right(), page.bottom());
        Bounds content = new Bounds(
                settingsPanel.contentLeft(),
                settingsPanel.contentTop(),
                settingsPanel.contentWidth(),
                settingsPanel.contentHeight()
        );
        graphics.enableScissor(content.left(), content.top(), content.right(), content.bottom());
        for (AbstractWidget widget : settingsContentWidgets) {
            boolean visible = settingsPanel.contentWidgetVisible(widget);
            widget.visible = visible;
            if (visible) {
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();
        for (AbstractWidget widget : settingsFooterWidgets) {
            widget.visible = true;
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
        if (secondaryPanel == SecondaryPanel.SETTINGS
                && (panel != SecondaryPanel.SETTINGS || secondaryPanel == panel)
                && settingsPanel != null) {
            settingsPanel.cancelPendingModelRequest();
        }
        secondaryPanel = secondaryPanel == panel ? SecondaryPanel.NONE : panel;
        if (secondaryPanel == SecondaryPanel.HISTORY) {
            historyScrollOffset = 0;
        }
        rebuildWidgets();
    }

    private void closeSecondaryPanel() {
        if (secondaryPanel == SecondaryPanel.SETTINGS && settingsPanel != null) {
            settingsPanel.cancelPendingModelRequest();
        }
        secondaryPanel = SecondaryPanel.NONE;
        rebuildWidgets();
    }

    private void openSource(SourceReference source) {
        previewSource = source;
        previewStatus = "";
    }

    /** 正文标注区是直接跳转按钮；目标暂时不可用时才回退到来源预览。 */
    private void openSourceOrPreview(SourceReference source) {
        if (sourceNavigator.open(source)) {
            previewSource = null;
            return;
        }
        previewSource = source;
        previewStatus = "unavailable";
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
        int headerHeight = headerHeight();
        int left = bounds.x() + 8;
        int width = Math.max(16, FloatingAssistantWindow.headerActionsStart(bounds) - left - 4);
        return new Bounds(left, bounds.y() + 4, width, Math.max(1, headerHeight - 8));
    }

    private Bounds historyBounds() {
        int headerHeight = headerHeight();
        int width = FloatingAssistantWindow.headerActionWidth(bounds.width());
        int left = FloatingAssistantWindow.headerActionsStart(bounds);
        return new Bounds(left, bounds.y() + 4, width, Math.max(1, headerHeight - 8));
    }

    private Bounds settingsBounds() {
        int headerHeight = headerHeight();
        int width = FloatingAssistantWindow.headerActionWidth(bounds.width());
        int left = historyBounds().right() + FloatingAssistantWindow.headerActionGap();
        return new Bounds(left, bounds.y() + 4, width, Math.max(1, headerHeight - 8));
    }

    private Bounds secondaryPageBounds() {
        constrainBounds();
        return fromLayoutRect(AssistantSecondaryLayout.page(bounds, headerHeight()));
    }

    private int headerHeight() {
        return FloatingAssistantWindow.headerHeight(bounds);
    }

    private Bounds secondaryPageCloseBounds() {
        Bounds page = secondaryPageBounds();
        int headerHeight = headerHeight();
        int closeWidth = FloatingAssistantWindow.headerCloseWidth(page.width());
        return new Bounds(page.right() - closeWidth, page.top(), closeWidth,
                Math.min(headerHeight, page.height()));
    }

    private Bounds closeBounds() {
        int headerHeight = headerHeight();
        int closeWidth = FloatingAssistantWindow.headerCloseWidth(bounds.width());
        return new Bounds(bounds.x() + bounds.width() - closeWidth, bounds.y() + 4,
                closeWidth, Math.max(1, headerHeight - 8));
    }

    private Bounds messageBounds() {
        int headerHeight = headerHeight();
        return new Bounds(
                bounds.x(),
                bounds.y() + headerHeight,
                bounds.width(),
                Math.max(1, bounds.height() - headerHeight - inputAreaHeight())
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
        int height = inputExpanded
                ? FloatingAssistantWindow.INPUT_FIELD_HEIGHT
                : COLLAPSED_INPUT_HEIGHT;
        int areaHeight = inputAreaHeight();
        int availableWidth = Math.max(96, bounds.width() - CONTENT_PADDING * 2);
        int rowWidth = inputExpanded
                ? Math.min(availableWidth, Math.max(96, availableWidth / 2))
                : Math.min(availableWidth, FloatingAssistantWindow.COLLAPSED_INPUT_WIDTH);
        return new Bounds(
                inputExpanded
                        ? bounds.x() + CONTENT_PADDING
                        : bounds.x() + bounds.width() - CONTENT_PADDING - rowWidth,
                inputExpanded
                        ? bounds.y() + bounds.height() - areaHeight
                        + (areaHeight - height) / 2
                        : bounds.y() + bounds.height() - CONTENT_PADDING - height,
                rowWidth,
                height
        );
    }

    private Bounds inputTriggerBounds() {
        Bounds row = inputRowBounds();
        return new Bounds(row.left(), row.top(), Math.max(1, row.width()), row.height());
    }

    private int inputAreaHeight() {
        // 折叠态只为小触发器保留一块内容安全区，避免它盖住最后一条消息；
        // 这块区域没有背景带，真正释放的是整条底栏的占用。
        return inputExpanded ? INPUT_HEIGHT : COLLAPSED_INPUT_HEIGHT + CONTENT_PADDING;
    }

    private int surfaceInputHeight() {
        // 折叠态不绘制整条底栏，只绘制消息区上的小触发器。
        return inputExpanded ? INPUT_HEIGHT : 0;
    }

    private void expandInput() {
        inputExpanded = true;
        rebuildWidgets();
        if (input != null) {
            input.setFocused(true);
        }
    }

    private void constrainBounds() {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (bounds == null) {
            bounds = windowConfig.load(WindowBounds.defaultFor(width, height));
        }
        bounds = bounds.clampTo(width, height);
    }

    private AssistantSecondaryLayout.Rect toLayoutRect(Bounds value) {
        return value == null ? null : new AssistantSecondaryLayout.Rect(
                value.left(), value.top(), value.width(), value.height());
    }

    private Bounds fromLayoutRect(AssistantSecondaryLayout.Rect value) {
        return value == null ? null : new Bounds(value.left(), value.top(), value.width(), value.height());
    }

    private Bounds inputFieldBounds() {
        Bounds row = inputRowBounds();
        int targetWidth = targetButtonVisible ? TARGET_INSERT_WIDTH + FloatingAssistantWindow.INPUT_GAP : 0;
        return new Bounds(
                row.left(),
                row.top(),
                Math.max(1, row.width() - FloatingAssistantWindow.SEND_BUTTON_SIZE
                        - FloatingAssistantWindow.INPUT_GAP - targetWidth),
                row.height()
        );
    }

    private Bounds targetInsertBounds() {
        if (!targetButtonVisible || !inputExpanded || secondaryPanel != SecondaryPanel.NONE) {
            return new Bounds(0, 0, 0, 0);
        }
        Bounds send = sendBounds();
        return new Bounds(
                send.left() - FloatingAssistantWindow.INPUT_GAP - TARGET_INSERT_WIDTH,
                send.top(),
                TARGET_INSERT_WIDTH,
                send.height()
        );
    }

    private void insertLookTarget() {
        if (input == null) {
            return;
        }
        JadeTargetStore.Target target = JadeTargetStore.current();
        if (target == null || target.itemId().isBlank()) {
            return;
        }
        String token = "[[item:" + target.itemId() + "|" + target.displayName() + "]]";
        String value = input.getValue();
        input.setValue(value.isBlank() ? token : value + " " + token);
        input.setFocused(true);
    }

    private void recordItemHits(
            MarkdownRenderer.RenderedLine line,
            int textLeft,
            int textTop
    ) {
        if (line.items().isEmpty() || line.source().kind() == MarkdownLine.Kind.CODE) {
            return;
        }
        String text = line.source().text();
        int searchFrom = 0;
        for (ItemReference reference : line.items()) {
            String display = reference.displayText();
            if (display.isBlank()) {
                continue;
            }
            int start = text.indexOf(display, searchFrom);
            if (start < 0) {
                start = text.indexOf(display);
            }
            if (start < 0) {
                continue;
            }
            int left = textLeft + font.width(text.substring(0, start));
            Bounds hit = new Bounds(left, textTop, Math.max(1, font.width(display)), font.lineHeight);
            itemHits = append(itemHits, new ItemHit(hit, reference));
            searchFrom = start + display.length();
        }
    }

    private Bounds previewBounds() {
        int headerHeight = headerHeight();
        return new Bounds(
                bounds.x() + 18,
                bounds.y() + headerHeight + 18,
                Math.max(120, bounds.width() - 36),
                Math.max(80, bounds.height() - headerHeight - 24)
        );
    }

    private Bounds previewCloseBounds() {
        int headerHeight = headerHeight();
        Bounds preview = previewBounds();
        return new Bounds(preview.right() - 36, preview.top() + 4, 32, Math.max(1, headerHeight - 8));
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

    private record SuggestionHit(Bounds bounds, String text, String directDocumentId) {
    }

    private record WelcomeSuggestion(Component label, String query, String directDocumentId) {
    }

    private record HistoryHit(Bounds bounds, ConversationSummary summary) {
    }

    private record ItemHit(Bounds bounds, ItemReference reference) {
    }

    private enum SecondaryPanel {
        NONE,
        HISTORY,
        SETTINGS
    }
}

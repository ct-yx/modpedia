package io.ctyx.modpedia.client;

import io.ctyx.modpedia.knowledge.KnowledgeStatus;
import io.ctyx.modpedia.knowledge.KnowledgeUpdateService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
    private Bounds retryBounds;
    private WindowBounds.ResizeEdge cursorEdge = WindowBounds.ResizeEdge.NONE;
    private long cursorHandle;

    public AssistantScreen(Screen previousScreen, AssistantSession session) {
        this(previousScreen, session, source -> {
            // 阶段四先在当前浮窗内预览；后续可注入 Patchouli/GuideME 跳转适配器。
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
        addRenderableWidget(input);
        setInitialFocus(input);

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
        if (input != null && input.isFocused()) {
            input.setFocused(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!FloatingAssistantWindow.prefersOpaqueSurface(minecraft)) {
            // 只将模糊副本裁剪到窗口区域，窗口外恢复原始清晰画面。
            ModernUiBridge.renderLocalizedBackdrop(graphics, bounds, width, height);
        }
        renderWindow(graphics, mouseX, mouseY);
        // 不调用 super.render：Screen.render 会再次绘制背景。ModernUI 的第二次
        // 背景绘制会把刚画好的标题、消息和边框一起送进模糊链，只有最后的
        // EditBox 仍然清晰。这里直接绘制已注册的控件，保持层级为：
        // 游戏视角 -> 背景模糊 -> 浮窗 -> 浮窗文字 -> 输入控件。
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (input != null && input.isFocused()) {
                input.setFocused(false);
                return true;
            }
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && !hasShiftDown()) {
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
            if (!previewBounds().contains(mouseX, mouseY)) {
                previewSource = null;
            }
            return true;
        }
        if (closeBounds().contains(mouseX, mouseY)) {
            onClose();
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
        ModernUiBridge.endAssistantScreen();
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

        drawMessages(graphics);
        drawInputChrome(graphics);
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
        List<MessageBubble> layouts = MessageList.layout(
                currentState.messages(),
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
        } else if (currentState instanceof AssistantUiState.Loading) {
            graphics.drawString(
                    font,
                    Component.translatable("screen.modpedia.loading"),
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
        graphics.drawString(font, Component.translatable("screen.modpedia.source_preview_hint"), preview.left() + 16, preview.bottom() - 24, SUBTLE_TEXT_COLOR, false);
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
        return font == null ? text : font.plainSubstrByWidth(text, Math.max(40, bounds.width() - 72));
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

    private void openSource(SourceReference source) {
        previewSource = source;
        sourceNavigator.open(source);
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
        return new Bounds(bounds.x() + 8, bounds.y() + 4, bounds.width() - 48, HEADER_HEIGHT - 8);
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
                Math.max(160, bounds.width() - 36),
                Math.max(120, bounds.height() - HEADER_HEIGHT - 24)
        );
    }

    private Bounds previewCloseBounds() {
        Bounds preview = previewBounds();
        return new Bounds(preview.right() - 36, preview.top() + 4, 32, HEADER_HEIGHT - 8);
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
}

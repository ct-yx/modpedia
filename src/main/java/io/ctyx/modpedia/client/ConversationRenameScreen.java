package io.ctyx.modpedia.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** 历史抽屉使用的轻量会话重命名对话框。 */
public final class ConversationRenameScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 132;
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int SUBTLE_COLOR = 0xFFB8C3D3;
    private static final int PANEL_COLOR = 0xEA161F2D;
    private static final int ACCENT_COLOR = 0xFF78B7FF;

    private final Screen previousScreen;
    private final AssistantSession session;
    private final String conversationId;
    private final String originalTitle;
    private EditBox titleInput;

    public ConversationRenameScreen(
            Screen previousScreen,
            AssistantSession session,
            String conversationId,
            String originalTitle
    ) {
        super(Component.translatable("screen.modpedia.rename_conversation"));
        this.previousScreen = previousScreen;
        this.session = session;
        this.conversationId = conversationId;
        this.originalTitle = originalTitle == null ? "" : originalTitle;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = Math.max(8, (height - PANEL_HEIGHT) / 2);
        titleInput = new EditBox(
                font,
                left + 20,
                top + 48,
                PANEL_WIDTH - 40,
                20,
                Component.translatable("screen.modpedia.conversation_title")
        );
        titleInput.setMaxLength(80);
        titleInput.setValue(originalTitle);
        addRenderableWidget(titleInput);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.modpedia.save"),
                        ignored -> save())
                .bounds(left + 20, top + 92, 100, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"),
                        ignored -> closeDialog())
                .bounds(left + PANEL_WIDTH - 120, top + 92, 100, 20)
                .build());
        setInitialFocus(titleInput);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int left = (width - PANEL_WIDTH) / 2;
        int top = Math.max(8, (height - PANEL_HEIGHT) / 2);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_COLOR);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, ACCENT_COLOR);
        graphics.drawString(font, title, left + 20, top + 17, TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("screen.modpedia.rename_hint"),
                left + 20, top + 32, SUBTLE_COLOR, false);
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeDialog();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && titleInput != null && titleInput.isFocused()) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void save() {
        if (titleInput != null) {
            session.renameConversation(conversationId, titleInput.getValue());
        }
        closeDialog();
    }

    private void closeDialog() {
        minecraft.setScreen(previousScreen);
    }
}

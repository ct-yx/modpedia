package io.ctyx.modpedia.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 助手输入组件，保持为单行以减少浮窗底部占用。 */
public final class AssistantInput extends EditBox {
    public static final int CHARACTER_LIMIT = 2000;

    public AssistantInput(
            Font font,
            int x,
            int y,
            int width,
            int height,
            Component placeholder,
            Component narration
    ) {
        super(font, x, y, width, height, narration);
        setHint(placeholder);
        setMaxLength(CHARACTER_LIMIT);
    }

    public boolean hasText() {
        return !getValue().isBlank();
    }

    public void setValueListener(Consumer<String> valueListener) {
        setResponder(valueListener);
    }
}

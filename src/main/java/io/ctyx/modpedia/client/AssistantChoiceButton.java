package io.ctyx.modpedia.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** 使用助手按钮材质的循环选择控件。 */
final class AssistantChoiceButton<T> extends AssistantPanelButton {
    private final Component label;
    private final List<T> values;
    private final Function<T, Component> valueLabel;
    private final Consumer<T> onValueChange;
    private T value;

    AssistantChoiceButton(
            Font font,
            AssistantGlassConfig.Style style,
            int x,
            int y,
            int width,
            int height,
            Component label,
            List<T> values,
            T initialValue,
            Function<T, Component> valueLabel,
            Consumer<T> onValueChange
    ) {
        super(font, style, x, y, width, height, Component.empty(), () -> {
        });
        this.label = label;
        this.values = List.copyOf(values);
        this.value = initialValue;
        this.valueLabel = valueLabel;
        this.onValueChange = onValueChange;
    }

    T getValue() {
        return value;
    }

    void setValue(T value) {
        if (values.contains(value)) {
            this.value = value;
        }
    }

    @Override
    protected Component displayMessage() {
        return Component.literal(label.getString() + " · " + valueLabel.apply(value).getString());
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!active || values.isEmpty()) {
            return;
        }
        int index = values.indexOf(value);
        value = values.get((index + 1) % values.size());
        onValueChange.accept(value);
    }
}

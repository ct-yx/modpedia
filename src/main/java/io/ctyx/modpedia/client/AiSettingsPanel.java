package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ai.AiClient;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ai.AiSettingsStore;
import io.ctyx.modpedia.ai.SearchIntensity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.Arrays;

/** 设置抽屉；与历史抽屉共用 AssistantScreen 的同一渲染层和输入隔离。 */
final class AiSettingsPanel {
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int SUBTLE_COLOR = 0xFFB8C3D3;
    private static final int ERROR_COLOR = 0xFFFFB4AB;

    private final AiSettingsStore settingsStore = AiSettingsStore.runtime();
    private AssistantScreen owner;
    private Font font;
    private int left;
    private int top;
    private int width;
    private int bottom;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
    private EditBox maxRounds;
    private EditBox maxResults;
    private EditBox maxContextChars;
    private EditBox timeoutSeconds;
    private CycleButton<SearchIntensity> intensity;
    private CycleButton<Boolean> streaming;
    private String status = "";
    private boolean statusError;
    private boolean testing;

    void init(AssistantScreen owner, Font font, int left, int top, int width, int bottom) {
        AiSettings values = endpoint == null ? settingsStore.load() : readSettings();
        this.owner = owner;
        this.font = font;
        this.left = left;
        this.top = top;
        this.width = width;
        this.bottom = bottom;

        int pad = 10;
        int fieldLeft = left + pad;
        int fieldWidth = Math.max(80, width - pad * 2);
        endpoint = textField(fieldLeft, top + 39, fieldWidth, values.endpoint(),
                "screen.modpedia.ai_endpoint_hint", false);
        model = textField(fieldLeft, top + 76, fieldWidth, values.model(),
                "screen.modpedia.ai_model_hint", false);
        apiKey = textField(fieldLeft, top + 113, fieldWidth, values.apiKey(),
                "screen.modpedia.ai_api_key_hint", true);
        apiKey.setFormatter((value, cursor) -> FormattedCharSequence.forward(
                "•".repeat(value.codePointCount(0, value.length())), Style.EMPTY
        ));

        intensity = CycleButton.<SearchIntensity>builder(this::intensityLabel)
                .withValues(Arrays.asList(SearchIntensity.values()))
                .withInitialValue(values.intensity())
                .create(fieldLeft, top + 150, fieldWidth,
                        20,
                        Component.translatable("screen.modpedia.ai_search_intensity"),
                        (button, selected) -> setStatus("自定义参数仅在“自定义”档位生效。", false));
        streaming = CycleButton.booleanBuilder(
                        Component.translatable("screen.modpedia.enabled"),
                        Component.translatable("screen.modpedia.disabled"))
                .withInitialValue(values.streaming())
                .create(fieldLeft, top + 187, fieldWidth, 20,
                        Component.translatable("screen.modpedia.ai_sse"));

        int advancedTop = Math.max(top + 224, bottom - 110);
        int smallGap = 6;
        int smallWidth = Math.max(32, (fieldWidth - smallGap) / 2);
        maxRounds = textField(fieldLeft, advancedTop + 24, smallWidth,
                Integer.toString(values.maxRounds()), "screen.modpedia.ai_max_rounds_hint", false);
        maxResults = textField(fieldLeft + smallWidth + smallGap, advancedTop + 24, smallWidth,
                Integer.toString(values.maxResults()), "screen.modpedia.ai_max_results_hint", false);
        maxContextChars = textField(fieldLeft, advancedTop + 60, smallWidth,
                Integer.toString(values.maxContextChars()), "screen.modpedia.ai_context_chars_hint", false);
        timeoutSeconds = textField(fieldLeft + smallWidth + smallGap, advancedTop + 60, smallWidth,
                Integer.toString(values.timeoutSeconds()), "screen.modpedia.ai_timeout_hint", false);

        owner.addSettingsWidget(endpoint);
        owner.addSettingsWidget(model);
        owner.addSettingsWidget(apiKey);
        owner.addSettingsWidget(intensity);
        owner.addSettingsWidget(streaming);
        owner.addSettingsWidget(maxRounds);
        owner.addSettingsWidget(maxResults);
        owner.addSettingsWidget(maxContextChars);
        owner.addSettingsWidget(timeoutSeconds);
        int buttonWidth = Math.max(24, (fieldWidth - 18) / 4);
        owner.addSettingsWidget(Button.builder(
                        Component.translatable("screen.modpedia.save"),
                        ignored -> saveSettings())
                .bounds(fieldLeft, bottom - 26, buttonWidth, 20)
                .build());
        owner.addSettingsWidget(Button.builder(
                        Component.translatable("screen.modpedia.test_connection"),
                        ignored -> testConnection())
                .bounds(fieldLeft + buttonWidth + 6,
                        bottom - 26,
                        buttonWidth,
                        20)
                .build());
        owner.addSettingsWidget(Button.builder(
                        Component.translatable("screen.modpedia.restore_defaults"),
                        ignored -> restoreDefaults())
                .bounds(fieldLeft + (buttonWidth + 6) * 2,
                        bottom - 26,
                        buttonWidth,
                        20)
                .build());
        owner.addSettingsWidget(Button.builder(
                        Component.translatable("gui.cancel"),
                        ignored -> owner.closeSettingsPanel())
                .bounds(fieldLeft + (buttonWidth + 6) * 3,
                        bottom - 26,
                        Math.max(1, fieldWidth - (buttonWidth + 6) * 3),
                        20)
                .build());
    }

    void render(GuiGraphics graphics, AssistantGlassConfig.Style style, boolean opaque, int mouseX, int mouseY) {
        int right = left + width;
        int height = Math.max(1, bottom - top);
        graphics.fill(left, top, right, bottom, opaque ? style.opaquePanelColor() : style.panelColor());
        graphics.renderOutline(left, top, width, height, style.outlineColor());
        graphics.drawString(font, Component.translatable("screen.modpedia.ai_settings"), left + 10, top + 9, TEXT_COLOR, false);
        graphics.drawString(font, Component.literal("×"), right - 22, top + 8, TEXT_COLOR, false);
        drawLabel(graphics, "screen.modpedia.ai_endpoint", left + 10, top + 27);
        drawLabel(graphics, "screen.modpedia.ai_model", left + 10, top + 64);
        drawLabel(graphics, "screen.modpedia.ai_api_key", left + 10, top + 101);
        drawLabel(graphics, "screen.modpedia.ai_search_intensity", left + 10, top + 138);
        drawLabel(graphics, "screen.modpedia.ai_sse", left + 10, top + 175);

        int advancedTop = Math.max(top + 224, bottom - 110);
        drawLabel(graphics, "screen.modpedia.ai_advanced", left + 10, advancedTop);
        drawLabel(graphics, "screen.modpedia.ai_max_rounds", left + 10, advancedTop + 13);
        drawLabel(graphics, "screen.modpedia.ai_max_results", left + width / 2 + 3, advancedTop + 13);
        drawLabel(graphics, "screen.modpedia.ai_context_chars", left + 10, advancedTop + 49);
        drawLabel(graphics, "screen.modpedia.ai_timeout", left + width / 2 + 3, advancedTop + 49);
        if (!status.isBlank()) {
            int statusY = Math.max(top + 205, advancedTop - 15);
            graphics.drawString(font, Component.literal(status), left + 10, statusY,
                    statusError ? ERROR_COLOR : style.accentColor(), false);
        }
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < bottom;
    }

    boolean closeContains(double mouseX, double mouseY) {
        return mouseX >= left + width - 32 && mouseX < left + width
                && mouseY >= top && mouseY < top + 30;
    }

    EditBox initialFocus() {
        return endpoint;
    }

    private EditBox textField(int x, int y, int fieldWidth, String value, String hintKey, boolean secret) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20, Component.translatable(hintKey));
        field.setValue(value == null ? "" : value);
        field.setMaxLength(secret ? 512 : 512);
        field.setHint(Component.translatable(hintKey));
        field.setBordered(true);
        field.setTextColor(TEXT_COLOR);
        return field;
    }

    private AiSettings readSettings() {
        SearchIntensity selected = intensity == null ? SearchIntensity.STANDARD : intensity.getValue();
        return new AiSettings(
                value(endpoint),
                value(model),
                value(apiKey),
                streaming == null || streaming.getValue(),
                selected,
                parseInt(maxRounds, selected.rounds()),
                parseInt(maxResults, selected.results()),
                parseInt(maxContextChars, selected.contextChars()),
                parseInt(timeoutSeconds, 90)
        );
    }

    private void saveSettings() {
        settingsStore.save(readSettings());
        setStatus("已保存。", false);
    }

    private void testConnection() {
        AiSettings values = readSettings();
        if (!values.configured()) {
            setStatus("请先填写 API 地址和模型名称。", true);
            return;
        }
        testing = true;
        setStatus("正在测试连接……", false);
        AiClient.testConnection(values, result -> Minecraft.getInstance().execute(() -> {
            if (!owner.settingsPanelOpen() || !testing) {
                return;
            }
            testing = false;
            String message = result.message() == null || result.message().isBlank()
                    ? "未知错误"
                    : result.message();
            if (!values.effectiveApiKey().isBlank()) {
                message = message.replace(values.effectiveApiKey(), "[已隐藏密钥]");
            }
            setStatus(result.failed() ? "连接失败：" + message : message, result.failed());
        }));
    }

    private void restoreDefaults() {
        settingsStore.save(AiSettings.defaults());
        endpoint = null;
        testing = false;
        setStatus("已恢复默认值。", false);
        owner.rebuildAssistantWidgets();
    }

    private void setStatus(String value, boolean error) {
        status = value == null ? "" : value;
        statusError = error;
    }

    private int parseInt(EditBox field, int fallback) {
        try {
            return field == null ? fallback : Integer.parseInt(field.getValue().strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String value(EditBox field) {
        return field == null ? "" : field.getValue();
    }

    private Component intensityLabel(SearchIntensity value) {
        String key = switch (value) {
            case FAST -> "screen.modpedia.ai_intensity_fast";
            case STANDARD -> "screen.modpedia.ai_intensity_standard";
            case DEEP -> "screen.modpedia.ai_intensity_deep";
            case CUSTOM -> "screen.modpedia.ai_intensity_custom";
        };
        return Component.translatable(key);
    }

    private void drawLabel(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(key), x, y, SUBTLE_COLOR, false);
    }
}

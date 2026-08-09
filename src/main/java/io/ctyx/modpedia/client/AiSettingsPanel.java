package io.ctyx.modpedia.client;

import io.ctyx.modpedia.ai.AiClient;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ai.AiSettingsStore;
import io.ctyx.modpedia.ai.AssistantMode;
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

/** 设置二级页面；内容滚动，页脚固定，并始终限制在助手窗口内部。 */
final class AiSettingsPanel {
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int SUBTLE_COLOR = 0xFFB8C3D3;
    private static final int DISABLED_COLOR = 0xFF72839A;
    private static final int ERROR_COLOR = 0xFFFFB4AB;
    private static final int PAGE_PADDING = 10;
    private static final int TITLE_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 64;
    private static final int CONTENT_HEIGHT = 366;

    private final AiSettingsStore settingsStore = AiSettingsStore.runtime();
    private AssistantScreen owner;
    private Font font;
    private int left;
    private int top;
    private int width;
    private int bottom;
    private int scrollOffset;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
    private EditBox maxRounds;
    private EditBox maxResults;
    private EditBox maxContextChars;
    private EditBox timeoutSeconds;
    private CycleButton<AssistantMode> mode;
    private CycleButton<SearchIntensity> intensity;
    private CycleButton<Boolean> streaming;
    private Button saveButton;
    private Button testButton;
    private Button restoreButton;
    private Button cancelButton;
    private String status = "";
    private boolean statusError;
    private boolean testing;

    void init(AssistantScreen owner, Font font, int left, int top, int width, int bottom) {
        AiSettings values = endpoint == null ? settingsStore.load() : readSettings();
        this.owner = owner;
        this.font = font;
        this.left = left;
        this.top = top;
        this.width = Math.max(1, width);
        this.bottom = Math.max(top + 1, bottom);
        clampScroll();

        int fieldLeft = left + PAGE_PADDING;
        int fieldWidth = Math.max(1, width - PAGE_PADDING * 2);
        int contentTop = contentBounds().top();

        mode = CycleButton.<AssistantMode>builder(this::modeLabel)
                .withValues(Arrays.asList(AssistantMode.values()))
                .withInitialValue(values.mode())
                .create(fieldLeft, y(contentTop, 18), fieldWidth, 20,
                        Component.translatable("screen.modpedia.assistant_mode"),
                        (button, selected) -> {
                            applyModeEnabled(selected);
                            owner.rebuildAssistantWidgets();
                        });

        endpoint = textField(fieldLeft, y(contentTop, 55), fieldWidth, values.endpoint(),
                "screen.modpedia.ai_endpoint_hint", false);
        model = textField(fieldLeft, y(contentTop, 92), fieldWidth, values.model(),
                "screen.modpedia.ai_model_hint", false);
        apiKey = textField(fieldLeft, y(contentTop, 129), fieldWidth, values.apiKey(),
                "screen.modpedia.ai_api_key_hint", true);
        apiKey.setFormatter((value, cursor) -> FormattedCharSequence.forward(
                "•".repeat(value.codePointCount(0, value.length())), Style.EMPTY
        ));

        intensity = CycleButton.<SearchIntensity>builder(this::intensityLabel)
                .withValues(Arrays.asList(SearchIntensity.values()))
                .withInitialValue(values.intensity())
                .create(fieldLeft, y(contentTop, 166), fieldWidth, 20,
                        Component.translatable("screen.modpedia.ai_search_intensity"),
                        (button, selected) -> setStatus("自定义参数仅在“自定义”档位生效。", false));
        streaming = CycleButton.booleanBuilder(
                        Component.translatable("screen.modpedia.enabled"),
                        Component.translatable("screen.modpedia.disabled"))
                .withInitialValue(values.streaming())
                .create(fieldLeft, y(contentTop, 203), fieldWidth, 20,
                        Component.translatable("screen.modpedia.ai_sse"));

        int smallGap = 6;
        int smallWidth = Math.max(1, (fieldWidth - smallGap) / 2);
        maxRounds = textField(fieldLeft, y(contentTop, 280), smallWidth,
                Integer.toString(values.maxRounds()), "screen.modpedia.ai_max_rounds_hint", false);
        maxResults = textField(fieldLeft + smallWidth + smallGap, y(contentTop, 280), smallWidth,
                Integer.toString(values.maxResults()), "screen.modpedia.ai_max_results_hint", false);
        maxContextChars = textField(fieldLeft, y(contentTop, 336), smallWidth,
                Integer.toString(values.maxContextChars()), "screen.modpedia.ai_context_chars_hint", false);
        timeoutSeconds = textField(fieldLeft + smallWidth + smallGap, y(contentTop, 336), smallWidth,
                Integer.toString(values.timeoutSeconds()), "screen.modpedia.ai_timeout_hint", false);

        owner.addSettingsContentWidget(mode);
        owner.addSettingsContentWidget(endpoint);
        owner.addSettingsContentWidget(model);
        owner.addSettingsContentWidget(apiKey);
        owner.addSettingsContentWidget(intensity);
        owner.addSettingsContentWidget(streaming);
        owner.addSettingsContentWidget(maxRounds);
        owner.addSettingsContentWidget(maxResults);
        owner.addSettingsContentWidget(maxContextChars);
        owner.addSettingsContentWidget(timeoutSeconds);

        createFooterButtons(owner, fieldLeft, fieldWidth);
        applyModeEnabled(values.mode());
    }

    void render(GuiGraphics graphics, AssistantGlassConfig.Style style, boolean opaque, int mouseX, int mouseY) {
        Bounds page = pageBounds();
        graphics.fill(page.left(), page.top(), page.right(), page.bottom(),
                opaque ? style.opaquePanelColor() : style.panelColor());
        graphics.renderOutline(page.left(), page.top(), page.width(), page.height(), style.outlineColor());
        graphics.drawString(font, Component.translatable("screen.modpedia.assistant_settings"), left + PAGE_PADDING, top + 10, TEXT_COLOR, false);
        graphics.drawString(font, Component.literal("×"), page.right() - 22, top + 8, TEXT_COLOR, false);

        Bounds content = contentBounds();
        graphics.enableScissor(content.left(), content.top(), content.right(), content.bottom());
        drawLabel(graphics, "screen.modpedia.assistant_mode", left + PAGE_PADDING, y(content.top(), 4), TEXT_COLOR);
        drawLabel(graphics, "screen.modpedia.ai_endpoint", left + PAGE_PADDING, y(content.top(), 39), fieldColor(), false);
        drawLabel(graphics, "screen.modpedia.ai_model", left + PAGE_PADDING, y(content.top(), 76), fieldColor(), false);
        drawLabel(graphics, "screen.modpedia.ai_api_key", left + PAGE_PADDING, y(content.top(), 113), fieldColor(), false);
        drawLabel(graphics, "screen.modpedia.ai_search_intensity", left + PAGE_PADDING, y(content.top(), 150), SUBTLE_COLOR, false);
        drawLabel(graphics, "screen.modpedia.ai_sse", left + PAGE_PADDING, y(content.top(), 187), fieldColor(), false);
        drawLabel(graphics, "screen.modpedia.ai_advanced", left + PAGE_PADDING, y(content.top(), 247), SUBTLE_COLOR, false);
        drawLabel(graphics, "screen.modpedia.ai_max_rounds", left + PAGE_PADDING, y(content.top(), 264), SUBTLE_COLOR, false);
        drawLabel(graphics, "screen.modpedia.ai_max_results", left + width / 2 + 3, y(content.top(), 264), SUBTLE_COLOR, false);
        drawLabel(graphics, "screen.modpedia.ai_context_chars", left + PAGE_PADDING, y(content.top(), 320), SUBTLE_COLOR, false);
        drawLabel(graphics, "screen.modpedia.ai_timeout", left + width / 2 + 3, y(content.top(), 320), SUBTLE_COLOR, false);
        graphics.disableScissor();

        Bounds footer = footerBounds();
        graphics.fill(footer.left(), footer.top(), footer.right(), footer.bottom(),
                opaque ? style.opaquePanelColor() : style.panelColor());
        if (!status.isBlank()) {
            graphics.drawString(font, Component.literal(status), left + PAGE_PADDING, footer.top() + 3,
                    statusError ? ERROR_COLOR : style.accentColor(), false);
        }
        if (scrollContentHeight() > content.height()) {
            int maxScroll = Math.max(1, scrollContentHeight() - content.height());
            int trackHeight = Math.max(14, content.height() * content.height() / scrollContentHeight());
            int trackY = content.top() + (content.height() - trackHeight) * scrollOffset / maxScroll;
            graphics.fill(content.right() - 4, content.top(), content.right() - 2, content.bottom(), 0x4F000000);
            graphics.fill(content.right() - 4, trackY, content.right() - 2, trackY + trackHeight, 0xBF9CC7FF);
        }
    }

    void scrollBy(double amount) {
        int old = scrollOffset;
        scrollOffset -= (int) Math.round(amount * 24.0);
        clampScroll();
        if (old != scrollOffset && owner != null) {
            owner.rebuildAssistantWidgets();
        }
    }

    Bounds pageBounds() {
        return new Bounds(left, top, width, Math.max(1, bottom - top));
    }

    Bounds contentBounds() {
        int contentTop = top + TITLE_HEIGHT;
        int footerTop = Math.max(contentTop + 1, bottom - FOOTER_HEIGHT);
        return new Bounds(left + 4, contentTop, Math.max(1, width - 8), Math.max(1, footerTop - contentTop));
    }

    int contentLeft() {
        return contentBounds().left();
    }

    int contentTop() {
        return contentBounds().top();
    }

    int contentWidth() {
        return contentBounds().width();
    }

    int contentHeight() {
        return contentBounds().height();
    }

    private Bounds footerBounds() {
        int footerTop = Math.max(top + TITLE_HEIGHT + 1, bottom - FOOTER_HEIGHT);
        return new Bounds(left, footerTop, width, Math.max(1, bottom - footerTop));
    }

    boolean pageContains(double mouseX, double mouseY) {
        return pageBounds().contains(mouseX, mouseY);
    }

    boolean contentContains(double mouseX, double mouseY) {
        return contentBounds().contains(mouseX, mouseY);
    }

    boolean closeContains(double mouseX, double mouseY) {
        return mouseX >= left + width - 32 && mouseX < left + width
                && mouseY >= top && mouseY < top + TITLE_HEIGHT;
    }

    net.minecraft.client.gui.components.AbstractWidget initialFocus() {
        return mode;
    }

    private void createFooterButtons(AssistantScreen owner, int fieldLeft, int fieldWidth) {
        int buttonGap = 6;
        boolean twoRows = fieldWidth < 360;
        if (twoRows) {
            int buttonWidth = Math.max(1, (fieldWidth - buttonGap) / 2);
            int rowOne = footerBounds().bottom() - 44;
            int rowTwo = footerBounds().bottom() - 20;
            saveButton = button("screen.modpedia.save", this::saveSettings, fieldLeft, rowOne, buttonWidth);
            testButton = button("screen.modpedia.test_connection", this::testConnection,
                    fieldLeft + buttonWidth + buttonGap, rowOne, buttonWidth);
            restoreButton = button("screen.modpedia.restore_defaults", this::restoreDefaults,
                    fieldLeft, rowTwo, buttonWidth);
            cancelButton = button("gui.cancel", owner::closeSettingsPanel,
                    fieldLeft + buttonWidth + buttonGap, rowTwo, buttonWidth);
        } else {
            int buttonWidth = Math.max(1, (fieldWidth - buttonGap * 3) / 4);
            int y = footerBounds().bottom() - 22;
            saveButton = button("screen.modpedia.save", this::saveSettings, fieldLeft, y, buttonWidth);
            testButton = button("screen.modpedia.test_connection", this::testConnection,
                    fieldLeft + (buttonWidth + buttonGap), y, buttonWidth);
            restoreButton = button("screen.modpedia.restore_defaults", this::restoreDefaults,
                    fieldLeft + (buttonWidth + buttonGap) * 2, y, buttonWidth);
            cancelButton = button("gui.cancel", owner::closeSettingsPanel,
                    fieldLeft + (buttonWidth + buttonGap) * 3, y,
                    Math.max(1, fieldWidth - (buttonWidth + buttonGap) * 3));
        }
        owner.addSettingsFooterWidget(saveButton);
        owner.addSettingsFooterWidget(testButton);
        owner.addSettingsFooterWidget(restoreButton);
        owner.addSettingsFooterWidget(cancelButton);
    }

    private Button button(String key, Runnable action, int x, int y, int width) {
        return Button.builder(Component.translatable(key), ignored -> action.run())
                .bounds(x, y, width, 20)
                .build();
    }

    private EditBox textField(int x, int y, int fieldWidth, String value, String hintKey, boolean secret) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20, Component.translatable(hintKey));
        field.setValue(value == null ? "" : value);
        field.setMaxLength(512);
        field.setHint(Component.translatable(hintKey));
        field.setBordered(true);
        field.setTextColor(TEXT_COLOR);
        return field;
    }

    private AiSettings readSettings() {
        AssistantMode selectedMode = mode == null ? AssistantMode.AI : mode.getValue();
        SearchIntensity selectedIntensity = intensity == null ? SearchIntensity.STANDARD : intensity.getValue();
        return new AiSettings(
                selectedMode,
                value(endpoint),
                value(model),
                value(apiKey),
                streaming == null || streaming.getValue(),
                selectedIntensity,
                parseInt(maxRounds, selectedIntensity.rounds()),
                parseInt(maxResults, selectedIntensity.results()),
                parseInt(maxContextChars, selectedIntensity.contextChars()),
                parseInt(timeoutSeconds, 90)
        );
    }

    private void applyModeEnabled(AssistantMode selected) {
        boolean ai = selected != AssistantMode.SEARCH_ONLY;
        if (endpoint != null) endpoint.active = ai;
        if (model != null) model.active = ai;
        if (apiKey != null) apiKey.active = ai;
        if (streaming != null) streaming.active = ai;
        if (testButton != null) testButton.active = ai;
    }

    private void saveSettings() {
        settingsStore.save(readSettings());
        setStatus("已保存。", false);
    }

    private void testConnection() {
        AiSettings values = readSettings();
        if (values.mode() == AssistantMode.SEARCH_ONLY) {
            setStatus("仅搜索模式无需测试 AI 连接。", false);
            return;
        }
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
        scrollOffset = 0;
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

    private int y(int contentTop, int offset) {
        return contentTop + offset - scrollOffset;
    }

    private int scrollContentHeight() {
        return CONTENT_HEIGHT;
    }

    private void clampScroll() {
        if (bottom <= top) {
            scrollOffset = 0;
            return;
        }
        int max = Math.max(0, scrollContentHeight() - Math.max(1, bottom - top - TITLE_HEIGHT - FOOTER_HEIGHT));
        scrollOffset = Math.max(0, Math.min(max, scrollOffset));
    }

    private int fieldColor() {
        return mode != null && mode.getValue() == AssistantMode.SEARCH_ONLY ? DISABLED_COLOR : SUBTLE_COLOR;
    }

    private Component modeLabel(AssistantMode value) {
        return Component.translatable(value == AssistantMode.SEARCH_ONLY
                ? "screen.modpedia.assistant_mode_search_only"
                : "screen.modpedia.assistant_mode_ai");
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

    private void drawLabel(GuiGraphics graphics, String key, int x, int y, int color) {
        drawLabel(graphics, key, x, y, color, true);
    }

    private void drawLabel(GuiGraphics graphics, String key, int x, int y, int color, boolean unused) {
        graphics.drawString(font, Component.translatable(key), x, y, color, false);
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
}

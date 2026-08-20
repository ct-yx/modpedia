package io.ctyx.modpedia.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ctyx.modpedia.ai.AiClient;
import io.ctyx.modpedia.ai.AiApiFormat;
import io.ctyx.modpedia.ai.AiSettings;
import io.ctyx.modpedia.ai.AssistantMode;
import io.ctyx.modpedia.ai.SearchIntensity;
import io.ctyx.modpedia.protocol.WorkerPayloadCodec;
import io.ctyx.modpedia.protocol.WorkerProtocol;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** 设置二级页面；控件随页面缩放，内容滚动，页脚固定。 */
final class AiSettingsPanel {
    private static final int TEXT_COLOR = 0xFFF3F6FA;
    private static final int SUBTLE_COLOR = 0xFFB8C3D3;
    private static final int DISABLED_COLOR = 0xFF72839A;
    private static final int ERROR_COLOR = 0xFFFFB4AB;

    private static final int BASE_PAGE_PADDING = 8;
    private static final int BASE_TITLE_HEIGHT = 26;
    private static final int BASE_FOOTER_HEIGHT = 46;
    private static final int BASE_CONTROL_HEIGHT = 18;
    private static final int BASE_BUTTON_GAP = 4;
    private static final int BASE_MAIN_ROW_GAP = 37;
    private static final int BASE_LABEL_CONTROL_GAP = 15;
    private static final int BASE_ADVANCED_HEADING_GAP = 24;
    private static final int BASE_ADVANCED_ROW_GAP = 52;
    private static final int BASE_CONTENT_BOTTOM_PADDING = 12;

    private final ModPediaBridge bridge = ModPediaBridge.get();
    private AssistantScreen owner;
    private Font font;
    private AssistantGlassConfig.Style buttonStyle;
    private int left;
    private int top;
    private int width;
    private int bottom;
    private double scale = 1.0;
    private int pagePadding;
    private int titleHeight;
    private int footerHeight;
    private int controlHeight;
    private int buttonGap;
    private int footerButtonRows;
    private int footerStatusHeight;
    private int footerStatusGap;
    private int footerBottomPadding;
    private int mainRowGap;
    private int labelControlGap;
    private int advancedHeadingGap;
    private int advancedRowGap;
    private int scrollOffset;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
    private EditBox maxRounds;
    private EditBox maxResults;
    private EditBox maxContextChars;
    private EditBox timeoutSeconds;
    private AssistantChoiceButton<AssistantMode> mode;
    private AssistantChoiceButton<AiApiFormat> apiFormat;
    private AssistantChoiceButton<SearchIntensity> intensity;
    private AssistantChoiceButton<Boolean> streaming;
    private AssistantPanelButton saveButton;
    private AssistantPanelButton testButton;
    private AssistantPanelButton testAllModelsButton;
    private AssistantPanelButton modelListButton;
    private AssistantPanelButton restoreButton;
    private AssistantPanelButton cancelButton;
    private List<String> availableModels = List.of();
    private String modelsEndpoint = "";
    private boolean fetchingModels;
    private long modelRequestId;
    private long settingsRequestId;
    private boolean settingsLoadRequested;
    private boolean settingsLoaded;
    private boolean savingSettings;
    private long settingsOperationId;
    private String status = "";
    private boolean statusError;
    private boolean testing;
    private boolean testingModels;

    void init(
            AssistantScreen owner,
            Font font,
            AssistantGlassConfig.Style buttonStyle,
            int left,
            int top,
            int width,
            int bottom
    ) {
        boolean requestSettings = endpoint == null && !settingsLoaded && !settingsLoadRequested;
        if (requestSettings) {
            settingsLoadRequested = true;
        }
        AiSettings values = endpoint == null ? AiSettings.defaults() : readSettings();
        this.owner = owner;
        this.font = font;
        this.buttonStyle = buttonStyle;
        this.left = left;
        this.top = top;
        this.width = Math.max(1, width);
        this.bottom = Math.max(top + 1, bottom);
        this.scale = AssistantSecondaryLayout.scaleFor(
                new AssistantSecondaryLayout.Rect(0, 0, this.width, this.bottom - this.top)
        );
        this.pagePadding = scaled(BASE_PAGE_PADDING, 6);
        this.titleHeight = scaled(BASE_TITLE_HEIGHT, 20);
        this.controlHeight = scaled(BASE_CONTROL_HEIGHT, 14);
        this.buttonGap = scaled(BASE_BUTTON_GAP, 4);
        this.footerButtonRows = footerRows(Math.max(1, this.width - this.pagePadding * 2));
        this.footerStatusHeight = Math.max(font.lineHeight, scaled(11, 8));
        this.footerStatusGap = scaled(5, 3);
        this.footerBottomPadding = scaled(5, 3);
        int requiredFooterHeight = footerStatusHeight
                + footerStatusGap
                + footerButtonRows * controlHeight
                + Math.max(0, footerButtonRows - 1) * buttonGap
                + footerBottomPadding;
        this.footerHeight = Math.max(
                scaled(BASE_FOOTER_HEIGHT, 38),
                requiredFooterHeight
        );
        this.labelControlGap = Math.max(
                scaled(BASE_LABEL_CONTROL_GAP, 11),
                font.lineHeight + 7
        );
        this.mainRowGap = Math.max(
                scaled(BASE_MAIN_ROW_GAP, 24),
                labelControlGap + controlHeight + 5
        );
        this.advancedHeadingGap = Math.max(
                scaled(BASE_ADVANCED_HEADING_GAP, 16),
                font.lineHeight + 9
        );
        this.advancedRowGap = Math.max(
                scaled(BASE_ADVANCED_ROW_GAP, 34),
                labelControlGap + controlHeight + 6
        );
        clampScroll();

        Bounds content = contentBounds();
        int fieldLeft = content.left();
        int fieldWidth = Math.max(1, content.width());

        mode = new AssistantChoiceButton<>(
                font,
                buttonStyle,
                fieldLeft,
                y(content.top(), mainLabelOffset(0)),
                fieldWidth,
                controlHeight,
                Component.translatable("screen.modpedia.assistant_mode"),
                Arrays.asList(AssistantMode.values()),
                values.mode(),
                this::modeLabel,
                selected -> {
                    applyModeEnabled(selected);
                    owner.rebuildAssistantWidgets();
                }
        );

        apiFormat = new AssistantChoiceButton<>(
                font,
                buttonStyle,
                fieldLeft,
                y(content.top(), mainLabelOffset(1)),
                fieldWidth,
                controlHeight,
                Component.translatable("screen.modpedia.ai_api_format"),
                Arrays.asList(AiApiFormat.values()),
                values.apiFormat(),
                this::apiFormatLabel,
                selected -> {
                    endpoint.setHint(Component.translatable(endpointHintKey(selected)));
                    setStatus("已选择 " + apiFormatLabel(selected).getString() + "。", false);
                    owner.rebuildAssistantWidgets();
                }
        );

        endpoint = textField(fieldLeft, y(content.top(), mainControlOffset(2)), fieldWidth, values.endpoint(),
                "screen.modpedia.ai_endpoint_hint", false);
        endpoint.setHint(Component.translatable(endpointHintKey(values.apiFormat())));
        int modelY = y(content.top(), mainControlOffset(3));
        int modelButtonGap = scaled(5, 4);
        int modelButtonWidth = modelButtonWidth(fieldWidth);
        int modelFieldWidth = Math.max(1, fieldWidth - modelButtonGap - modelButtonWidth);
        model = textField(fieldLeft, modelY, modelFieldWidth, values.model(),
                "screen.modpedia.ai_model_hint", false);
        modelListButton = new AssistantPanelButton(
                font,
                buttonStyle,
                fieldLeft + modelFieldWidth + modelButtonGap,
                modelY,
                modelButtonWidth,
                controlHeight,
                this::modelListLabel,
                this::fetchOrSelectModel
        );
        apiKey = textField(fieldLeft, y(content.top(), mainControlOffset(4)), fieldWidth, values.apiKey(),
                "screen.modpedia.ai_api_key_hint", true);
        apiKey.setFormatter((value, cursor) -> FormattedCharSequence.forward(
                "•".repeat(value.codePointCount(0, value.length())), Style.EMPTY
        ));

        intensity = new AssistantChoiceButton<>(
                font,
                buttonStyle,
                fieldLeft,
                y(content.top(), mainLabelOffset(5)),
                fieldWidth,
                controlHeight,
                Component.translatable("screen.modpedia.ai_search_intensity"),
                Arrays.asList(SearchIntensity.values()),
                values.intensity(),
                this::intensityLabel,
                selected -> setStatus("自定义参数仅在“自定义”档位生效。", false)
        );
        streaming = new AssistantChoiceButton<>(
                font,
                buttonStyle,
                fieldLeft,
                y(content.top(), mainLabelOffset(6)),
                fieldWidth,
                controlHeight,
                Component.translatable("screen.modpedia.ai_sse"),
                Arrays.asList(Boolean.TRUE, Boolean.FALSE),
                values.streaming(),
                enabled -> Component.translatable(enabled
                        ? "screen.modpedia.enabled"
                        : "screen.modpedia.disabled"),
                this::saveStreamingChoice
        );

        int smallGap = scaled(6, 4);
        int smallWidth = Math.max(1, (fieldWidth - smallGap) / 2);
        maxRounds = advancedField(fieldLeft, fieldWidth, smallWidth, values.maxRounds(), 0,
                "screen.modpedia.ai_max_rounds_hint");
        maxResults = advancedField(fieldLeft, fieldWidth, smallWidth, values.maxResults(), 1,
                "screen.modpedia.ai_max_results_hint");
        maxContextChars = advancedField(fieldLeft, fieldWidth, smallWidth, values.maxContextChars(), 2,
                "screen.modpedia.ai_context_chars_hint");
        timeoutSeconds = advancedField(fieldLeft, fieldWidth, smallWidth, values.timeoutSeconds(), 3,
                "screen.modpedia.ai_timeout_hint");

        owner.addSettingsContentWidget(mode);
        owner.addSettingsContentWidget(apiFormat);
        owner.addSettingsContentWidget(endpoint);
        owner.addSettingsContentWidget(model);
        owner.addSettingsContentWidget(modelListButton);
        owner.addSettingsContentWidget(apiKey);
        owner.addSettingsContentWidget(intensity);
        owner.addSettingsContentWidget(streaming);
        owner.addSettingsContentWidget(maxRounds);
        owner.addSettingsContentWidget(maxResults);
        owner.addSettingsContentWidget(maxContextChars);
        owner.addSettingsContentWidget(timeoutSeconds);

        createFooterButtons(owner, fieldLeft, fieldWidth);
        applyModeEnabled(values.mode());
        if (requestSettings) {
            loadSettingsFromWorker();
        }
    }

    private EditBox advancedField(int fieldLeft, int fieldWidth, int smallWidth, int value, int index, String hint) {
        return textField(
                advancedFieldX(fieldLeft, smallWidth, index),
                y(contentBounds().top(), advancedControlOffset(index)),
                advancedFieldWidth(fieldWidth, smallWidth),
                Integer.toString(value),
                hint,
                false
        );
    }

    void render(GuiGraphics graphics, AssistantGlassConfig.Style style, boolean opaque, int mouseX, int mouseY) {
        Bounds page = pageBounds();
        // 页面本身不透明，并且先于控件绘制；控件的 scissor 与页面完全一致。
        graphics.enableScissor(page.left(), page.top(), page.right(), page.bottom());
        graphics.fill(page.left(), page.top(), page.right(), page.bottom(), style.opaquePanelColor());
        graphics.renderOutline(page.left(), page.top(), page.width(), page.height(), style.outlineColor());
        String title = font.plainSubstrByWidth(
                Component.translatable("screen.modpedia.assistant_settings").getString(),
                Math.max(1, width - pagePadding * 2 - scaled(30, 10))
        );
        graphics.drawString(font, Component.literal(title), left + pagePadding, top + scaled(8, 5), TEXT_COLOR, false);
        graphics.drawString(font, Component.literal("×"), page.right() - scaled(22, 14), top + scaled(6, 4), TEXT_COLOR, false);

        Bounds content = contentBounds();
        graphics.enableScissor(content.left(), content.top(), content.right(), content.bottom());
        int fieldLeft = content.left();
        int fieldWidth = content.width();
        if (labelAndControlFullyVisible(mainLabelOffset(2), mainControlOffset(2))) {
            drawLabel(graphics, "screen.modpedia.ai_endpoint", fieldLeft,
                    y(content.top(), mainLabelOffset(2)), fieldColor(), false);
        }
        if (labelAndControlFullyVisible(mainLabelOffset(3), mainControlOffset(3))) {
            drawLabel(graphics, "screen.modpedia.ai_model", fieldLeft,
                    y(content.top(), mainLabelOffset(3)), fieldColor(), false);
        }
        if (labelAndControlFullyVisible(mainLabelOffset(4), mainControlOffset(4))) {
            drawLabel(graphics, "screen.modpedia.ai_api_key", fieldLeft,
                    y(content.top(), mainLabelOffset(4)), fieldColor(), false);
        }
        String[] advancedLabels = {
                "screen.modpedia.ai_max_rounds",
                "screen.modpedia.ai_max_results",
                "screen.modpedia.ai_context_chars",
                "screen.modpedia.ai_timeout"
        };
        boolean advancedVisible = false;
        for (int index = 0; index < advancedLabels.length; index++) {
            advancedVisible |= controlFullyVisible(advancedControlOffset(index));
        }
        if (advancedVisible && textFullyVisible(advancedHeadingOffset(), font.lineHeight)) {
            drawLabel(graphics, "screen.modpedia.ai_advanced", fieldLeft,
                    y(content.top(), advancedHeadingOffset()), SUBTLE_COLOR, false);
        }
        int smallGap = scaled(6, 4);
        int smallWidth = Math.max(1, (fieldWidth - smallGap) / 2);
        for (int index = 0; index < advancedLabels.length; index++) {
            if (!labelAndControlFullyVisible(
                    advancedLabelOffset(index),
                    advancedControlOffset(index)
            )) {
                continue;
            }
            drawLabel(
                    graphics,
                    advancedLabels[index],
                    advancedFieldX(fieldLeft, smallWidth, index),
                    y(content.top(), advancedLabelOffset(index)),
                    SUBTLE_COLOR,
                    false
            );
        }
        graphics.disableScissor();

        Bounds footer = footerBounds();
        graphics.fill(footer.left(), footer.top(), footer.right(), footer.bottom(), style.opaquePanelColor());
        if (!status.isBlank()) {
            String statusText = font.plainSubstrByWidth(status, Math.max(1, width - pagePadding * 2));
            graphics.drawString(font, Component.literal(statusText), left + pagePadding,
                    footer.top() + Math.max(1, (footerStatusHeight - font.lineHeight) / 2),
                    statusError ? ERROR_COLOR : style.accentColor(), false);
        }
        if (scrollContentHeight() > content.height()) {
            int maxScroll = Math.max(1, scrollContentHeight() - content.height());
            int trackHeight = Math.max(12, content.height() * content.height() / scrollContentHeight());
            int trackY = content.top() + (content.height() - trackHeight) * scrollOffset / maxScroll;
            graphics.fill(content.right() - 4, content.top(), content.right() - 2, content.bottom(), 0x4F000000);
            graphics.fill(content.right() - 4, trackY, content.right() - 2, trackY + trackHeight, 0xBF9CC7FF);
        }
        graphics.disableScissor();
    }

    void scrollBy(double amount) {
        int old = scrollOffset;
        scrollOffset -= (int) Math.round(amount * scaled(24, 12));
        clampScroll();
        if (old != scrollOffset && owner != null) {
            owner.rebuildAssistantWidgets();
        }
    }

    Bounds pageBounds() {
        return new Bounds(left, top, width, Math.max(1, bottom - top));
    }

    Bounds contentBounds() {
        int contentTop = top + titleHeight;
        int footerTop = Math.max(contentTop + 1, bottom - footerHeight);
        return new Bounds(
                left + pagePadding,
                contentTop,
                Math.max(1, width - pagePadding * 2),
                Math.max(1, footerTop - contentTop)
        );
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
        int footerTop = Math.max(top + titleHeight + 1, bottom - footerHeight);
        return new Bounds(left, footerTop, width, Math.max(1, bottom - footerTop));
    }

    boolean pageContains(double mouseX, double mouseY) {
        return pageBounds().contains(mouseX, mouseY);
    }

    void cancelPendingModelRequest() {
        modelRequestId++;
        settingsRequestId++;
        settingsOperationId++;
        fetchingModels = false;
        settingsLoadRequested = false;
        savingSettings = false;
        testing = false;
        testingModels = false;
    }

    boolean contentContains(double mouseX, double mouseY) {
        return contentBounds().contains(mouseX, mouseY);
    }

    boolean closeContains(double mouseX, double mouseY) {
        int closeWidth = Math.max(24, scaled(32, 24));
        return mouseX >= left + width - closeWidth && mouseX < left + width
                && mouseY >= top && mouseY < top + titleHeight;
    }

    net.minecraft.client.gui.components.AbstractWidget initialFocus() {
        return mode;
    }

    private void createFooterButtons(AssistantScreen owner, int fieldLeft, int fieldWidth) {
        Bounds footer = footerBounds();
        // 兼容性诊断是批量动作，和单模型连接测试并列放在同一个统一按钮网格中。
        // 五个按钮在窄窗口也固定为两行，避免压缩后文字互相覆盖。
        boolean twoRows = true;
        int buttonsBottom = Math.max(footer.top() + controlHeight, footer.bottom() - footerBottomPadding);
        if (twoRows) {
            int firstRowWidth = Math.max(1, (fieldWidth - buttonGap * 2) / 3);
            int secondRowWidth = Math.max(1, (fieldWidth - buttonGap) / 2);
            int rowTwo = Math.max(footer.top(), buttonsBottom - controlHeight);
            int rowOne = Math.max(
                    footer.top() + footerStatusHeight + footerStatusGap,
                    rowTwo - controlHeight - buttonGap
            );
            saveButton = button("screen.modpedia.save", this::saveSettings, fieldLeft, rowOne, firstRowWidth);
            testButton = button("screen.modpedia.test_connection", this::testConnection,
                    fieldLeft + firstRowWidth + buttonGap, rowOne, firstRowWidth);
            testAllModelsButton = button("screen.modpedia.ai_test_all_models", this::testAllModels,
                    fieldLeft + (firstRowWidth + buttonGap) * 2, rowOne,
                    Math.max(1, fieldWidth - (firstRowWidth + buttonGap) * 2));
            restoreButton = button("screen.modpedia.restore_defaults", this::restoreDefaults,
                    fieldLeft, rowTwo, secondRowWidth);
            cancelButton = button("gui.cancel", owner::closeSettingsPanel,
                    fieldLeft + secondRowWidth + buttonGap, rowTwo,
                    Math.max(1, fieldWidth - secondRowWidth - buttonGap));
        } else {
            int buttonWidth = Math.max(1, (fieldWidth - buttonGap * 3) / 4);
            int y = Math.max(
                    footer.top() + footerStatusHeight + footerStatusGap,
                    buttonsBottom - controlHeight
            );
            saveButton = button("screen.modpedia.save", this::saveSettings, fieldLeft, y, buttonWidth);
            testButton = button("screen.modpedia.test_connection", this::testConnection,
                    fieldLeft + buttonWidth + buttonGap, y, buttonWidth);
            restoreButton = button("screen.modpedia.restore_defaults", this::restoreDefaults,
                    fieldLeft + (buttonWidth + buttonGap) * 2, y, buttonWidth);
            cancelButton = button("gui.cancel", owner::closeSettingsPanel,
                    fieldLeft + (buttonWidth + buttonGap) * 3, y,
                    Math.max(1, fieldWidth - (buttonWidth + buttonGap) * 3));
        }
        owner.addSettingsFooterWidget(saveButton);
        owner.addSettingsFooterWidget(testButton);
        owner.addSettingsFooterWidget(testAllModelsButton);
        owner.addSettingsFooterWidget(restoreButton);
        owner.addSettingsFooterWidget(cancelButton);
    }

    private AssistantPanelButton button(String key, Runnable action, int x, int y, int buttonWidth) {
        return new AssistantPanelButton(
                font,
                buttonStyle,
                x,
                y,
                buttonWidth,
                controlHeight,
                Component.translatable(key),
                action
        );
    }

    private EditBox textField(int x, int y, int fieldWidth, String value, String hintKey, boolean secret) {
        EditBox field = new EditBox(font, x, y, fieldWidth, controlHeight, Component.translatable(hintKey));
        // EditBox 默认 maxLength 是 32；先设置上限再写入值，否则 64 位 API Key
        // 在页面重建时会先被截成 32 位，随后再点击任意控件就只剩一半圆点。
        field.setMaxLength(512);
        field.setValue(value == null ? "" : value);
        field.setHint(Component.translatable(hintKey));
        field.setBordered(true);
        field.setTextColor(TEXT_COLOR);
        return field;
    }

    private AiSettings readSettings() {
        AssistantMode selectedMode = mode == null ? AssistantMode.AI : mode.getValue();
        AiApiFormat selectedFormat = apiFormat == null ? AiApiFormat.CHAT_COMPLETIONS : apiFormat.getValue();
        SearchIntensity selectedIntensity = intensity == null ? SearchIntensity.STANDARD : intensity.getValue();
        return new AiSettings(
                selectedMode,
                selectedFormat,
                AiClient.normalizedEndpoint(value(endpoint), selectedFormat),
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
        boolean ready = settingsLoaded && !settingsLoadRequested && !savingSettings
                && !testing && !testingModels && !fetchingModels;
        if (endpoint != null) endpoint.active = ai && ready;
        if (model != null) model.active = ai && ready;
        if (apiKey != null) apiKey.active = ai && ready;
        if (apiFormat != null) apiFormat.active = ai && ready;
        if (streaming != null) streaming.active = ai && ready;
        if (testButton != null) testButton.active = ai && ready;
        if (testAllModelsButton != null) {
            testAllModelsButton.active = ai && ready
                    && (apiFormat == null || apiFormat.getValue() == AiApiFormat.CHAT_COMPLETIONS);
        }
        if (modelListButton != null) modelListButton.active = ai && ready;
        if (saveButton != null) saveButton.active = ready;
        if (restoreButton != null) restoreButton.active = ready;
        if (cancelButton != null) cancelButton.active = true;
        if (mode != null) mode.active = ready;
        if (intensity != null) intensity.active = ready;
    }

    private int modelButtonWidth(int fieldWidth) {
        int preferred = scaled(118, 86);
        int minimum = scaled(86, 64);
        return Math.min(Math.max(1, fieldWidth), Math.max(minimum, Math.min(preferred, fieldWidth / 3)));
    }

    private Component modelListLabel() {
        if (fetchingModels) {
            return Component.translatable("screen.modpedia.ai_fetch_models_loading");
        }
        if (!availableModels.isEmpty()) {
            return Component.translatable("screen.modpedia.ai_model_list_count", availableModels.size());
        }
        return Component.translatable("screen.modpedia.ai_fetch_models");
    }

    private void fetchOrSelectModel() {
        if (fetchingModels || savingSettings || testing || testingModels
                || settingsLoadRequested || mode == null
                || mode.getValue() == AssistantMode.SEARCH_ONLY) {
            return;
        }
        String currentEndpoint = value(endpoint).strip();
        if (!availableModels.isEmpty() && currentEndpoint.equals(modelsEndpoint)) {
            String currentModel = value(model).strip();
            int currentIndex = -1;
            for (int index = 0; index < availableModels.size(); index++) {
                if (availableModels.get(index).equals(currentModel)) {
                    currentIndex = index;
                    break;
                }
            }
            int nextIndex = (currentIndex + 1 + availableModels.size()) % availableModels.size();
            model.setValue(availableModels.get(nextIndex));
            setStatus("已选择模型：" + availableModels.get(nextIndex), false);
            return;
        }

        AiSettings values = readSettings();
        if (values.endpoint().isBlank()) {
            setStatus("请先填写 API 地址。", true);
            return;
        }
        if (values.effectiveApiKey().isBlank()) {
            setStatus("请先填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。", true);
            return;
        }
        fetchingModels = true;
        availableModels = List.of();
        long requestId = ++modelRequestId;
        modelsEndpoint = "";
        setStatus("正在获取模型列表……", false);
        applyModeEnabled(values.mode());
        owner.rebuildAssistantWidgets();
        bridge.fetchModels(values, event -> {
            if (!owner.settingsPanelOpen() || requestId != modelRequestId) {
                return;
            }
            fetchingModels = false;
            if (isError(event)) {
                availableModels = List.of();
                modelsEndpoint = "";
                setStatus(workerMessage(event, "获取模型列表失败。"), true);
            } else {
                availableModels = modelIds(event);
                modelsEndpoint = value(endpoint).strip();
                String normalized = normalizeEndpoint(modelsEndpoint);
                boolean endpointChanged = !normalized.equals(modelsEndpoint);
                if (endpointChanged) {
                    endpoint.setValue(normalized);
                    modelsEndpoint = normalized;
                }
                setStatus(workerMessage(event, "模型列表获取完成。")
                        + (endpointChanged ? " 已自动补全 /v1。" : ""), false);
                if (!availableModels.isEmpty()) {
                    String currentModel = value(model).strip();
                    boolean found = availableModels.stream().anyMatch(currentModel::equals);
                    if (!found) {
                        model.setValue(availableModels.getFirst());
                    }
                }
            }
            applyModeEnabled(readSettings().mode());
            owner.rebuildAssistantWidgets();
        });
    }

    private void saveSettings() {
        if (savingSettings || testing || testingModels || settingsLoadRequested) {
            return;
        }
        AiSettings values = readSettings();
        persistSettings(values, "正在保存设置……", ignored -> setStatus("已保存并验证。", false));
    }

    /**
     * 流式开关是即时行为设置。点击后立即写入 Worker，避免界面已经显示“关闭”但
     * Worker 仍从旧配置读取 true，下一次请求继续进入 SSE 分支。
     */
    private void saveStreamingChoice(boolean enabled) {
        if (savingSettings || testing || testingModels || settingsLoadRequested) {
            return;
        }
        persistSettings(
                readSettings(),
                enabled ? "正在开启流式响应……" : "正在关闭流式响应……",
                persisted -> setStatus(
                        persisted.streaming() ? "流式响应已开启。" : "流式响应已关闭。",
                        false
                )
        );
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
        if (values.effectiveApiKey().isBlank()) {
            setStatus("请先填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。", true);
            return;
        }
        if (savingSettings || testing || testingModels || settingsLoadRequested) {
            return;
        }
        // 先保存当前 UI 值，再用 Worker 回传的同一份值测试，避免保存和测试两个 IPC
        // 请求并行时测试到旧配置。
        testing = true;
        savingSettings = true;
        long operationId = ++settingsOperationId;
        setStatus("正在保存并测试连接……", false);
        applyModeEnabled(values.mode());
        owner.rebuildAssistantWidgets();
        if (!bridge.saveSettings(values, event -> {
            if (!owner.settingsPanelOpen() || operationId != settingsOperationId || !testing) {
                return;
            }
            savingSettings = false;
            if (isError(event)) {
                testing = false;
                setStatus("保存失败，未发起连接测试：" + workerMessage(event, "未知错误"), true);
                applyModeEnabled(readSettings().mode());
                owner.rebuildAssistantWidgets();
                return;
            }
            settingsLoaded = true;
            AiSettings persisted = settingsFromEvent(event, values);
            applySettings(persisted);
            setStatus("正在测试连接……", false);
            owner.rebuildAssistantWidgets();
            if (!bridge.testConnection(persisted, result -> {
                if (!owner.settingsPanelOpen() || operationId != settingsOperationId || !testing) {
                    return;
                }
                testing = false;
                String message = workerMessage(result, "未知错误");
                setStatus(isError(result) ? "连接失败：" + message : message, isError(result));
                applyModeEnabled(readSettings().mode());
                owner.rebuildAssistantWidgets();
            })) {
                testing = false;
                setStatus("ModPedia Worker 不可用，无法测试连接。", true);
                applyModeEnabled(readSettings().mode());
                owner.rebuildAssistantWidgets();
            }
        })) {
            savingSettings = false;
            testing = false;
            setStatus("ModPedia Worker 不可用，设置未保存，无法测试连接。", true);
            applyModeEnabled(values.mode());
            owner.rebuildAssistantWidgets();
        }
    }

    private void testAllModels() {
        AiSettings values = readSettings();
        if (values.mode() == AssistantMode.SEARCH_ONLY) {
            setStatus("仅搜索模式无需测试 AI 模型。", false);
            return;
        }
        if (values.endpoint().isBlank()) {
            setStatus("请先填写 API 地址。", true);
            return;
        }
        if (values.effectiveApiKey().isBlank()) {
            setStatus("请先填写 API Key，或设置 MODPEDIA_API_KEY 环境变量。", true);
            return;
        }
        if (savingSettings || testing || testingModels || settingsLoadRequested) {
            return;
        }
        testingModels = true;
        long requestId = ++modelRequestId;
        setStatus("正在批量测试全部模型，期间无需逐个发送问题……", false);
        applyModeEnabled(values.mode());
        owner.rebuildAssistantWidgets();
        if (!bridge.testAllModels(values, event -> {
                    if (!owner.settingsPanelOpen() || requestId != modelRequestId) {
                        return;
                    }
                    testingModels = false;
                    if (isError(event)) {
                        setStatus("批量测试失败：" + workerMessage(event, "未知错误"), true);
                    } else {
                        setStatus(workerMessage(event, "批量模型测试完成。")
                                + " 报告已保存到 diagnostics。", false);
                    }
                    applyModeEnabled(readSettings().mode());
                    owner.rebuildAssistantWidgets();
                })) {
            testingModels = false;
            setStatus("ModPedia Worker 不可用，批量测试未启动。", true);
        }
    }

    private void restoreDefaults() {
        if (savingSettings || testing || testingModels || settingsLoadRequested) {
            return;
        }
        AiSettings defaults = AiSettings.defaults();
        persistSettings(defaults, "正在恢复默认设置……", ignored -> {
            testing = false;
            testingModels = false;
            modelRequestId++;
            scrollOffset = 0;
            setStatus("已恢复默认值。", false);
        });
    }

    private void persistSettings(
            AiSettings values,
            String pendingMessage,
            java.util.function.Consumer<AiSettings> onSuccess
    ) {
        savingSettings = true;
        long operationId = ++settingsOperationId;
        setStatus(pendingMessage, false);
        applyModeEnabled(values.mode());
        owner.rebuildAssistantWidgets();
        if (!bridge.saveSettings(values, event -> {
            if (!owner.settingsPanelOpen() || operationId != settingsOperationId) {
                return;
            }
            savingSettings = false;
        if (isError(event)) {
                setStatus(workerMessage(event, "设置保存失败。"), true);
            } else {
                settingsLoaded = true;
                AiSettings persisted = settingsFromEvent(event, values);
                applySettings(persisted);
                if (onSuccess != null) {
                    onSuccess.accept(persisted);
                }
            }
            applyModeEnabled(readSettings().mode());
            owner.rebuildAssistantWidgets();
        })) {
            savingSettings = false;
            setStatus("ModPedia Worker 不可用，设置未保存。", true);
            applyModeEnabled(values.mode());
            owner.rebuildAssistantWidgets();
        }
    }

    private void loadSettingsFromWorker() {
        long requestId = ++settingsRequestId;
        setStatus("正在读取设置……", false);
        if (!bridge.loadSettings(event -> {
            if (!owner.settingsPanelOpen() || requestId != settingsRequestId) {
                return;
            }
            settingsLoadRequested = false;
            if (isError(event)) {
                settingsLoaded = true;
                setStatus(workerMessage(event, "设置读取失败。"), true);
                applyModeEnabled(readSettings().mode());
                owner.rebuildAssistantWidgets();
                return;
            }
            JsonObject value = event.getAsJsonObject("settings");
            applySettings(WorkerPayloadCodec.aiSettings(value));
            settingsLoaded = true;
            applyModeEnabled(readSettings().mode());
            setStatus("", false);
            owner.rebuildAssistantWidgets();
        })) {
            settingsLoadRequested = false;
            settingsLoaded = true;
            setStatus("ModPedia Worker 不可用，暂时使用默认设置。", true);
            applyModeEnabled(readSettings().mode());
            owner.rebuildAssistantWidgets();
        }
    }

    private AiSettings settingsFromEvent(JsonObject event, AiSettings fallback) {
        JsonElement value = event == null ? null : event.get("settings");
        return value != null && value.isJsonObject()
                ? WorkerPayloadCodec.aiSettings(value.getAsJsonObject())
                : fallback;
    }

    private void applySettings(AiSettings values) {
        AiSettings actual = values == null ? AiSettings.defaults() : values;
        if (endpoint != null) endpoint.setValue(actual.endpoint());
        if (model != null) model.setValue(actual.model());
        if (apiKey != null) apiKey.setValue(actual.apiKey());
        if (maxRounds != null) maxRounds.setValue(Integer.toString(actual.maxRounds()));
        if (maxResults != null) maxResults.setValue(Integer.toString(actual.maxResults()));
        if (maxContextChars != null) maxContextChars.setValue(Integer.toString(actual.maxContextChars()));
        if (timeoutSeconds != null) timeoutSeconds.setValue(Integer.toString(actual.timeoutSeconds()));
        if (mode != null) mode.setValue(actual.mode());
        if (apiFormat != null) {
            apiFormat.setValue(actual.apiFormat());
            if (endpoint != null) endpoint.setHint(Component.translatable(endpointHintKey(actual.apiFormat())));
        }
        if (intensity != null) intensity.setValue(actual.intensity());
        if (streaming != null) streaming.setValue(actual.streaming());
        applyModeEnabled(actual.mode());
    }

    private boolean isError(JsonObject event) {
        return event == null || WorkerProtocol.ERROR.equals(WorkerProtocol.string(event, "type"))
                || WorkerProtocol.bool(event, "failed", false);
    }

    private String workerMessage(JsonObject event, String fallback) {
        String message = WorkerProtocol.string(event, "message").strip();
        return message.isBlank() ? fallback : message;
    }

    private List<String> modelIds(JsonObject event) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : WorkerPayloadCodec.array(event, "models")) {
            if (value.isJsonObject()) {
                String id = WorkerProtocol.string(value.getAsJsonObject(), "id").strip();
                if (!id.isBlank()) result.add(id);
            }
        }
        return List.copyOf(result);
    }

    private String normalizeEndpoint(String endpoint) {
        AiApiFormat selected = apiFormat == null ? AiApiFormat.CHAT_COMPLETIONS : apiFormat.getValue();
        return AiClient.normalizedEndpoint(endpoint, selected);
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
        // 所有行间距在 init() 中已经按照页面比例缩放过一次。
        // 这里的 offset 是页面坐标，不能再次 scaled，否则在小窗口中
        // 行距会被压缩，标签就会贴到甚至穿过下一个输入框。
        return positionAt(contentTop, offset, scrollOffset);
    }

    static int positionAt(int contentTop, int offset, int scrollOffset) {
        return contentTop + offset - scrollOffset;
    }

    private int scrollContentHeight() {
        int lastAdvancedControl = advancedControlOffset(3) + controlHeight;
        return lastAdvancedControl + scaled(BASE_CONTENT_BOTTOM_PADDING, 8);
    }

    private boolean compactLayout() {
        return width < 320;
    }

    private int footerRows(int fieldWidth) {
        return 2;
    }

    private int advancedControlOffset(int index) {
        return advancedLabelOffset(index) + labelControlGap;
    }

    private int advancedLabelOffset(int index) {
        int row = compactLayout() ? index : index / 2;
        return advancedHeadingOffset() + advancedHeadingGap + row * advancedRowGap;
    }

    private int mainLabelOffset(int index) {
        return index * mainRowGap;
    }

    private int mainControlOffset(int index) {
        return mainLabelOffset(index) + labelControlGap;
    }

    private int advancedHeadingOffset() {
        return mainLabelOffset(7);
    }

    private int advancedFieldX(int fieldLeft, int smallWidth, int index) {
        if (compactLayout() || index % 2 == 0) {
            return fieldLeft;
        }
        return fieldLeft + smallWidth + buttonGap;
    }

    private int advancedFieldWidth(int fieldWidth, int smallWidth) {
        return compactLayout() ? fieldWidth : smallWidth;
    }

    /**
     * 只把完整落在滚动视口内的控件交给渲染器，避免 EditBox 边框被页脚截成半框，
     * 以及标签在控件不可见时孤零零地停在边界上。
     */
    boolean contentWidgetVisible(net.minecraft.client.gui.components.AbstractWidget widget) {
        Bounds content = contentBounds();
        int right = widget.getX() + widget.getWidth();
        int bottom = widget.getY() + widget.getHeight();
        return widget.getX() >= content.left()
                && widget.getY() >= content.top()
                && right <= content.right()
                && bottom <= content.bottom();
    }

    private boolean controlFullyVisible(int controlOffset) {
        Bounds content = contentBounds();
        int controlTop = y(content.top(), controlOffset);
        return controlTop >= content.top()
                && controlTop + controlHeight <= content.bottom();
    }

    private boolean labelAndControlFullyVisible(int labelOffset, int controlOffset) {
        Bounds content = contentBounds();
        int labelTop = y(content.top(), labelOffset);
        int controlTop = y(content.top(), controlOffset);
        return labelTop >= content.top()
                && labelTop + font.lineHeight <= content.bottom()
                && controlTop >= content.top()
                && controlTop + controlHeight <= content.bottom();
    }

    private boolean textFullyVisible(int offset, int textHeight) {
        Bounds content = contentBounds();
        int textTop = y(content.top(), offset);
        return textTop >= content.top()
                && textTop + textHeight <= content.bottom();
    }

    private void clampScroll() {
        if (bottom <= top) {
            scrollOffset = 0;
            return;
        }
        Bounds content = contentBounds();
        int max = Math.max(0, scrollContentHeight() - content.height());
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

    private Component apiFormatLabel(AiApiFormat value) {
        String key = switch (value == null ? AiApiFormat.CHAT_COMPLETIONS : value) {
            case CHAT_COMPLETIONS -> "screen.modpedia.ai_api_format_chat";
            case NATIVE_MESSAGES -> "screen.modpedia.ai_api_format_native";
            case RESPONSES -> "screen.modpedia.ai_api_format_responses";
            case GENERATE_CONTENT -> "screen.modpedia.ai_api_format_generate_content";
        };
        return Component.translatable(key);
    }

    private String endpointHintKey(AiApiFormat value) {
        return switch (value == null ? AiApiFormat.CHAT_COMPLETIONS : value) {
            case NATIVE_MESSAGES -> "screen.modpedia.ai_endpoint_hint_native";
            case RESPONSES -> "screen.modpedia.ai_endpoint_hint_responses";
            case GENERATE_CONTENT -> "screen.modpedia.ai_endpoint_hint_generate_content";
            case CHAT_COMPLETIONS -> "screen.modpedia.ai_endpoint_hint";
        };
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

    private int scaled(int value, int minimum) {
        return Math.max(minimum, (int) Math.round(value * scale));
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

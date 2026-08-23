package io.ctyx.modpedia.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    private static final int MODEL_BASE = 100;
    private static final int HISTORY_SELECT_BASE = 200;
    private static final int HISTORY_DELETE_BASE = 300;

    private final GuiScreen previousScreen;
    private final LegacyTargetStore.Target frozenTarget;
    private final List<TokenHit> tokenHits = new ArrayList<TokenHit>();
    private final List<ModelOption> models = new ArrayList<ModelOption>();
    private final List<ConversationSummary> conversations = new ArrayList<ConversationSummary>();

    private Panel panel = Panel.MAIN;
    private GuiTextField input;
    private GuiTextField endpointField;
    private GuiTextField modelField;
    private GuiTextField apiKeyField;
    private String answer = "输入问题后按 Enter，或点击发送。\n\nWorker 未就绪时会回退到本地 Markdown 搜索。";
    private String status = "就绪";
    private String lastPrompt = "";
    private String activeConversationId = "";
    private String activeConversationTitle = "";
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

    public LegacyAssistantScreen(GuiScreen previousScreen, LegacyTargetStore.Target frozenTarget) {
        this.previousScreen = previousScreen;
        this.frozenTarget = frozenTarget;
    }

    @Override
    public void initGui() {
        int panelWidth = panelWidth();
        int left = panelLeft(panelWidth);
        int bottom = height - 20;
        input = new GuiTextField(10, fontRenderer, left + 12, bottom - 24, panelWidth - 150, 20);
        input.setMaxStringLength(4000);
        input.setFocused(true);

        endpointField = new GuiTextField(11, fontRenderer, left + 12, 0, panelWidth - 24, 20);
        modelField = new GuiTextField(12, fontRenderer, left + 12, 0, panelWidth - 24, 20);
        apiKeyField = new GuiTextField(13, fontRenderer, left + 12, 0, panelWidth - 24, 20);
        endpointField.setMaxStringLength(500);
        modelField.setMaxStringLength(200);
        apiKeyField.setMaxStringLength(500);
        endpointField.setCanLoseFocus(true);
        modelField.setCanLoseFocus(true);
        apiKeyField.setCanLoseFocus(true);
        rebuildFields();
        rebuildButtons();

        if (LegacyWorkerBridge.get().isReady()) {
            loadSettings();
            loadConversations();
        } else {
            status = "Worker 未就绪：可使用本地搜索";
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int panelWidth = panelWidth();
        int left = panelLeft(panelWidth);
        int top = 18;
        int bottom = height - 14;
        drawRect(left, top, left + panelWidth, bottom, 0xF0181820);
        drawRect(left, top, left + panelWidth, top + 28, 0xFF252A36);
        drawString(fontRenderer, panel == Panel.MAIN ? "ModPedia · 1.12.2"
                : panel == Panel.SETTINGS ? "助手设置" : "历史会话", left + 12, top + 9, 0xFFFFFF);
        drawString(fontRenderer, status, left + 12, top + 34, 0xAAB7C4);

        if (panel == Panel.MAIN) {
            drawMain(left, panelWidth, top, bottom);
        } else if (panel == Panel.SETTINGS) {
            drawSettings(left, panelWidth, top, bottom);
        } else {
            drawHistory(left, top, bottom);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == CLOSE) {
            close();
        } else if (button.id == HISTORY && panel == Panel.MAIN) {
            panel = Panel.HISTORY;
            input.setFocused(false);
            rebuildFields();
            rebuildButtons();
            loadConversations();
        } else if (button.id == SETTINGS && panel == Panel.MAIN) {
            panel = Panel.SETTINGS;
            input.setFocused(false);
            rebuildFields();
            rebuildButtons();
            if (!settingsLoaded) {
                loadSettings();
            }
        } else if (button.id == BACK) {
            panel = Panel.MAIN;
            input.setFocused(true);
            rebuildFields();
            rebuildButtons();
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
            if (panel != Panel.MAIN) {
                panel = Panel.MAIN;
                input.setFocused(true);
                rebuildFields();
                rebuildButtons();
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
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (panel == Panel.MAIN && mouseButton == 0 && (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))) {
            for (TokenHit hit : tokenHits) {
                if (hit.contains(mouseX, mouseY) && LegacyJeiBridge.showItem(hit.itemValue)) {
                    return;
                }
            }
        }
        if (panel == Panel.MAIN && input != null) {
            input.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (panel == Panel.SETTINGS) {
            endpointField.mouseClicked(mouseX, mouseY, mouseButton);
            modelField.mouseClicked(mouseX, mouseY, mouseButton);
            apiKeyField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void onGuiClosed() {
        closed = true;
        if (activeRequestId != null) {
            LegacyWorkerBridge.get().cancelChat(activeRequestId);
        }
        LegacyTargetStore.get().release();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawMain(int left, int panelWidth, int top, int bottom) {
        tokenHits.clear();
        int y = top + 50;
        String[] markdownLines = answer.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : markdownLines) {
            int lineTop = y;
            String formatted = markdownLine(line);
            List<String> wrapped = fontRenderer.listFormattedStringToWidth(formatted, panelWidth - 24);
            if (wrapped.isEmpty()) {
                y += 10;
                continue;
            }
            for (String wrappedLine : wrapped) {
                if (y > bottom - 54) {
                    break;
                }
                drawString(fontRenderer, wrappedLine, left + 12, y,
                        wrappedLine.startsWith("##") ? 0xFFE2B35C : 0xFFE8E8E8);
                y += 10;
            }
            if (y > bottom - 54) {
                break;
            }
            collectTokenHits(line, left + 12, lineTop);
        }
        if (frozenTarget != null && !frozenTarget.getItemId().isEmpty()) {
            drawString(fontRenderer, "当前目标：" + frozenTarget.getDisplayName()
                    + "（点击“插入”后才会写入问题）", left + 12, bottom - 42, 0xFF91D5FF);
        }
        drawString(fontRenderer, busy ? "处理中……" : "按 Shift+左键点击回答中的物品可打开配方",
                left + 12, bottom - 30, 0xFF8892A0);
        input.drawTextBox();
    }

    private void drawSettings(int left, int panelWidth, int top, int bottom) {
        int label = 0xFFB9C5D0;
        int fieldY = top + 116;
        drawString(fontRenderer, "工作模式", left + 12, top + 52, label);
        drawString(fontRenderer, onlySearch ? "当前：本地搜索" : "当前：AI + 工具",
                left + 120, top + 52, 0xFFE8E8E8);

        drawString(fontRenderer, "API 地址", left + 12, fieldY - 12, label);
        endpointField.drawTextBox();
        fieldY += 32;
        drawString(fontRenderer, "模型", left + 12, fieldY - 12, label);
        modelField.drawTextBox();
        fieldY += 32;
        drawString(fontRenderer, "API Key", left + 12, fieldY - 12, label);
        drawMaskedField(apiKeyField);

        int protocolY = top + 212;
        drawString(fontRenderer, "协议", left + 12, protocolY, label);
        drawString(fontRenderer, pretty(apiFormat), left + 66, protocolY, 0xFFE8E8E8);
        drawString(fontRenderer, "搜索强度", left + panelWidth / 2, protocolY, label);
        drawString(fontRenderer, pretty(intensity), left + panelWidth / 2 + 82,
                protocolY, 0xFFE8E8E8);

        int controlsTop = top + 232;
        int listLabel = controlsTop + 52;
        int listRow = listLabel + 18;
        drawString(fontRenderer, "模型列表（点击右侧“使用”填入）", left + 12, listLabel, label);
        if (models.isEmpty()) {
            drawString(fontRenderer, settingsBusy ? "正在读取……" : "尚未获取模型列表",
                    left + 12, listRow, 0xFF8D99A6);
        } else {
            int limit = Math.min(models.size(), Math.max(0, (bottom - listRow - 32) / 18));
            for (int index = 0; index < limit; index++) {
                drawString(fontRenderer, models.get(index).id,
                        left + 14, listRow + index * 18, 0xFFB9DFFF);
            }
        }
        if (!LegacyWorkerBridge.get().isReady()) {
            drawString(fontRenderer, "Worker 未连接，设置读写和模型测试暂不可用。",
                    left + 12, bottom - 42, 0xFFFFB0A0);
        } else if (settingsLoaded) {
            drawString(fontRenderer, settingsDirty
                            ? "有未保存设置；API Key 由 Worker 加密保存。"
                            : "API Key 已隐藏；保存后由 Worker 加密保存。",
                    left + 12, bottom - 42, 0xFF8D99A6);
        }
    }

    private void drawHistory(int left, int top, int bottom) {
        if (conversations.isEmpty()) {
            drawString(fontRenderer, "暂无历史会话", left + 12, top + 58, 0xFFB9C5D0);
            return;
        }
        int panelWidth = panelWidth();
        int titleWidth = Math.max(40, panelWidth - 142);
        int row = top + 54;
        int limit = Math.min(conversations.size(), Math.max(1, (bottom - row - 26) / 24));
        for (int index = 0; index < limit; index++) {
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
        lastPrompt = prompt;
        retryAvailable = false;
        busy = true;
        status = onlySearch ? "正在搜索" : "正在处理";
        answer = "";
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
                        answer = localAnswer;
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
        String type = value(event, "type");
        if ("text_delta".equals(type)) {
            answer += value(event, "text");
            status = onlySearch ? "正在整理搜索结果" : "正在生成回答";
        } else if ("status".equals(type)) {
            status = value(event, "message");
        } else if ("completed".equals(type)) {
            if (event.has("conversations") && event.has("active_conversation_id")) {
                applyConversationState(event);
            }
            String finalAnswer = value(event, "answer");
            if (!finalAnswer.isEmpty()) {
                answer = finalAnswer;
            }
            status = "完成";
            busy = false;
            activeRequestId = null;
            retryAvailable = false;
            rebuildButtons();
        } else if ("error".equals(type)) {
            if (event.has("conversations") && event.has("active_conversation_id")) {
                applyConversationState(event);
            }
            answer = value(event, "message");
            status = isTimeoutMessage(answer) ? "请求超时，可点击重试" : "请求失败，可点击重试";
            busy = false;
            activeRequestId = null;
            retryAvailable = !lastPrompt.isEmpty();
            rebuildButtons();
        } else if ("cancelled".equals(type)) {
            status = "已取消，可点击重试";
            busy = false;
            activeRequestId = null;
            retryAvailable = !lastPrompt.isEmpty();
            rebuildButtons();
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

    private void loadConversations() {
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
                    answer = "新会话已创建。";
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
        status = "正在切换会话";
        LegacyWorkerBridge.get().selectConversation(conversationId).whenComplete((event, failure) -> onClient(new Runnable() {
            @Override
            public void run() {
                if (failure != null || event == null) {
                    status = "会话切换失败";
                    return;
                }
                applyConversationState(event);
                lastPrompt = "";
                retryAvailable = false;
                status = "会话已切换";
                panel = Panel.MAIN;
                input.setFocused(true);
                rebuildFields();
                rebuildButtons();
            }
        }));
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
                    answer = "当前会话已清空。";
                    status = "会话已清空";
                    rebuildButtons();
                }
            }
        }));
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
        activeConversationId = value(event, "active_conversation_id");
        activeConversationTitle = value(event, "active_title");
        conversations.clear();
        JsonElement values = event.get("conversations");
        if (values != null && values.isJsonArray()) {
            for (JsonElement element : values.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject summary = element.getAsJsonObject();
                    conversations.add(new ConversationSummary(
                            value(summary, "id"), value(summary, "title"),
                            intValue(summary, "message_count", 0)
                    ));
                }
            }
        }
        String latestAnswer = "";
        JsonElement messages = event.get("messages");
        if (messages != null && messages.isJsonArray()) {
            for (JsonElement element : messages.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                if ("ASSISTANT".equalsIgnoreCase(value(message, "role"))
                        || "assistant".equalsIgnoreCase(value(message, "role"))) {
                    latestAnswer = value(message, "content");
                }
            }
        }
        if (!latestAnswer.isEmpty()) {
            answer = latestAnswer;
        } else {
            answer = "该会话暂无助手回答。";
        }
    }

    private void rebuildFields() {
        if (input == null) {
            return;
        }
        int panelWidth = panelWidth();
        int left = panelLeft(panelWidth);
        int top = 18;
        int y = top + 116;
        endpointField.x = left + 12;
        endpointField.y = y;
        endpointField.width = panelWidth - 24;
        y += 32;
        modelField.x = left + 12;
        modelField.y = y;
        modelField.width = panelWidth - 24;
        y += 32;
        apiKeyField.x = left + 12;
        apiKeyField.y = y;
        apiKeyField.width = panelWidth - 24;
        input.setVisible(panel == Panel.MAIN);
        endpointField.setVisible(panel == Panel.SETTINGS);
        modelField.setVisible(panel == Panel.SETTINGS);
        apiKeyField.setVisible(panel == Panel.SETTINGS);
        boolean aiEnabled = !onlySearch;
        endpointField.setEnabled(aiEnabled);
        modelField.setEnabled(aiEnabled);
        apiKeyField.setEnabled(aiEnabled);
    }

    private void rebuildButtons() {
        if (buttonList == null) {
            return;
        }
        int panelWidth = panelWidth();
        int left = panelLeft(panelWidth);
        int top = 18;
        int bottom = height - 20;
        buttonList.clear();
        buttonList.add(new GuiButton(CLOSE, left + panelWidth - 58, top + 5, 46, 20, "关闭"));
        if (panel == Panel.MAIN) {
            buttonList.add(new GuiButton(HISTORY, left + panelWidth - 164, top + 5, 48, 20, "历史"));
            buttonList.add(new GuiButton(SETTINGS, left + panelWidth - 110, top + 5, 48, 20, "设置"));
            if (busy) {
                GuiButton cancel = new GuiButton(CANCEL, left + panelWidth - 132, bottom - 24, 58, 20, "取消");
                cancel.enabled = activeRequestId != null;
                buttonList.add(cancel);
            } else {
                buttonList.add(new GuiButton(SEND, left + panelWidth - 132, bottom - 24, 58, 20,
                        retryAvailable ? "重试" : "发送"));
            }
            buttonList.add(new GuiButton(INSERT, left + panelWidth - 70, bottom - 24, 58, 20, "插入"));
        } else if (panel == Panel.SETTINGS) {
            buttonList.add(new GuiButton(BACK, left + 12, top + 5, 48, 20, "返回"));
            GuiButton save = new GuiButton(SETTINGS_SAVE, left + 12, bottom - 24, 58, 20, "保存");
            save.enabled = !settingsBusy && LegacyWorkerBridge.get().isReady();
            buttonList.add(save);
            GuiButton fetch = new GuiButton(SETTINGS_FETCH_MODELS, left + 76, bottom - 24, 78, 20, "获取模型");
            fetch.enabled = !settingsBusy && !onlySearch && LegacyWorkerBridge.get().isReady();
            buttonList.add(fetch);
            GuiButton test = new GuiButton(SETTINGS_TEST_CONNECTION, left + 160, bottom - 24, 78, 20, "测试连接");
            test.enabled = !settingsBusy && !onlySearch && LegacyWorkerBridge.get().isReady();
            buttonList.add(test);
            buttonList.add(new GuiButton(SETTINGS_MODE_AI, left + 12, top + 66, 90, 20,
                    onlySearch ? "AI 回答" : "AI 回答 ✓"));
            buttonList.add(new GuiButton(SETTINGS_MODE_SEARCH, left + 108, top + 66, 90, 20,
                    onlySearch ? "仅搜索 ✓" : "仅搜索"));
            int controlsTop = top + 232;
            int columnWidth = Math.max(80, (panelWidth - 36) / 2);
            int secondColumn = left + 12 + columnWidth + 6;
            int secondWidth = Math.max(80, panelWidth - 30 - columnWidth);
            GuiButton stream = new GuiButton(SETTINGS_STREAMING, left + 12, controlsTop,
                    columnWidth, 20,
                    streaming ? "流式：开" : "流式：关");
            stream.enabled = !onlySearch;
            buttonList.add(stream);
            GuiButton format = new GuiButton(SETTINGS_FORMAT, secondColumn, controlsTop,
                    secondWidth, 20,
                    "协议：" + pretty(apiFormat));
            format.enabled = !onlySearch;
            buttonList.add(format);
            buttonList.add(new GuiButton(SETTINGS_INTENSITY, left + 12, controlsTop + 24,
                    columnWidth, 20,
                    "强度：" + pretty(intensity)));
            int row = controlsTop + 70;
            int limit = Math.min(models.size(), Math.max(0, (bottom - row - 32) / 18));
            for (int index = 0; index < limit; index++) {
                buttonList.add(new GuiButton(MODEL_BASE + index, left + panelWidth - 120,
                        row + index * 18 - 4, 108, 18, "使用"));
            }
        } else {
            buttonList.add(new GuiButton(BACK, left + 12, top + 5, 48, 20, "返回"));
            buttonList.add(new GuiButton(HISTORY_NEW, left + 68, top + 5, 58, 20, "新建"));
            buttonList.add(new GuiButton(HISTORY_CLEAR, left + 132, top + 5, 58, 20, "清空"));
            int row = top + 54;
            int limit = Math.min(conversations.size(), Math.max(0, (bottom - row - 26) / 24));
            for (int index = 0; index < limit; index++) {
                buttonList.add(new GuiButton(HISTORY_SELECT_BASE + index, left + panelWidth - 120,
                        row + index * 24 - 4, 52, 18, "打开"));
                buttonList.add(new GuiButton(HISTORY_DELETE_BASE + index, left + panelWidth - 62,
                        row + index * 24 - 4, 52, 18, "删除"));
            }
        }
    }

    private void close() {
        Minecraft.getMinecraft().displayGuiScreen(previousScreen);
    }

    private String markdownLine(String line) {
        String value = line.replace("**", "").replace("__", "");
        if (value.startsWith("#")) {
            value = "§e" + value.replaceFirst("^#+\\s*", "");
        }
        Matcher matcher = ITEM_TOKEN.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(0);
            String display = matcher.group(2);
            String shown = LegacyItemCatalogSyncService.get().displayName(raw, showIds());
            if (display != null && !display.trim().isEmpty() && !showIds()) {
                shown = display;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement("§b" + shown + "§r"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void collectTokenHits(String line, int x, int y) {
        Matcher matcher = ITEM_TOKEN.matcher(line);
        while (matcher.find()) {
            String raw = matcher.group(0);
            String shown = matcher.group(2) == null || matcher.group(2).trim().isEmpty()
                    ? LegacyItemCatalogSyncService.get().displayName(raw, false) : matcher.group(2);
            int start = fontRenderer.getStringWidth(markdownLine(line.substring(0, matcher.start())));
            int tokenWidth = fontRenderer.getStringWidth(shown);
            tokenHits.add(new TokenHit(x + start, y, tokenWidth, 10, raw));
        }
    }

    private void onClient(Runnable runnable) {
        try {
            Minecraft.getMinecraft().addScheduledTask(runnable);
        } catch (Throwable ignored) {
            // GUI 已关闭时丢弃异步回调。
        }
    }

    private int panelWidth() {
        return Math.min(620, Math.max(280, width - 24));
    }

    private int panelLeft(int panelWidth) {
        return (width - panelWidth) / 2;
    }

    private boolean showIds() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
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

    private enum Panel {
        MAIN,
        SETTINGS,
        HISTORY
    }
}

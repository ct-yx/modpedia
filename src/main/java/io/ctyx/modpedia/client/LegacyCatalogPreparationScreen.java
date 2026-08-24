package io.ctyx.modpedia.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;

/**
 * 进入世界前的物品目录准备页。
 *
 * <p>它不是在主线程上等待扫描，而是把“开始进入世界”的按钮动作暂存，
 * 让 ClientTick 继续以小批次捕获基础目录；完成后由 ClientProxy 恢复原按钮
 * 动作。这样既不会在世界线程里全量生成 Tooltip，也不会让半成品目录覆盖
 * 已有目录。</p>
 */
public final class LegacyCatalogPreparationScreen extends GuiScreen {
    private final GuiScreen parent;
    private final Runnable completedAction;
    private final Runnable cancelledAction;
    private boolean finished;

    public LegacyCatalogPreparationScreen(GuiScreen parent, Runnable completedAction) {
        this(parent, completedAction, null);
    }

    public LegacyCatalogPreparationScreen(GuiScreen parent, Runnable completedAction,
            Runnable cancelledAction) {
        this.parent = parent;
        this.completedAction = completedAction;
        this.cancelledAction = cancelledAction;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 75, height - 42, 150, 20, "返回"));
    }

    @Override
    public void updateScreen() {
        LegacyItemCatalogProgress.Snapshot snapshot =
                LegacyItemCatalogSyncService.get().progress();
        if (!finished && snapshot.phase() == LegacyItemCatalogProgress.Phase.COMPLETED) {
            finished = true;
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (completedAction != null) {
                        completedAction.run();
                    }
                }
            });
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button != null && button.id == 0) {
            if (cancelledAction != null) {
                cancelledAction.run();
            }
            Minecraft.getMinecraft().displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "正在准备 ModPedia 物品目录",
                width / 2, 28, 0xFFFFFFFF);

        LegacyItemCatalogProgress.Snapshot snapshot =
                LegacyItemCatalogSyncService.get().progress();
        int total = Math.max(1, snapshot.registeredItems());
        int processed = Math.min(total, snapshot.processedVariants());
        int barWidth = Math.min(420, Math.max(220, width - 40));
        int left = (width - barWidth) / 2;
        int top = height / 2 - 24;
        drawRect(left, top, left + barWidth, top + 10, 0xFF30343B);
        drawRect(left, top, left + (barWidth * processed / total), top + 10,
                0xFF6FA9D4);

        String status = phase(snapshot.phase()) + "  " + processed + " / " + total;
        drawCenteredString(fontRenderer, status, width / 2, top + 20, 0xFFE8EEF5);
        drawCenteredString(fontRenderer,
                "进入世界前完成基础名称导入；Tooltip 将在查询时按需读取",
                width / 2, top + 38, 0xFFB9C5D0);
        drawCenteredString(fontRenderer,
                "已写入 " + snapshot.syncedEntries() + " · 失败 " + snapshot.failedVariants(),
                width / 2, top + 54, 0xFF8E9AAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String phase(LegacyItemCatalogProgress.Phase value) {
        switch (value) {
            case WAITING:
                return "等待扫描";
            case SCANNING:
                return "扫描中";
            case PAUSED:
                return "已暂停";
            case WAITING_FOR_WORKER:
                return "等待 Worker 写入";
            case SYNCING:
                return "写入中";
            case COMPLETED:
                return "已完成";
            default:
                return "准备中";
        }
    }
}

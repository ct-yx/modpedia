package io.ctyx.modpedia.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.ctyx.modpedia.knowledge.LegacyFtbQuestRuntimeReader;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 服务端到客户端的 FTBQ 运行时快照同步包。
 *
 * <p>快照只在内存中传输和保存，不写入客户端 knowledge.db，也不包含 API 配置。</p>
 */
public final class LegacyFtbQuestSnapshotMessage implements IMessage {
    private String scopeKey = "";
    private String snapshotJson = "";

    public LegacyFtbQuestSnapshotMessage() {
    }

    public LegacyFtbQuestSnapshotMessage(String scopeKey, JsonElement snapshot) {
        this.scopeKey = scopeKey == null ? "" : scopeKey;
        this.snapshotJson = snapshot == null ? "" : snapshot.toString();
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        scopeKey = ByteBufUtils.readUTF8String(buffer);
        snapshotJson = ByteBufUtils.readUTF8String(buffer);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        ByteBufUtils.writeUTF8String(buffer, scopeKey == null ? "" : scopeKey);
        ByteBufUtils.writeUTF8String(buffer, snapshotJson == null ? "" : snapshotJson);
    }

    public static final class Handler
            implements IMessageHandler<LegacyFtbQuestSnapshotMessage, IMessage> {
        @Override
        public IMessage onMessage(
                final LegacyFtbQuestSnapshotMessage message,
                MessageContext context
        ) {
            if (context.side != Side.CLIENT) {
                return null;
            }
            FMLCommonHandler.instance().getWorldThread(context.netHandler)
                    .addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            if (message.snapshotJson == null || message.snapshotJson.trim().isEmpty()) {
                                LegacyFtbQuestRuntimeReader.clearRemoteSnapshot();
                                return;
                            }
                            try {
                                JsonElement parsed = new JsonParser().parse(message.snapshotJson);
                                if (parsed != null && parsed.isJsonObject()) {
                                    LegacyFtbQuestRuntimeReader.applyRemoteSnapshot(
                                            message.scopeKey, parsed.getAsJsonObject()
                                    );
                                    return;
                                }
                            } catch (RuntimeException ignored) {
                                // 损坏或过期的同步包按不可用处理，不影响客户端继续运行。
                            }
                            LegacyFtbQuestRuntimeReader.clearRemoteSnapshot();
                        }
                    });
            return null;
        }
    }
}

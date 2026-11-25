package gg.phase.space;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Сервер → клиент: открытия игрока (через запятую: c:kitsune, o:venus, v:mars, d:yomi) */
public record AtlasPayload(String data) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AtlasPayload> TYPE = new CustomPacketPayload.Type<>(PhaseSpace.id("atlas"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AtlasPayload> CODEC = CustomPacketPayload.codec(AtlasPayload::write, AtlasPayload::new);

	public AtlasPayload(RegistryFriendlyByteBuf buf) {
		this(buf.readUtf());
	}

	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(data);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

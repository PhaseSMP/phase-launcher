package gg.phase.space;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Сервер → клиент: id сущностей игроков с силой вильтрумита (через запятую) — для следов и хлопков */
public record ViltrumPayload(String ids) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ViltrumPayload> TYPE = new CustomPacketPayload.Type<>(PhaseSpace.id("viltrum"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ViltrumPayload> CODEC = CustomPacketPayload.codec(ViltrumPayload::write, ViltrumPayload::new);

	public ViltrumPayload(RegistryFriendlyByteBuf buf) {
		this(buf.readUtf());
	}

	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(ids);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

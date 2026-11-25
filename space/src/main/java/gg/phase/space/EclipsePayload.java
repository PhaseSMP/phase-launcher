package gg.phase.space;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Сервер → клиент: параметры затмения (active = false — остановлено) */
public record EclipsePayload(boolean active, long start, int dur, double x, double y, double z, double hx, double hz, long seed, boolean fuji)
	implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EclipsePayload> TYPE = new CustomPacketPayload.Type<>(PhaseSpace.id("eclipse"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EclipsePayload> CODEC = CustomPacketPayload.codec(EclipsePayload::write, EclipsePayload::new);
	public static final EclipsePayload STOP = new EclipsePayload(false, 0, 0, 0, 0, 0, 0, 0, 0, false);

	public static EclipsePayload of(EclipseChaos.State s) {
		return new EclipsePayload(true, s.start, s.dur, s.x, s.y, s.z, s.hx, s.hz, s.seed, s.fuji);
	}

	public EclipseChaos.State toState() {
		EclipseChaos.State s = new EclipseChaos.State();
		s.start = start; s.dur = dur; s.x = x; s.y = y; s.z = z; s.hx = hx; s.hz = hz; s.seed = seed; s.fuji = fuji;
		return s;
	}

	public EclipsePayload(RegistryFriendlyByteBuf buf) {
		this(buf.readBoolean(), buf.readLong(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readDouble(), buf.readDouble(), buf.readLong(), buf.readBoolean());
	}

	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeBoolean(active);
		buf.writeLong(start);
		buf.writeVarInt(dur);
		buf.writeDouble(x);
		buf.writeDouble(y);
		buf.writeDouble(z);
		buf.writeDouble(hx);
		buf.writeDouble(hz);
		buf.writeLong(seed);
		buf.writeBoolean(fuji);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

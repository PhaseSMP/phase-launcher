package gg.phase.cosmetics;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Табличка над ником: тег клана слева в цвете клана, значок справа —
 * ♛ основатель, ✦ администрация, ✎ медиа, ★ Max, ☆ Pro. Только на клиенте, сервер не трогаем.
 */
public final class NameTags {
	private NameTags() {}

	static int clanColor(String c) {
		if (c == null) return 0xFF3B47;
		return switch (c) {
			case "sakura" -> 0xFF8FB8;
			case "gold" -> 0xE8B53C;
			case "jade" -> 0x3CC48A;
			case "ice" -> 0x6CC8F0;
			case "violet" -> 0xB36BFF;
			default -> 0xFF3B47;
		};
	}

	public static Component decorate(Component name, CosmeticsData.Worn w) {
		MutableComponent out = Component.empty();
		if (w.clan() != null && !w.clan().isBlank()) {
			out.append(Component.literal("[" + w.clan() + "] ").withColor(clanColor(w.clanColor())));
		}
		out.append(name);
		String b = w.badge();
		if (b != null) {
			switch (b) {
				case "owner" -> out.append(Component.literal(" ♛").withColor(0xFFD36B));
				case "admin" -> out.append(Component.literal(" ✦").withColor(0xFF3B47));
				case "media" -> out.append(Component.literal(" ✎").withColor(0xB36BFF));
				case "max" -> out.append(Component.literal(" ★").withColor(0xFF5A4E));
				case "pro" -> out.append(Component.literal(" ☆").withColor(0x6CC8F0));
				default -> { }
			}
		}
		return out;
	}
}

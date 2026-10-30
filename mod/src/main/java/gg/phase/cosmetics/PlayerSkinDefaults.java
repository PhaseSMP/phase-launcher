package gg.phase.cosmetics;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/** Стандартный скин для ника (как у игры без лицензии) — им дополняем, если загружен только плащ */
final class PlayerSkinDefaults {
	private PlayerSkinDefaults() {}

	static PlayerSkin of(String name) {
		return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
	}
}

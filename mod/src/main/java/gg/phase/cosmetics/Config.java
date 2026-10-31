package gg.phase.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Настройки игрока (config/phase-cosmetics.json). Команда питомцу хранится и на сервере — её видят все. */
public final class Config {
	public boolean sounds = true;
	public boolean others = true;
	public boolean hideHelmet = true;
	public boolean ownAura = true;
	public String mode = "follow";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config INSTANCE;

	public static Config get() {
		if (INSTANCE == null) {
			try {
				INSTANCE = GSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), Config.class);
			} catch (Exception e) {
				INSTANCE = null;
			}
			if (INSTANCE == null) INSTANCE = new Config();
			if (INSTANCE.mode == null) INSTANCE.mode = "follow";
		}
		return INSTANCE;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("phase-cosmetics.json");
	}
}

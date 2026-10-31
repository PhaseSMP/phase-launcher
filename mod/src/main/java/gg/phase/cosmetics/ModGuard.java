package gg.phase.cosmetics;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Запрет своих модов: лаунчер Phase передаёт игре список SHA-1 разрешённых файлов (-Dphase.mods).
 * Если в игру загружен мод не из сборки — игра не запустится и покажет, какой файл лишний.
 * Без этого параметра (игра запущена не лаунчером Phase) проверка не выполняется.
 */
final class ModGuard {
	private ModGuard() {}

	static void check() {
		String allow = System.getProperty("phase.mods");
		if (allow == null) return;
		Set<String> ok = new HashSet<>();
		for (String h : allow.split(",")) if (!h.isBlank()) ok.add(h.trim().toLowerCase(Locale.ROOT));
		Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
		List<String> bad = new ArrayList<>();
		Set<Path> seen = new HashSet<>();
		for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
			if (m.getContainingMod().isPresent()) continue; // вложенные модули (например, части Fabric API)
			for (Path p : m.getOrigin().getPaths()) {
				Path abs = p.toAbsolutePath().normalize();
				if (!seen.add(abs) || Files.isDirectory(abs)) continue;
				String name = abs.getFileName().toString();
				if (!name.endsWith(".jar")) continue;
				// всё, что не из системных библиотек игры: и из mods, и подложенное другими путями
				boolean fromGame = abs.startsWith(modsDir);
				boolean builtin = m.getMetadata().getId().equals("minecraft") || m.getMetadata().getId().equals("java") || m.getMetadata().getId().equals("fabricloader")
					|| m.getMetadata().getType().equals("builtin");
				if (builtin && !fromGame) continue;
				String sha = sha1(abs);
				if (sha == null || !ok.contains(sha)) bad.add(name);
			}
		}
		if (!bad.isEmpty()) {
			throw new IllegalStateException("Phase: посторонние моды запрещены — " + String.join(", ", bad)
				+ ". Удалите их и запустите игру из лаунчера Phase: на сервере можно играть только со сборкой лаунчера.");
		}
	}

	private static String sha1(Path p) {
		try (InputStream in = Files.newInputStream(p)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
			return HexFormat.of().formatHex(md.digest());
		} catch (Exception e) {
			return null;
		}
	}
}

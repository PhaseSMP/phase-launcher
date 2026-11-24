package gg.phase.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.border.WorldBorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * config/phaseworld.properties
 *   border_size=7400      world border edge length (6400 = from -3200 to 3200), 0 = don't touch
 *   border_applied=false  set to true after the first apply; set back to false to re-apply a new size
 */
public final class PhaseConfig {
	private PhaseConfig() {
	}

	public static void applyWorldBorder(MinecraftServer server) {
		Path file = Path.of("config", "phaseworld.properties");
		Properties p = new Properties();
		try {
			if (Files.exists(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					p.load(in);
				}
			}
			double size = Double.parseDouble(p.getProperty("border_size", "6400"));
			boolean applied = Boolean.parseBoolean(p.getProperty("border_applied", "false"));
			if (size > 0 && !applied) {
				WorldBorder border = server.overworld().getWorldBorder();
				border.setCenter(0, 0);
				border.setSize(size);
				PhaseWorld.LOG.info("World border set to {} blocks around 0,0", size);
				applied = true;
			}
			p.setProperty("border_size", Long.toString((long) size));
			p.setProperty("border_applied", Boolean.toString(applied));
			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				p.store(out, "Phase World. border_size: edge length of the world border (6400 = -3200..3200). "
					+ "Set border_applied=false to apply a new size on next start, or use /worldborder.");
			}
		} catch (IOException | RuntimeException e) {
			PhaseWorld.LOG.warn("Could not apply Phase world border", e);
		}
	}
}

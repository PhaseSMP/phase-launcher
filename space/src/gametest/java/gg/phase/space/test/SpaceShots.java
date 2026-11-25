package gg.phase.space.test;

import gg.phase.space.SolarSystem;
import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Comet;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CI-снимки настоящего клиента: планеты, пояс, комета, дальний космос (Ёми, Аматэрасу, TON 618,
 * Идзанаги и Идзанами, Кагура) и поверхности нескольких миров. Положения тел берутся из
 * той же модели орбит, что и в моде, на момент снимка.
 */
public class SpaceShots implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("Phase Space shots");
	private static final double[] P = new double[3];

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.options.renderDistance().set(8);
			mc.options.simulationDistance().set(5);
			mc.options.fov().set(80);
		});
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("20261124"))
			.create()) {
			waitRender(sp, "spawn");
			cmd(sp, "gamemode spectator @p");
			ctx.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
			look(ctx, sp, "01-earth", "earth", -110, 30, 70);
			look(ctx, sp, "02-earth-moon", "moon", -45, 14, -40);
			lookSun(ctx, sp, "03-sun", 1250);
			look(ctx, sp, "04-mars", "mars", 60, 16, -80);
			look(ctx, sp, "05-sakura", "sakura", -40, 12, 30);
			look(ctx, sp, "06-florana", "florana", -140, 35, 90);
			look(ctx, sp, "07-florana-lumi", "lumi", -30, 8, 22);
			look(ctx, sp, "08-jupiter", "jupiter", 520, 60, -380);
			look(ctx, sp, "09-saturn", "saturn", -440, 130, 420);
			look(ctx, sp, "10-uranus", "uranus", 200, 50, 180);
			look(ctx, sp, "11-neptune", "neptune", -180, 30, -150);
			beltShot(ctx, sp);
			cometShot(ctx, sp);
			look(ctx, sp, "14-pluto-charon", "pluto", 60, 15, 45);
			look(ctx, sp, "15-haumea", "haumea", 40, 10, 30);
			deepShot(ctx, sp, "16-black-hole", "yomi", 4200, 1300, 2600);
			deepShot(ctx, sp, "17-nebula", "amaterasu", -9000, 1500, -9000);
			deepShot(ctx, sp, "18-ton618", "ton618", -26000, 1700, 14000);
			deepShot(ctx, sp, "19-binary", "izanagi", 9000, 1800, 6000);
			deepShot(ctx, sp, "20-pulsar", "kagura", 2500, 600, 1800);
			surface(ctx, sp, "30-surface-mars", "mars");
			surface(ctx, sp, "31-surface-florana", "florana");
			surface(ctx, sp, "32-surface-moon", "moon");
			surface(ctx, sp, "33-surface-sakura", "sakura");
		}
	}

	private static double now(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> (double) server.overworld().getGameTime());
	}

	/** Камера со смещением (dx, dy, dz) от тела, смотрит на него */
	private static void look(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String id, double dx, double dy, double dz) {
		Body b = SolarSystem.byId(id);
		b.pos(now(sp) + 60, P);
		double x = P[0] + dx, y = clampY(P[1] + dy), z = P[2] + dz;
		shoot(ctx, sp, name, x, y, z, P[0], P[1], P[2]);
	}

	private static void lookSun(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, double dist) {
		shoot(ctx, sp, name, dist * 0.8, SolarSystem.PLANE_Y + 300, dist * 0.6, 0, SolarSystem.PLANE_Y, 0);
	}

	private static void deepShot(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String id, double dx, double dy, double dz) {
		SolarSystem.Deep d = SolarSystem.deep(id);
		shoot(ctx, sp, name, d.x + dx, clampY(d.y + dy), d.z + dz, d.x, d.y, d.z);
	}

	private static void beltShot(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		double r = (SolarSystem.BELT_INNER + SolarSystem.BELT_OUTER) / 2;
		// внутри пояса, смотрим по касательной — настоящие астероиды-блоки рядом и обломки вдали
		shoot(ctx, sp, "12-asteroid-belt", r, SolarSystem.PLANE_Y + 6, -40, r - 60, SolarSystem.PLANE_Y - 5, 200);
	}

	private static void cometShot(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		double t = now(sp) + 60;
		Comet best = null;
		double bestR = 1e9;
		for (Comet c : SolarSystem.COMETS) {
			if (c.minor) continue;
			c.pos(t, P);
			double r = Math.sqrt(P[0] * P[0] + P[2] * P[2]);
			if (r < bestR) { bestR = r; best = c; }
		}
		best.pos(t, P);
		double r = Math.max(1, Math.sqrt(P[0] * P[0] + P[2] * P[2]));
		// сбоку от хвоста: смещение перпендикулярно направлению на Солнце
		double nx = -P[2] / r, nz = P[0] / r;
		LOG.info("[shots] comet {} at r={}", best.id, (int) bestR);
		shoot(ctx, sp, "13-comet-" + best.id, P[0] + nx * 1400, clampY(P[1] + 120), P[2] + nz * 1400, P[0] + P[0] / r * 900, P[1], P[2] + P[2] / r * 900);
	}

	private static void surface(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String id) {
		cmd(sp, String.format(Locale.ROOT, "execute in phasespace:%s run tp @p 0 120 0 30 25", id));
		cmd(sp, "time set 1000");
		ctx.waitTicks(40);
		waitRender(sp, name);
		ctx.waitTicks(40);
		ctx.takeScreenshot(name);
		LOG.info("[shots] {} done", name);
	}

	private static double clampY(double y) {
		return Math.max(-1950, Math.min(1950, y));
	}

	private static void shoot(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, double x, double y, double z, double tx, double ty, double tz) {
		double dx = tx - x, dy = ty - y, dz = tz - z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		LOG.info("[shots] {} at {} {} {}", name, (int) x, (int) y, (int) z);
		cmd(sp, String.format(Locale.ROOT, "execute in phasespace:space run tp @p %.1f %.1f %.1f %.1f %.1f", x, y, z, yaw, pitch));
		ctx.waitTicks(30);
		waitRender(sp, name);
		ctx.waitTicks(30);
		ctx.takeScreenshot(name);
		LOG.info("[shots] {} done", name);
	}

	private static void waitRender(TestSingleplayerContext sp, String what) {
		try {
			sp.getConnection().waitForChunksRender(true, 20 * 300);
		} catch (Throwable t) {
			LOG.info("[shots] chunks did not finish for {}: {}", what, t.toString());
		}
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.info("[shots] command failed: {} ({})", c, t.toString());
		}
	}
}

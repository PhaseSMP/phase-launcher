package gg.phase.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.phase.space.Viltrum;
import gg.phase.space.ViltrumPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Сила вильтрумита на клиенте: инверсионный след за летящим игроком и кольцо-хлопок,
 * когда он рвёт звуковой барьер. Кто владеет силой — пакет phasespace:viltrum.
 */
public final class ViltrumFx {
	private static final Set<Integer> IDS = new HashSet<>();
	private static final Map<Integer, Trail> TRAILS = new HashMap<>();
	private static final List<double[]> BOOMS = new ArrayList<>();   // {x, y, z, возраст}
	private static final int LIFE = 22;

	private static final class Trail {
		final ArrayDeque<double[]> pts = new ArrayDeque<>();      // {x, y, z, возраст}
		double lx = Double.NaN, ly, lz, speed;
	}

	private ViltrumFx() {}

	public static void set(ViltrumPayload p) {
		IDS.clear();
		for (String s : p.ids().split(",")) {
			if (s.isBlank()) continue;
			try { IDS.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { }
		}
		TRAILS.keySet().retainAll(IDS);
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			IDS.clear();
			TRAILS.clear();
			BOOMS.clear();
			return;
		}
		if (mc.isPaused()) return;
		for (Trail tr : TRAILS.values()) for (double[] q : tr.pts) q[3]++;
		for (Iterator<double[]> it = BOOMS.iterator(); it.hasNext(); ) if (++it.next()[3] > 18) it.remove();
		for (int id : IDS) {
			Entity e = mc.level.getEntity(id);
			Trail tr = TRAILS.computeIfAbsent(id, k -> new Trail());
			while (!tr.pts.isEmpty() && tr.pts.peekFirst()[3] > LIFE) tr.pts.pollFirst();
			if (e == null) { tr.lx = Double.NaN; continue; }
			Vec3 p = e.position();
			double x = p.x, y = p.y + e.getBbHeight() * 0.45, z = p.z;
			if (!Double.isNaN(tr.lx)) {
				double dx = x - tr.lx, dy = y - tr.ly, dz = z - tr.lz, sp = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (sp > 100) { tr.pts.clear(); sp = 0; }                                   // телепорт
				if (sp > Viltrum.BOOM_SPEED && tr.speed <= Viltrum.BOOM_SPEED) BOOMS.add(new double[] { x - dx, y - dy, z - dz, 0 });
				tr.speed = sp;
				if (sp > 0.9) tr.pts.addLast(new double[] { x, y, z, 0 });
				else if (!tr.pts.isEmpty()) tr.pts.addLast(new double[] { Double.NaN, 0, 0, 0 });   // разрыв
			}
			tr.lx = x; tr.ly = y; tr.lz = z;
		}
	}

	public static void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || IDS.isEmpty() && BOOMS.isEmpty() || !Models.ready()) return;
		if (ShaderCompat.shadowPass()) return;
		SkyFx.camera(mc);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		boolean first = mc.options.getCameraType().isFirstPerson();
		int self = mc.player == null ? -1 : mc.player.getId();
		for (Map.Entry<Integer, Trail> en : TRAILS.entrySet()) {
			double[] prev = null;
			for (double[] q : en.getValue().pts) {
				if (Double.isNaN(q[0])) { prev = null; continue; }
				if (prev != null) {
					double ax = prev[0] - cam.x, ay = prev[1] - cam.y, az = prev[2] - cam.z;
					double bx = q[0] - cam.x, by = q[1] - cam.y, bz = q[2] - cam.z;
					boolean near = ax * ax + ay * ay + az * az < 16 || bx * bx + by * by + bz * bz < 16;
					if (!(first && en.getKey() == self && near)) {
						double age = q[3] / LIFE;
						SkyFx.line(pose, out, ax, ay, az, bx, by, bz, 0.35 + age * 1.6, age < 0.4 ? "fx/ray_white_a" : "fx/ray_white_c");
						if (age < 0.5) SkyFx.line(pose, out, ax, ay, az, bx, by, bz, 0.9 + age * 2.5, "fx/ray_ion_c");
					}
				}
				prev = q;
			}
		}
		for (double[] b : BOOMS) {
			double rx = b[0] - cam.x, ry = b[1] - cam.y, rz = b[2] - cam.z;
			double a = b[3];
			SkyFx.billboard(pose, out, rx, ry, rz, 2 + a * 2.6, a < 7 ? "fx/ring_white_a" : "fx/ring_white_c", 0);
			SkyFx.billboard(pose, out, rx, ry, rz, 1 + a * 1.6, "fx/ring_ion_c", (float) (a * 20));
			if (a < 4) SkyFx.billboard(pose, out, rx, ry, rz, 7 - a, "fx/glow_white_b", 0);
		}
	}
}

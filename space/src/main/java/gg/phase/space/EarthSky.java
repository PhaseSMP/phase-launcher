package gg.phase.space;

import gg.phase.space.SolarSystem.Body;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Что видно с Земли невооружённым глазом: как в жизни, яркие планеты у эклиптики (пути Солнца).
 * Венера — вечерняя и утренняя звезда, никогда не отходит от Солнца дальше ~45°; Марс красный; Юпитер яркий;
 * Сатурн жёлтый; Меркурий только низко в сумерках. И Флорана — фиолетовая «блуждающая звезда», которой нет
 * в настоящей Солнечной системе. Солнце и Луна на небе Земли — наши, кубические (текстуры minecraft:sun и moon/*).
 */
public final class EarthSky {
	public static final List<Body> VISIBLE;

	static {
		List<Body> l = new ArrayList<>();
		for (String id : new String[] { "mercury", "venus", "mars", "jupiter", "saturn", "florana" }) {
			Body b = SolarSystem.byId(id);
			if (b != null) l.add(b);
		}
		VISIBLE = Collections.unmodifiableList(l);
	}

	private EarthSky() {}

	/** Видимая «звёздная величина» как размер точки (0..1) */
	public static double brightness(Body b) {
		return switch (b.id) {
			case "venus" -> 1.0;
			case "jupiter" -> 0.8;
			case "florana" -> 0.7;
			case "mars" -> 0.6;
			case "saturn" -> 0.55;
			default -> 0.35;
		};
	}
}

package gg.phase.space;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** config/phasespace.json — создаётся при первом запуске, все поля можно не трогать. */
public final class SpaceConfig {
	/** Космос открыт. Если закрыт (/space close) — вылет на высоте 2000 не работает, всех из космоса отправляет домой */
	public boolean spaceOpen = true;
	/** Обычные игроки могут сами пользоваться /space go и /space home (админы могут всегда) */
	public boolean playerCommands = false;
	/** Ниже этой высоты атмосфера плотная: при быстром спуске выше неё обшивка (игрок) раскаляется */
	public int atmosphereY = 700;
	/** Нагрев при входе в атмосферу обжигает (слабо; зелье огнестойкости и элитры спасают) */
	public boolean reentryDamage = true;
	/** Высота в обычном мире, выше которой игрок попадает в космос */
	public int overworldExitY = 2000;
	/** Высота в космосе, ниже которой игрок возвращается на Землю (плоскость системы — Y 0) */
	public int spaceReturnY = -2000;
	/** На сколько блоков ниже верха мира планеты начинается выход в космос */
	public int planetExitMargin = 24;
	/** Скорость полёта в космосе (ванильная 0.05) */
	public double flySpeed = 0.11;
	/** Разгон: если лететь с бегом по прямой, скорость растёт до этих значений (внутри системы / в дальнем космосе) */
	public double warpMaxSystem = 0.22;
	public double warpMaxDeep = 0.45;
	/** Солнце, звёзды, квазар и пульсар обжигают и облучают тех, кто подлетел слишком близко */
	public boolean starHeat = true;
	/** События космоса (метеорные потоки, вспышки, затмения…). Расписание общее для сервера и клиента */
	public boolean events = true;
	/** Во время метеорного потока рядом с игроками падают настоящие метеориты из блоков */
	public boolean meteorites = true;
	/** Подсказка над хотбаром: на какое тело смотришь и сколько до него */
	public boolean navigation = true;
	/** Клиент: качество (0 — минимум, 1 — обычное, 2 — максимум) */
	public int quality = 1;
	/** Клиент: рисовать звёздное небо мода и при включённых шейдерах */
	public boolean skyWithShaders = true;
	/** Клиент: блики объектива от Солнца */
	public boolean lensFlare = true;

	// ── Хаос затмения (/space eclipse) ──
	/** Центр затмения, если мод карты Phase World не стоит (с ним — кратер Фудзи) */
	public double eclipseX = 0, eclipseZ = 0;
	/** Длительность по умолчанию, секунд */
	public int eclipseSeconds = 360;
	/** При запуске поставить полдень и ясную погоду, чтобы Солнце было видно */
	public boolean eclipseSetNoon = true;
	/** Ударные волны от кратера (отбрасывают; щит в блоке гасит, присед ослабляет) и их урон */
	public boolean eclipseWaves = true;
	public boolean eclipseWaveDamage = true;
	/** Луч из кратера поднимает игроков вверх (до космоса) */
	public boolean eclipseBeam = true;
	/** Дождь осколков затмения в полной тени */
	public boolean eclipseCrystals = true;
	/** Фантомы в полной тени */
	public boolean eclipseMobs = true;
	/** Клиент: насколько сильно темнеет мир в полной фазе (0 — не темнеет, 1 — максимум) */
	public double eclipseDarkness = 1.0;

	private static SpaceConfig instance;

	public static SpaceConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	/** Перечитать config/phasespace.json (/space reload) */
	public static void reload() {
		instance = load();
	}

	/** Сохранить текущие значения (например, после /space open | close) */
	public static void save() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("phasespace.json");
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(get(), w);
		} catch (Exception e) {
			PhaseSpace.LOG.warn("Cannot write config {}: {}", file, e.toString());
		}
	}

	private static SpaceConfig load() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("phasespace.json");
		SpaceConfig cfg = null;
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				cfg = gson.fromJson(r, SpaceConfig.class);
			} catch (Exception e) {
				PhaseSpace.LOG.warn("Bad config {}: {}", file, e.toString());
			}
		}
		if (cfg == null) cfg = new SpaceConfig();
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(cfg, w);
		} catch (Exception e) {
			PhaseSpace.LOG.warn("Cannot write config {}: {}", file, e.toString());
		}
		return cfg;
	}
}

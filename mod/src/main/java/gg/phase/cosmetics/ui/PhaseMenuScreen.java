package gg.phase.cosmetics.ui;

import gg.phase.cosmetics.Config;
import gg.phase.cosmetics.CosmeticsData;
import gg.phase.cosmetics.CosmeticsLayer;
import gg.phase.cosmetics.PhaseCosmetics;
import gg.phase.cosmetics.pet.Pet;
import gg.phase.cosmetics.pet.PetManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Меню Phase в игре (клавиша Ё) — на весь экран, в стиле лаунчера.
 * Слева разделы, в центре — ваш персонаж (его можно крутить мышью и приближать колесом),
 * справа — настройки раздела. Шляпу можно сдвинуть, повернуть и изменить размер — видно сразу на персонаже.
 */
public class PhaseMenuScreen extends Screen {
	private static final int M = 8, HEAD = 30, FOOT = 18;
	private static final String[] TABS = { "Одежда", "Паки", "Питомец", "Настройки" };
	private static final String[] TAB_TITLES = { "ОДЕЖДА", "ПАКИ ЭФФЕКТОВ", "ПИТОМЕЦ", "НАСТРОЙКИ" };
	private static final String[] TAB_SUB = { "Шляпы, шарф и варежки · наведите — примерка", "Аура, след, приземление, смерть и звуки",
		"Раскраска, команды и трюки", "Только на этом компьютере" };
	private static int tab;
	private static final int[] SCROLL = new int[TABS.length];
	private static float yaw = 22F, tilt = -6F, zoom = 1F;

	private String status = "";
	private int statusColor = Style.MUTED;
	private boolean loading = true;
	private final long openedAt = System.currentTimeMillis();

	// раскладка
	private int sideX, sideW, prevX, prevW, conX, conW, top, bottom, listTop, listBottom, contentH;
	private boolean dragging;
	private final List<Text> texts = new ArrayList<>();
	private final List<Object[]> hatButtons = new ArrayList<>();
	private final List<AbstractWidget> listWidgets = new ArrayList<>();

	// настройка шляпы: изменения сохраняются на сервере через полсекунды после последнего нажатия
	private String adjHat;
	private long adjEditAt;

	private record Text(String s, int x, int y, int color, int kind, int w) {}  // kind: 0 текст, 1 заголовок раздела, 2 по центру

	/** Для автотеста: открыть раздел */
	public static void testTab(int t) { tab = t; }

	public PhaseMenuScreen() {
		super(Component.literal("Phase"));
	}

	// ───────── Раскладка ─────────
	@Override
	protected void init() {
		this.top = HEAD + M;
		this.bottom = this.height - FOOT - M;
		this.sideX = M;
		this.sideW = Mth.clamp(this.width / 7, 78, 116);
		this.conW = Mth.clamp((int) (this.width * 0.4F), 196, 300);
		this.conX = this.width - M - this.conW;
		this.prevX = this.sideX + this.sideW + M;
		this.prevW = this.conX - M - this.prevX;
		if (this.prevW < 84) {
			// узкое окно: справа берём меньше места, персонажа — сколько останется
			this.conW = Math.max(170, this.conX + this.conW - this.prevX - 84 - M);
			this.conX = this.width - M - this.conW;
			this.prevW = this.conX - M - this.prevX;
		}
		if (CosmeticsData.mine != null) this.loading = false;
		if (this.loading) {
			CosmeticsData.loadMine().whenComplete((m, err) -> this.minecraft.execute(() -> {
				this.loading = false;
				if (err != null) this.fail(err);
				this.rebuildWidgets();
			}));
		}
		this.layout();
	}

	private void layout() {
		this.texts.clear();
		this.hatButtons.clear();
		this.listWidgets.clear();
		// разделы слева
		int ty = this.top + 22;
		for (int i = 0; i < TABS.length; i++) {
			int idx = i;
			this.addRenderableWidget(new PhaseButton(this.sideX + 6, ty, this.sideW - 12, 20, TABS[i], PhaseButton.Kind.CHIP, () -> tab == idx, () -> {
				tab = idx;
				this.rebuildWidgets();
			}));
			ty += 24;
		}
		// закрыть
		this.addRenderableWidget(new PhaseButton(this.width - M - 20, 6, 20, 18, "✕", PhaseButton.Kind.ACTION, null, this::onClose));

		this.listTop = this.top + 34;
		this.listBottom = this.bottom - 6;
		int x = this.conX + 10, w = this.conW - 20;
		int y0 = this.listTop - SCROLL[tab];
		int y = y0;
		CosmeticsData.Mine m = CosmeticsData.mine;
		if (m == null && tab != 3) {
			this.text(this.loading ? "Загружаю вашу косметику…" : "Нет данных — нажмите «Перезагрузить» в настройках", x, y + 4, Style.MUTED);
			y += 20;
		} else {
			switch (tab) {
				case 0 -> y = this.hats(m, x, y, w);
				case 1 -> y = this.packs(m, x, y, w);
				case 2 -> y = this.pet(m, x, y, w);
				default -> y = this.settings(x, y, w);
			}
		}
		this.contentH = y - y0;
		int max = Math.max(0, this.contentH - (this.listBottom - this.listTop));
		if (SCROLL[tab] > max) {
			SCROLL[tab] = max;
			this.clearWidgets();
			this.layout();
			return;
		}
		// за краем списка кнопки не показываем (и не нажимаем)
		for (AbstractWidget wd : this.listWidgets) wd.visible = wd.getY() >= this.listTop - 1 && wd.getY() + wd.getHeight() <= this.listBottom + 1;
	}

	private int section(String s, int x, int y) {
		this.texts.add(new Text(s, x, y, Style.MUTED, 1, 0));
		return y + 13;
	}

	private void text(String s, int x, int y, int color) {
		this.texts.add(new Text(s, x, y, color, 0, 0));
	}

	private PhaseButton add(PhaseButton b) {
		this.addRenderableWidget(b);
		this.listWidgets.add(b);
		return b;
	}

	// ── Шляпы ──
	private int hats(CosmeticsData.Mine m, int x, int y, int w) {
		int gap = 4, half = (w - gap) / 2, rh = 20;
		List<CosmeticsData.Item> hats = m.items().stream().filter(i -> i.slot().equals("hat")).toList();
		y = this.section("МОИ ШЛЯПЫ", x, y);
		int col = 0;
		for (CosmeticsData.Item it : hats) {
			PhaseButton b = this.add(new PhaseButton(x + col * (half + gap), y, half, rh, it.name(), PhaseButton.Kind.CHIP,
				() -> CosmeticsData.mine != null && Objects.equals(CosmeticsData.mine.hat(), it.id()), () -> this.equip("hat", it.id())));
			this.hatButtons.add(new Object[] { b, it.id() });
			if (++col == 2) { col = 0; y += rh + gap; }
		}
		PhaseButton none = this.add(new PhaseButton(x + col * (half + gap), y, col == 0 ? w : half, rh, "Без шляпы", PhaseButton.Kind.CHIP,
			() -> CosmeticsData.mine != null && CosmeticsData.mine.hat() == null, () -> this.equip("hat", null)));
		this.hatButtons.add(new Object[] { none, "" });
		y += rh + 10;
		if (hats.isEmpty()) {
			this.text("Шляпы покупаются в лаунчере → «Донаты»", x, y, Style.MUTED);
			y += 14;
		}
		// шарф и варежки
		List<CosmeticsData.Item> wear = m.items().stream().filter(i -> i.slot().equals("neck") || i.slot().equals("hands")).toList();
		y = this.section("ШАРФ И ВАРЕЖКИ", x, y);
		if (wear.isEmpty()) {
			this.text("Шарф и варежки — в лаунчере → «Донаты»", x, y, Style.MUTED);
			y += 18;
		} else {
			int wc = 0;
			for (CosmeticsData.Item it : wear) {
				boolean neck = it.slot().equals("neck");
				java.util.function.BooleanSupplier on = () -> CosmeticsData.mine != null && Objects.equals(neck ? CosmeticsData.mine.neck() : CosmeticsData.mine.hands(), it.id());
				this.add(new PhaseButton(x + wc * (half + gap), y, wear.size() == 1 ? w : half, rh, it.name(), PhaseButton.Kind.TOGGLE, on,
					() -> this.equip(it.slot(), on.getAsBoolean() ? null : it.id())));
				if (++wc == 2) { wc = 0; y += rh + gap; }
			}
			if (wc != 0) y += rh + gap;
			y += 6;
		}
		if (m.hat() == null) return y;

		// настройка надетой шляпы
		String hat = m.hat();
		String hatName = hats.stream().filter(i -> i.id().equals(hat)).map(CosmeticsData.Item::name).findFirst().orElse(hat);
		y = this.section("НАСТРОЙКА · " + hatName.toUpperCase(), x, y);
		this.text("Видно всем игрокам. Сохраняется само.", x, y, Style.MUTED_D);
		y += 13;
		boolean moved = CosmeticsData.MY_ADJ.containsKey(hat);
		PhaseButton reset = this.add(new PhaseButton(x, y, w, 18, moved ? "Вернуть шляпу на место" : "Шляпа на своём месте", PhaseButton.Kind.ACTION, null, () -> this.resetAdj(hat)));
		reset.active = moved;
		y += 24;
		Object[][] rows = {
			{ "Влево / вправо", 0, 0.5F, "px" }, { "Вниз / вверх", 1, 0.5F, "px" }, { "Назад / вперёд", 2, 0.5F, "px" },
			{ "Наклон", 3, 5F, "°" }, { "Поворот", 4, 5F, "°" }, { "Крен", 5, 5F, "°" }, { "Размер", 6, 0.05F, "%" } };
		int bw = 20, valW = 46;
		int labelW = w - 2 * bw - valW - 8;
		for (Object[] r : rows) {
			int i = (int) r[1];
			float step = (float) r[2];
			String unit = (String) r[3];
			this.text((String) r[0], x, y + 6, Style.TEXT_D);
			int bx = x + labelW + 4;
			this.add(new PhaseButton(bx, y, bw, 18, "−", PhaseButton.Kind.ACTION, null, () -> this.nudge(hat, i, -step)));
			float v = this.adjOf(hat)[i];
			String vs = unit.equals("%") ? Math.round(v * 100F) + "%" : (v > 0 ? "+" : "") + (v == (int) v ? Integer.toString((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v)) + unit;
			float def = i == 6 ? 1F : 0F;
			// значение — тоже кнопка: клик возвращает эту строку к нулю
			PhaseButton val = this.add(new PhaseButton(bx + bw + 2, y, valW - 4, 18, vs, PhaseButton.Kind.CHIP, () -> false, () -> this.nudge(hat, i, def - this.adjOf(hat)[i])));
			val.active = v != def;
			this.add(new PhaseButton(bx + bw + valW, y, bw, 18, "+", PhaseButton.Kind.ACTION, null, () -> this.nudge(hat, i, step)));
			y += 21;
		}
		this.text("Клик по значению — сбросить только его", x, y + 2, Style.MUTED_D);
		return y + 16;
	}

	private void resetAdj(String hat) {
		CosmeticsData.MY_ADJ.remove(hat);
		this.adjHat = hat;
		this.adjEditAt = 0; // сохранить сразу
		this.rebuildWidgets();
	}

	private float[] adjOf(String hat) {
		float[] v = CosmeticsData.MY_ADJ.get(hat);
		return v == null ? CosmeticsData.NO_ADJ : v;
	}

	private void nudge(String hat, int i, float d) {
		float[] v = this.adjOf(hat).clone();
		float[][] lim = { { -6, 6 }, { -6, 6 }, { -6, 6 }, { -45, 45 }, { -45, 45 }, { -45, 45 }, { 0.7F, 1.3F } };
		v[i] = Mth.clamp(Math.round((v[i] + d) * 100F) / 100F, lim[i][0], lim[i][1]);
		if (java.util.Arrays.equals(v, CosmeticsData.NO_ADJ)) CosmeticsData.MY_ADJ.remove(hat);
		else CosmeticsData.MY_ADJ.put(hat, v);
		this.adjHat = hat;
		this.adjEditAt = System.currentTimeMillis();
		this.rebuildWidgets();
	}

	private void flushAdj() {
		if (this.adjHat == null) return;
		String hat = this.adjHat;
		this.adjHat = null;
		float[] v = this.adjOf(hat);
		this.status = "Сохраняю шляпу…";
		this.statusColor = Style.MUTED;
		CosmeticsData.saveAdj(hat, v).whenComplete((r, err) -> this.minecraft.execute(() -> {
			if (err != null) this.fail(err);
			else { this.status = "Шляпа настроена — другие увидят через 15 секунд"; this.statusColor = Style.GOLD; }
		}));
	}

	@Override
	public void tick() {
		if (this.adjHat != null && System.currentTimeMillis() - this.adjEditAt > 600) this.flushAdj();
	}

	// ── Паки ──
	private int packs(CosmeticsData.Mine m, int x, int y, int w) {
		int gap = 4, half = (w - gap) / 2, rh = 20;
		List<CosmeticsData.Item> auraItems = m.items().stream().filter(i -> i.slot().equals("aura")).toList();
		y = this.section("МОИ ПАКИ", x, y);
		int col = 0;
		for (CosmeticsData.Item it : auraItems) {
			String label = it.name().replace("Аура ", "").replace("Пак ", "").replace("«", "").replace("»", "");
			this.add(new PhaseButton(x + col * (half + gap), y, half, rh, label, PhaseButton.Kind.CHIP,
				() -> CosmeticsData.mine != null && Objects.equals(CosmeticsData.mine.aura(), it.id()), () -> this.equip("aura", it.id())));
			if (++col == 2) { col = 0; y += rh + gap; }
		}
		this.add(new PhaseButton(x + col * (half + gap), y, col == 0 ? w : half, rh, "Без пака", PhaseButton.Kind.CHIP,
			() -> CosmeticsData.mine != null && CosmeticsData.mine.aura() == null, () -> this.equip("aura", null)));
		y += rh + 10;
		if (auraItems.isEmpty()) {
			this.text("Паки покупаются в лаунчере → «Донаты»", x, y, Style.MUTED);
			y += 14;
		}
		// части пака: каждую можно выключить
		y = this.section("ЧАСТИ ПАКА", x, y);
		char[] parts = { 'a', 't', 'b', 'd', 'k', 's' };
		String[] names = { "Аура", "След", "Приземление", "Смерть", "Убийство", "Звуки" };
		for (int i = 0; i < parts.length; i++) {
			char part = parts[i];
			PhaseButton b = this.add(new PhaseButton(x + (i % 2) * (half + gap), y + (i / 2) * (rh + gap), half, rh, names[i], PhaseButton.Kind.TOGGLE,
				() -> CosmeticsData.mine != null && CosmeticsData.fxOn(CosmeticsData.mine.fx(), part), () -> this.toggleFx(part)));
			b.active = m.aura() != null || part == 's';
		}
		y += 3 * (rh + gap) + 6;
		// громкость звуков паков (общая с лаунчером)
		y = this.section("ГРОМКОСТЬ ЗВУКОВ ПАКОВ", x, y);
		int vol = m.vol();
		boolean snd = CosmeticsData.fxOn(m.fx(), 's') && Config.get().packSounds;
		PhaseButton minus = this.add(new PhaseButton(x, y, 26, rh, "−", PhaseButton.Kind.ACTION, null, () -> this.volume(vol - 10)));
		PhaseButton plus = this.add(new PhaseButton(x + w - 26, y, 26, rh, "+", PhaseButton.Kind.ACTION, null, () -> this.volume(vol + 10)));
		minus.active = snd && vol > 0;
		plus.active = snd && vol < 100;
		// полоска громкости
		this.texts.add(new Text("", x + 32, y + 7, snd ? Style.RED : Style.MUTED_D, 3, (w - 64) * vol / 100));
		this.texts.add(new Text(snd ? vol + "%" : "выкл", x + w / 2, y + 6, Style.TEXT, 2, 0));
		y += rh + 8;
		if (!Config.get().packSounds) {
			this.text("Звуки паков выключены в «Настройках»", x, y, Style.MUTED_D);
			y += 12;
		}
		return y;
	}

	// ── Питомец ──
	private static final String[][] DRAGON_COLORS = { { "default", "Эндер" }, { "crimson", "Пламя" }, { "frost", "Иней" }, { "gold", "Злато" }, { "void", "Бездна" }, { "prism", "Призма" } };
	private static final String[][] SLIME_COLORS = { { "default", "Лайм" }, { "sakura", "Сакура" }, { "frost", "Иней" }, { "lava", "Магма" }, { "honey", "Мёд" }, { "void", "Бездна" }, { "prism", "Призма" } };

	private int pet(CosmeticsData.Mine m, int x, int y, int w) {
		int gap = 4, rh = 20, third = (w - gap * 2) / 3, half = (w - gap) / 2;
		List<CosmeticsData.Item> pets = m.items().stream().filter(i -> i.slot().equals("pet")).toList();
		if (pets.isEmpty()) {
			this.texts.add(new Text("", x, y, Style.GOLD, 4, w));
			this.text("Эндер-дракончик и слизень", x + 8, y + 6, Style.TEXT);
			this.text("Купить: лаунчер → «Донаты»", x + 8, y + 18, Style.MUTED);
			return y + 36;
		}
		// кто рядом: один из своих питомцев или никого
		y = this.section("КТО РЯДОМ", x, y);
		int col = 0;
		for (CosmeticsData.Item it : pets) {
			this.add(new PhaseButton(x + col * (half + gap), y, half, rh, it.name(), PhaseButton.Kind.CHIP,
				() -> CosmeticsData.mine != null && Objects.equals(CosmeticsData.mine.pet(), it.id()), () -> this.equip("pet", it.id())));
			if (++col == 2) { col = 0; y += rh + gap; }
		}
		this.add(new PhaseButton(x + col * (half + gap), y, col == 0 ? w : half, rh, "Без питомца", PhaseButton.Kind.CHIP,
			() -> CosmeticsData.mine != null && CosmeticsData.mine.pet() == null, () -> this.equip("pet", null)));
		y += rh + 10;
		boolean shown = m.pet() != null;
		boolean slime = "slime".equals(m.pet() != null ? m.pet() : pets.get(0).id());
		String petWord = slime ? "СЛИЗНЯ" : "ДРАКОНЧИКА";
		y = this.section("РАСКРАСКА " + petWord, x, y);
		String[][] colors = slime ? SLIME_COLORS : DRAGON_COLORS;
		java.util.Set<String> valid = new java.util.HashSet<>();
		for (String[] c : colors) valid.add(c[0]);
		for (int i = 0; i < colors.length; i++) {
			String id = colors[i][0];
			PhaseButton b = this.add(new PhaseButton(x + (i % 3) * (third + gap), y + (i / 3) * (rh + gap), third, rh, colors[i][1], PhaseButton.Kind.CHIP,
				() -> {
					String cur = CosmeticsData.mine == null || CosmeticsData.mine.petColor() == null ? "default" : CosmeticsData.mine.petColor();
					return id.equals(valid.contains(cur) ? cur : "default");
				},
				() -> this.petColor(id)));
			b.active = shown;
		}
		y += ((colors.length + 2) / 3) * (rh + gap) + 6;
		y = this.section("КОМАНДА", x, y);
		String[][] modes = { { "follow", "Рядом" }, { "shoulder", "На плечо" }, { "stay", "Ждать тут" } };
		for (int i = 0; i < 3; i++) {
			String id = modes[i][0];
			PhaseButton b = this.add(new PhaseButton(x + i * (third + gap), y, third, rh, modes[i][1], PhaseButton.Kind.CHIP,
				() -> Config.get().mode.equals(id), () -> CosmeticsData.sendMode(id)));
			b.active = shown;
		}
		y += rh + 10;
		// громкость питомца — только на этом компьютере
		y = this.section("ГРОМКОСТЬ " + petWord, x, y);
		Config cfg = Config.get();
		int pv = cfg.sounds ? cfg.petVolume : 0;
		PhaseButton pMinus = this.add(new PhaseButton(x, y, 26, rh, "−", PhaseButton.Kind.ACTION, null, () -> { cfg.petVolume = Math.max(0, cfg.petVolume - 10); cfg.sounds = true; cfg.save(); this.rebuildWidgets(); }));
		PhaseButton pPlus = this.add(new PhaseButton(x + w - 26, y, 26, rh, "+", PhaseButton.Kind.ACTION, null, () -> { cfg.petVolume = Math.min(100, cfg.petVolume + 10); cfg.sounds = true; cfg.save(); this.rebuildWidgets(); }));
		pMinus.active = pv > 0;
		pPlus.active = pv < 100;
		this.texts.add(new Text("", x + 32, y + 7, pv > 0 ? Style.RED : Style.MUTED_D, 3, (w - 64) * pv / 100));
		this.texts.add(new Text(pv > 0 ? pv + "%" : "выкл", x + w / 2, y + 6, Style.TEXT, 2, 0));
		y += rh + 10;
		y = this.section("ТРЮКИ", x, y);
		Object[][] tricks = slime
			? new Object[][] { { "Погладить", Pet.Act.PETTED }, { "Сальто", Pet.Act.LOOP }, { "Удивить", Pet.Act.ROAR },
				{ "Танец", Pet.Act.SPIN }, { "Кувырок", Pet.Act.ROLL }, { "Ко мне", Pet.Act.VISIT }, { "Надуться", Pet.Act.SULK } }
			: new Object[][] { { "Погладить", Pet.Act.PETTED }, { "Сальто", Pet.Act.LOOP }, { "Рык", Pet.Act.ROAR },
				{ "Кружить", Pet.Act.SPIN }, { "Бочка", Pet.Act.ROLL }, { "Ко мне", Pet.Act.VISIT } };
		for (int i = 0; i < tricks.length; i++) {
			Pet.Act act = (Pet.Act) tricks[i][1];
			PhaseButton b = this.add(new PhaseButton(x + (i % 3) * (third + gap), y + (i / 3) * (rh + gap), third, rh, (String) tricks[i][0], PhaseButton.Kind.ACTION,
				null, () -> {
					if (!Config.get().mode.equals("follow")) CosmeticsData.sendMode("follow");
					PetManager.INSTANCE.command(act);
					this.onClose(); // закрываем меню, чтобы увидеть трюк
				}));
			b.active = shown;
		}
		y += ((tricks.length + 2) / 3) * (rh + gap) + 4;
		this.text("Трюк закрывает меню, чтобы его было видно", x, y, Style.MUTED_D);
		return y + 14;
	}

	// ── Настройки ──
	private int settings(int x, int y, int w) {
		Config c = Config.get();
		int rh = 20, gap = 4;
		Object[][] rows = {
			{ "Звуки питомца", (Runnable) () -> c.sounds = !c.sounds, (java.util.function.BooleanSupplier) () -> c.sounds },
			{ "Питомцы и паки других игроков", (Runnable) () -> c.others = !c.others, (java.util.function.BooleanSupplier) () -> c.others },
			{ "Шляпа поверх шлема", (Runnable) () -> c.hideHelmet = !c.hideHelmet, (java.util.function.BooleanSupplier) () -> c.hideHelmet },
			{ "Своя аура у меня на экране", (Runnable) () -> c.ownAura = !c.ownAura, (java.util.function.BooleanSupplier) () -> c.ownAura },
			{ "Звуки паков (свои и чужие)", (Runnable) () -> c.packSounds = !c.packSounds, (java.util.function.BooleanSupplier) () -> c.packSounds },
			{ "Клан и значки над никами", (Runnable) () -> c.nametags = !c.nametags, (java.util.function.BooleanSupplier) () -> c.nametags } };
		y = this.section("ИГРА", x, y);
		for (Object[] r : rows) {
			Runnable flip = (Runnable) r[1];
			this.add(new PhaseButton(x, y, w, rh, (String) r[0], PhaseButton.Kind.TOGGLE, (java.util.function.BooleanSupplier) r[2], () -> { flip.run(); c.save(); }));
			y += rh + gap;
		}
		y += 6;
		y = this.section("ДАННЫЕ", x, y);
		this.add(new PhaseButton(x, y, w, rh, "Перезагрузить косметику и скины", PhaseButton.Kind.ACTION, null, this::reload));
		y += rh + gap;
		PhaseButton all = this.add(new PhaseButton(x, y, w, rh, "Вернуть все шляпы на место", PhaseButton.Kind.ACTION, null, () -> {
			for (String h : new ArrayList<>(CosmeticsData.MY_ADJ.keySet())) CosmeticsData.saveAdj(h, CosmeticsData.NO_ADJ);
			this.status = "Расположение всех шляп сброшено";
			this.statusColor = Style.GOLD;
			this.rebuildWidgets();
		}));
		all.active = !CosmeticsData.MY_ADJ.isEmpty();
		y += rh + 6;
		this.text("Изменили что-то в лаунчере — в игре обновится само", x, y, Style.MUTED_D);
		return y + 14;
	}

	// ───────── Действия ─────────
	private void fail(Throwable err) {
		Throwable t = err.getCause() != null ? err.getCause() : err;
		this.status = t.getMessage() == null ? "Нет связи с сервером Phase" : t.getMessage();
		this.statusColor = Style.RED_L;
	}

	private void saving() {
		this.status = "Сохраняю…";
		this.statusColor = Style.MUTED;
	}

	private void done(Throwable err, String ok) {
		this.minecraft.execute(() -> {
			if (err != null) this.fail(err);
			else { this.status = ok; this.statusColor = Style.GOLD; }
			this.rebuildWidgets();
		});
	}

	private void reload() {
		this.status = "Перезагружаю…";
		this.statusColor = Style.MUTED;
		CosmeticsData.reload().whenComplete((m, err) -> this.done(err, "Косметика и скины обновлены"));
	}

	private void toggleFx(char part) {
		this.saving();
		CosmeticsData.toggleFx(part).whenComplete((m, err) -> this.done(err, "Сохранено"));
		this.rebuildWidgets();
	}

	private void volume(int v) {
		this.saving();
		CosmeticsData.setVol(v).whenComplete((m, err) -> this.done(err, "Громкость сохранена"));
		this.rebuildWidgets();
	}

	private void petColor(String id) {
		this.saving();
		CosmeticsData.setPetColor(id).whenComplete((m, err) -> this.done(err, "Раскраска сохранена"));
		this.rebuildWidgets();
	}

	private void equip(String slot, String item) {
		this.saving();
		CosmeticsData.equip(slot, item).whenComplete((m, err) -> this.done(err, "Сохранено — другие увидят через 15 секунд"));
		this.rebuildWidgets();
	}

	// ───────── Мышь и клавиши ─────────
	private boolean inPreview(double x, double y) {
		return this.prevW > 0 && x >= this.prevX && x < this.prevX + this.prevW && y >= this.top && y < this.bottom;
	}

	private boolean inList(double x, double y) {
		return x >= this.conX && x < this.conX + this.conW && y >= this.top && y < this.bottom;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.inPreview(event.x(), event.y())) {
			if (doubleClick) { yaw = 22F; tilt = -6F; zoom = 1F; }
			this.dragging = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.dragging) {
			yaw = Mth.wrapDegrees(yaw - (float) dx * 1.4F);
			tilt = Mth.clamp(tilt - (float) dy * 0.6F, -25F, 25F);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (this.inPreview(x, y)) {
			zoom = Mth.clamp(zoom + (float) sy * 0.1F, 0.7F, 1.8F);
			return true;
		}
		if (this.inList(x, y) && this.contentH > this.listBottom - this.listTop) {
			int max = this.contentH - (this.listBottom - this.listTop);
			SCROLL[tab] = Mth.clamp(SCROLL[tab] - (int) (sy * 18), 0, max);
			this.rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(x, y, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Ё закрывает меню так же, как открывает (повтор зажатой клавиши сразу после открытия не считаем)
		if (PhaseCosmetics.MENU != null && PhaseCosmetics.MENU.matches(event) && System.currentTimeMillis() - this.openedAt > 250) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.flushAdj();
		CosmeticsLayer.PREVIEW_STATE = null;
		super.onClose();
	}

	@Override
	public void removed() {
		this.flushAdj();
		CosmeticsLayer.PREVIEW_STATE = null;
	}

	// ───────── Отрисовка ─────────
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// затемняем мир целиком, сверху — шапка с алой линией
		g.fill(0, 0, this.width, this.height, 0xC8070509);
		g.fill(0, 0, this.width, HEAD, 0xF00D0A11);
		g.fill(0, HEAD - 1, this.width, HEAD, Style.LINE);
		g.fill(0, 0, this.width, 2, Style.RED);
		g.fill(0, this.height - FOOT, this.width, this.height, 0xF00D0A11);
		g.fill(0, this.height - FOOT, this.width, this.height - FOOT + 1, Style.LINE);
		// панели: разделы, персонаж, настройки
		this.panel(g, this.sideX, this.top, this.sideW, this.bottom - this.top, false);
		if (this.prevW > 0) {
			this.panel(g, this.prevX, this.top, this.prevW, this.bottom - this.top, false);
			g.fillGradient(this.prevX + 1, this.top + 1, this.prevX + this.prevW - 1, this.bottom - 1, 0x40461019, 0x10000000);
		}
		this.panel(g, this.conX, this.top, this.conW, this.bottom - this.top, true);
	}

	private void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean accent) {
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x60000000);
		g.fill(x, y, x + w, y + h, Style.INK);
		g.outline(x, y, w, h, Style.LINE);
		if (accent) g.fill(x, y, x + w, y + 2, Style.RED);
		else {
			// уголки в стиле Phase
			g.fill(x, y, x + 8, y + 1, Style.RED);
			g.fill(x, y, x + 1, y + 8, Style.RED);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// шапка
		g.pose().pushMatrix();
		g.pose().translate(M + 2, 9);
		g.pose().scale(1.5F, 1.5F);
		g.text(this.font, Component.literal("PHASE").withStyle(ChatFormatting.BOLD), 0, 0, Style.RED, false);
		g.pose().popMatrix();
		g.text(this.font, "相", M + 56, 12, Style.GOLD, false);
		String who = this.minecraft.getUser().getName();
		g.text(this.font, PhaseButton.fit(this.font, "Косметика · " + who, this.width - M - 30 - (M + 72)), M + 72, 12, Style.MUTED, false);

		// разделы
		g.text(this.font, "РАЗДЕЛЫ", this.sideX + 8, this.top + 8, Style.MUTED_D, false);

		// персонаж
		if (this.prevW > 0) this.preview(g, mouseX, mouseY);

		// заголовок раздела
		int x = this.conX + 10, w = this.conW - 20;
		g.text(this.font, TAB_TITLES[tab], x, this.top + 8, Style.TEXT, false);
		g.text(this.font, PhaseButton.fit(this.font, TAB_SUB[tab], w), x, this.top + 19, Style.MUTED_D, false);

		// подписи и значения внутри списка (обрезаются по краю списка)
		g.enableScissor(this.conX + 1, this.listTop - 1, this.conX + this.conW - 1, this.listBottom + 1);
		for (Text t : this.texts) {
			switch (t.kind) {
				case 1 -> {
					g.fill(t.x, t.y + 2, t.x + 3, t.y + 7, Style.RED);
					g.text(this.font, PhaseButton.fit(this.font, t.s, w - 8), t.x + 7, t.y, t.color, false);
				}
				case 2 -> g.centeredText(this.font, t.s, t.x, t.y, t.color);
				case 3 -> {
					int bw = this.conW - 20 - 64;
					g.fill(t.x, t.y, t.x + bw, t.y + 6, 0xFF100D14);
					g.outline(t.x, t.y, bw, 6, Style.LINE);
					g.fill(t.x + 1, t.y + 1, t.x + 1 + Math.max(0, t.w - 2), t.y + 5, t.color);
				}
				case 4 -> {
					g.fill(t.x, t.y, t.x + t.w, t.y + 30, Style.CARD);
					g.outline(t.x, t.y, t.w, 30, Style.LINE);
					g.fill(t.x, t.y, t.x + 2, t.y + 30, t.color);
				}
				default -> g.text(this.font, PhaseButton.fit(this.font, t.s, this.conX + this.conW - 10 - t.x), t.x, t.y, t.color, false);
			}
		}
		g.disableScissor();

		super.extractRenderState(g, mouseX, mouseY, a);

		// полоса прокрутки
		int view = this.listBottom - this.listTop;
		if (this.contentH > view) {
			int sx = this.conX + this.conW - 4;
			int bar = Math.max(16, view * view / this.contentH);
			int by = this.listTop + (view - bar) * SCROLL[tab] / Math.max(1, this.contentH - view);
			g.fill(sx, this.listTop, sx + 2, this.listBottom, 0xFF1A1620);
			g.fill(sx, by, sx + 2, by + bar, Style.RED);
		}

		// подвал: статус слева (всегда в одну строку), подсказка справа
		String hint = PhaseCosmetics.MENU.getTranslatedKeyMessage().getString() + " / Esc — закрыть";
		int hw = this.font.width(hint);
		int fy = this.height - FOOT + 5;
		g.text(this.font, hint, this.width - M - hw, fy, Style.MUTED_D, false);
		if (!this.status.isEmpty()) g.text(this.font, PhaseButton.fit(this.font, this.status, this.width - 3 * M - hw), M, fy, this.statusColor, false);
	}

	/** Свой персонаж в центре: крутится мышью, колесо — ближе/дальше, двойной клик — как было */
	private void preview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		LocalPlayer pl = this.minecraft.player;
		int x0 = this.prevX + 1, y0 = this.top + 1, x1 = this.prevX + this.prevW - 1, y1 = this.bottom - 1;
		int cx = (x0 + x1) / 2;
		// «пол» под персонажем
		int floorY = y1 - 22;
		g.fill(cx - this.prevW / 3, floorY, cx + this.prevW / 3, floorY + 1, 0x80D21F2A);
		g.fill(cx - this.prevW / 5, floorY + 1, cx + this.prevW / 5, floorY + 3, 0x40D21F2A);
		g.centeredText(this.font, PhaseButton.fit(this.font, "Тяните — повернуть · колесо — ближе", this.prevW - 8), cx, y1 - 13, Style.MUTED_D);
		if (pl == null) return;

		// шляпа под курсором — примерка
		String hat = CosmeticsData.mine == null ? null : CosmeticsData.mine.hat();
		if (tab == 0) {
			for (Object[] hb : this.hatButtons) {
				PhaseButton b = (PhaseButton) hb[0];
				if (b.visible && b.isHovered()) { String id = (String) hb[1]; hat = id.isEmpty() ? null : id; }
			}
		}
		EntityRenderer<? super LocalPlayer, ?> renderer = this.minecraft.getEntityRenderDispatcher().getRenderer(pl);
		EntityRenderState st = renderer.createRenderState(pl, 1.0F);
		st.shadowPieces.clear();
		st.outlineColor = 0;
		if (st instanceof LivingEntityRenderState ls) {
			ls.bodyRot = 180F + yaw;
			ls.yRot = 0F;
			ls.xRot = ls.pose == Pose.FALL_FLYING ? 0F : tilt * 0.5F;
			ls.boundingBoxWidth = ls.boundingBoxWidth / ls.scale;
			ls.boundingBoxHeight = ls.boundingBoxHeight / ls.scale;
			ls.scale = 1.0F;
		}
		CosmeticsLayer.PREVIEW_HAT = hat;
		CosmeticsLayer.PREVIEW_STATE = st;
		int areaH = floorY - y0 - 6;
		int size = (int) (Math.min(areaH / 2.3F, this.prevW / 1.3F) * zoom);
		Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf xr = new Quaternionf().rotateX(tilt * Mth.DEG_TO_RAD);
		rot.mul(xr);
		// персонаж стоит на «полу»; если при приближении голова не влезает — опускаем его, голова важнее ног
		float feet = floorY;
		float head = feet - size * st.boundingBoxHeight;
		if (head < y0 + 14) feet += y0 + 14 - head;
		float boxCy = (y0 + y1) / 2F;
		Vector3f tr = new Vector3f(0F, (feet - boxCy) / Math.max(1, size), 0F);
		g.entity(st, size, tr, rot, xr, x0, y0, x1, y1);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

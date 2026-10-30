package gg.phase.cosmetics.ui;

import gg.phase.cosmetics.Config;
import gg.phase.cosmetics.CosmeticsData;
import gg.phase.cosmetics.pet.Pet;
import gg.phase.cosmetics.pet.PetManager;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Меню Phase в игре (клавиша J): шляпа, питомец, его команды и настройки.
 * Панель справа, мир слева не размыт и не на паузе — видно, как питомец выполняет команду.
 */
public class PhaseMenuScreen extends Screen {
	private static final int PANEL_W = 232;
	private String status = "";
	private int statusColor = Style.MUTED;
	private boolean loading = true;
	private int px, top, bottom;
	private int hatLabelY = -1, petLabelY = -1, setLabelY = -1, shopY = -1;

	public PhaseMenuScreen() {
		super(Component.literal("Phase"));
	}

	@Override
	protected void init() {
		this.px = this.width - PANEL_W - 10;
		this.top = 10;
		this.bottom = this.height - 10;
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

	private void fail(Throwable err) {
		Throwable t = err.getCause() != null ? err.getCause() : err;
		this.status = t.getMessage() == null ? "Нет связи с сервером Phase" : t.getMessage();
		this.statusColor = Style.RED_L;
	}

	private void layout() {
		CosmeticsData.Mine m = CosmeticsData.mine;
		int x = this.px + 12, w = PANEL_W - 24;
		int avail = this.bottom - this.top;
		int rh = avail < 300 ? 14 : avail < 350 ? 16 : 18, gap = avail < 300 ? 2 : 3, sec = avail < 300 ? 6 : 10;
		int y = this.top + 40;
		this.hatLabelY = this.petLabelY = this.setLabelY = this.shopY = -1;

		if (m != null) {
			// ── Шляпа ──
			this.hatLabelY = y;
			y += 11;
			List<CosmeticsData.Item> hats = m.items().stream().filter(i -> i.slot().equals("hat")).toList();
			int col = 0, half = (w - gap) / 2;
			for (CosmeticsData.Item it : hats) {
				this.addRenderableWidget(new PhaseButton(x + col * (half + gap), y, half, rh, it.name(), PhaseButton.Kind.CHIP,
					() -> Objects.equals(CosmeticsData.mine.hat(), it.id()), () -> this.equip("hat", it.id())));
				if (++col == 2) { col = 0; y += rh + gap; }
			}
			this.addRenderableWidget(new PhaseButton(x + col * (half + gap), y, hats.isEmpty() ? w : half, rh, "Без шляпы", PhaseButton.Kind.CHIP,
				() -> CosmeticsData.mine.hat() == null, () -> this.equip("hat", null)));
			y += rh + sec;

			// ── Питомец ──
			this.petLabelY = y;
			y += 11;
			boolean hasPet = m.items().stream().anyMatch(i -> i.id().equals("ender_dragon"));
			if (hasPet) {
				this.addRenderableWidget(new PhaseButton(x, y, w, rh, "Эндер-дракончик рядом", PhaseButton.Kind.TOGGLE,
					() -> CosmeticsData.mine.pet() != null, () -> this.equip("pet", CosmeticsData.mine.pet() == null ? "ender_dragon" : null)));
				y += rh + gap;
				boolean shown = m.pet() != null;
				int third = (w - gap * 2) / 3;
				String[][] modes = { { "follow", "Рядом" }, { "shoulder", "На плечо" }, { "stay", "Ждать тут" } };
				for (int i = 0; i < 3; i++) {
					String id = modes[i][0];
					PhaseButton b = new PhaseButton(x + i * (third + gap), y, third, rh, modes[i][1], PhaseButton.Kind.CHIP,
						() -> Config.get().mode.equals(id), () -> CosmeticsData.sendMode(id));
					b.active = shown;
					this.addRenderableWidget(b);
				}
				y += rh + gap;
				Object[][] tricks = { { "Погладить", Pet.Act.PETTED }, { "Сальто", Pet.Act.LOOP }, { "Рык", Pet.Act.ROAR },
					{ "Кружить", Pet.Act.SPIN }, { "Бочка", Pet.Act.ROLL }, { "Ко мне", Pet.Act.VISIT } };
				for (int i = 0; i < tricks.length; i++) {
					Pet.Act act = (Pet.Act) tricks[i][1];
					PhaseButton b = new PhaseButton(x + (i % 3) * (third + gap), y + (i / 3) * (rh + gap), third, rh, (String) tricks[i][0], PhaseButton.Kind.ACTION,
						null, () -> {
							if (!Config.get().mode.equals("follow")) CosmeticsData.sendMode("follow");
							PetManager.INSTANCE.command(act);
						});
					b.active = shown;
					this.addRenderableWidget(b);
				}
				y += 2 * (rh + gap) + sec - gap;
			} else {
				this.shopY = y;
				y += 30 + sec;
			}
		} else {
			y += 24;
		}

		// ── Настройки ──
		this.setLabelY = y;
		y += 11;
		Config c = Config.get();
		this.addRenderableWidget(new PhaseButton(x, y, w, rh, "Звуки питомца", PhaseButton.Kind.TOGGLE, () -> c.sounds, () -> { c.sounds = !c.sounds; c.save(); }));
		y += rh + gap;
		this.addRenderableWidget(new PhaseButton(x, y, w, rh, "Питомцы других игроков", PhaseButton.Kind.TOGGLE, () -> c.others, () -> { c.others = !c.others; c.save(); }));
		y += rh + gap;
		this.addRenderableWidget(new PhaseButton(x, y, w, rh, "Шляпа поверх шлема", PhaseButton.Kind.TOGGLE, () -> c.hideHelmet, () -> { c.hideHelmet = !c.hideHelmet; c.save(); }));
	}

	private void equip(String slot, String item) {
		this.status = "Сохраняю…";
		this.statusColor = Style.MUTED;
		CosmeticsData.equip(slot, item).whenComplete((m, err) -> this.minecraft.execute(() -> {
			if (err != null) this.fail(err);
			else { this.status = "Сохранено — другие увидят в течение минуты"; this.statusColor = Style.GOLD; }
			this.rebuildWidgets();
		}));
		this.rebuildWidgets();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// мир не размываем: слева видно питомца. Лёгкая тень к панели
		g.fillGradient(this.px - 40, 0, this.px, this.height, 0x00000000, 0x00000000);
		g.fill(this.px - 2, this.top + 2, this.px + PANEL_W + 2, this.bottom + 2, 0x60000000);
		g.fill(this.px, this.top, this.px + PANEL_W, this.bottom, Style.INK);
		g.outline(this.px, this.top, PANEL_W, this.bottom - this.top, Style.LINE);
		g.fill(this.px, this.top, this.px + PANEL_W, this.top + 2, Style.RED);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = this.px + 12, w = PANEL_W - 24;
		// заголовок
		g.pose().pushMatrix();
		g.pose().translate(x, this.top + 10);
		g.pose().scale(1.5F, 1.5F);
		g.text(this.font, Component.literal("PHASE").withStyle(ChatFormatting.BOLD), 0, 0, Style.RED, false);
		g.pose().popMatrix();
		g.text(this.font, "相", x + 54, this.top + 13, Style.GOLD, false);
		String who = this.minecraft.getUser().getName();
		g.text(this.font, "Косметика · " + who, x, this.top + 25, Style.MUTED, false);
		g.fill(x, this.top + 35, x + w, this.top + 36, Style.LINE);

		if (this.loading && CosmeticsData.mine == null) g.text(this.font, "Загружаю вашу косметику…", x, this.top + 44, Style.MUTED, false);
		this.label(g, "ШЛЯПА", x, this.hatLabelY);
		this.label(g, "ПИТОМЕЦ", x, this.petLabelY);
		this.label(g, "НАСТРОЙКИ", x, this.setLabelY);
		if (this.shopY >= 0) {
			g.fill(x, this.shopY, x + w, this.shopY + 28, Style.CARD);
			g.outline(x, this.shopY, w, 28, Style.LINE);
			g.fill(x, this.shopY, x + 2, this.shopY + 28, Style.GOLD);
			g.text(this.font, "Эндер-дракончик · 499 ₽", x + 8, this.shopY + 5, Style.TEXT, false);
			g.text(this.font, "Купить: лаунчер → «Донаты»", x + 8, this.shopY + 16, Style.MUTED, false);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		// подвал
		int fy = this.bottom - 22;
		if (!this.status.isEmpty()) g.text(this.font, this.font.plainSubstrByWidth(this.status, w), x, fy - 12, this.statusColor, false);
		g.fill(x, fy - 2, x + w, fy - 1, Style.LINE);
		g.text(this.font, "J — меню  ·  Esc — закрыть", x, fy + 4, Style.MUTED_D, false);
	}

	private void label(GuiGraphicsExtractor g, String s, int x, int y) {
		if (y < 0) return;
		g.fill(x, y + 2, x + 3, y + 7, Style.RED);
		g.text(this.font, s, x + 7, y, Style.MUTED, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

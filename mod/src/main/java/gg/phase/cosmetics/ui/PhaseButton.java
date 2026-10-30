package gg.phase.cosmetics.ui;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Кнопка в стиле Phase: тёмная плашка, тонкая рамка, алая полоска у выбранной, золотой индикатор у переключателя. */
public class PhaseButton extends AbstractButton {
	public enum Kind { CHIP, ACTION, TOGGLE }

	private final Runnable action;
	private final Kind kind;
	private final BooleanSupplier on;

	public PhaseButton(int x, int y, int w, int h, String label, Kind kind, BooleanSupplier on, Runnable action) {
		super(x, y, w, h, Component.literal(label));
		this.kind = kind;
		this.on = on == null ? () -> false : on;
		this.action = action;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		this.action.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
		boolean sel = this.kind == Kind.CHIP && this.on.getAsBoolean();
		boolean hov = this.active && this.isHoveredOrFocused();
		int bg = !this.active ? 0xC0141118 : sel ? 0xF0461019 : hov ? 0xF0272030 : this.kind == Kind.ACTION ? 0xF01D1824 : 0xF0181420;
		int border = !this.active ? 0xFF221D29 : sel ? Style.RED : hov ? 0xFF54485F : Style.LINE;
		g.fill(x, y, x + w, y + h, bg);
		g.outline(x, y, w, h, border);
		if (sel) g.fill(x, y, x + 2, y + h, Style.RED);
		if (hov && !sel) g.fill(x + 1, y + h - 1, x + w - 1, y + h, Style.RED);
		int color = !this.active ? Style.MUTED_D : sel || hov ? Style.TEXT : Style.TEXT_D;
		var font = Minecraft.getInstance().font;
		int ty = y + (h - 8) / 2;
		if (this.kind == Kind.TOGGLE) {
			g.text(font, this.getMessage(), x + 8, ty, color, false);
			boolean v = this.on.getAsBoolean();
			int sx = x + w - 30, sy = y + (h - 10) / 2;
			g.fill(sx, sy, sx + 22, sy + 10, v ? 0xFF5A1219 : 0xFF100D14);
			g.outline(sx, sy, 22, 10, v ? Style.RED : Style.LINE);
			int kx = v ? sx + 13 : sx + 2;
			g.fill(kx, sy + 2, kx + 7, sy + 8, v ? Style.GOLD : 0xFF5C5466);
		} else {
			g.centeredText(font, this.getMessage(), x + w / 2 + (sel ? 1 : 0), ty, color);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}

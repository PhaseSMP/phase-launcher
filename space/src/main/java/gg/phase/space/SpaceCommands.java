package gg.phase.space;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import gg.phase.space.SolarSystem.Body;
import gg.phase.space.SolarSystem.Deep;
import gg.phase.space.SpaceEvents.Event;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;

/**
 * Команда /space (она же /космос).
 * <pre>
 * /space                  — инструкция
 * /space info             — открыт ли космос, где вы, какое событие идёт и какое следующее
 * /space events           — расписание событий на ближайшие 2 часа
 * /space list             — все планеты, спутники и объекты дальнего космоса
 * /space go [игроки]      — в космос к Земле
 * /space home [игроки]    — домой на Землю (на поверхность)
 * /space fall [игроки]    — домой через вход в атмосферу (падение с 1940, с нагревом)
 * /space tp &lt;тело&gt; [игроки]   — в космос к планете, спутнику или объекту дальнего космоса
 * /space land &lt;тело&gt; [игроки] — сразу на поверхность мира планеты
 * /space open | close     — открыть / закрыть космос (закрытие отправляет всех домой)
 * /space reload           — перечитать config/phasespace.json
 * /space eclipse          — хаос затмения: start [сек] (над Фудзи) · here [сек] (здесь) · stop
 * </pre>
 * Админ-команды требуют прав гейм-мастера (op 2+). go и home для себя — всем, если playerCommands: true.
 */
public final class SpaceCommands {
	private static final int ACCENT = 0xBFD7FF;

	private SpaceCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			for (String root : new String[] { "space", "космос" }) {
				dispatcher.register(Commands.literal(root)
					.executes(c -> help(c.getSource()))
					.then(Commands.literal("help").executes(c -> help(c.getSource())))
					.then(Commands.literal("info").executes(c -> info(c.getSource())))
					.then(Commands.literal("events").executes(c -> events(c.getSource())))
					.then(Commands.literal("list").executes(c -> list(c.getSource())))
					.then(Commands.literal("atlas").executes(c -> {
						c.getSource().sendSystemMessage(Atlas.report(c.getSource().getPlayerOrException()));
						return 1;
					}))
					.then(Commands.literal("sky").executes(c -> sky(c.getSource())))
					.then(Commands.literal("go").requires(SpaceCommands::canTravel)
						.executes(c -> go(c, self(c), null))
						.then(Commands.argument("targets", EntityArgument.players()).requires(SpaceCommands::admin)
							.executes(c -> go(c, EntityArgument.getPlayers(c, "targets"), null))))
					.then(Commands.literal("home").requires(SpaceCommands::canTravel)
						.executes(c -> home(c, self(c), false))
						.then(Commands.argument("targets", EntityArgument.players()).requires(SpaceCommands::admin)
							.executes(c -> home(c, EntityArgument.getPlayers(c, "targets"), false))))
					.then(Commands.literal("fall").requires(SpaceCommands::canTravel)
						.executes(c -> home(c, self(c), true))
						.then(Commands.argument("targets", EntityArgument.players()).requires(SpaceCommands::admin)
							.executes(c -> home(c, EntityArgument.getPlayers(c, "targets"), true))))
					.then(Commands.literal("tp").requires(SpaceCommands::admin)
						.then(Commands.argument("body", StringArgumentType.word()).suggests(ALL_TARGETS)
							.executes(c -> tp(c, self(c)))
							.then(Commands.argument("targets", EntityArgument.players())
								.executes(c -> tp(c, EntityArgument.getPlayers(c, "targets"))))))
					.then(Commands.literal("land").requires(SpaceCommands::admin)
						.then(Commands.argument("body", StringArgumentType.word()).suggests(WORLDS)
							.executes(c -> land(c, self(c)))
							.then(Commands.argument("targets", EntityArgument.players())
								.executes(c -> land(c, EntityArgument.getPlayers(c, "targets"))))))
					.then(eclipse("eclipse"))
					.then(eclipse("затмение"))
					.then(Commands.literal("open").requires(SpaceCommands::admin).executes(c -> setOpen(c.getSource(), true)))
					.then(Commands.literal("close").requires(SpaceCommands::admin).executes(c -> setOpen(c.getSource(), false)))
					.then(Commands.literal("reload").requires(SpaceCommands::admin).executes(c -> {
						SpaceConfig.reload();
						ok(c.getSource(), Component.literal("Конфиг перечитан.").withStyle(ChatFormatting.GRAY));
						return 1;
					})));
			}
		});
	}

	/** /space eclipse — хаос затмения над Фудзи */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> eclipse(String name) {
		return Commands.literal(name)
			.executes(c -> { c.getSource().sendSystemMessage(EclipseChaos.info(c.getSource().getServer())); return 1; })
			.then(Commands.literal("start").requires(SpaceCommands::admin)
				.executes(c -> eclipseStart(c.getSource(), SpaceConfig.get().eclipseSeconds, false))
				.then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(60, 3600))
					.executes(c -> eclipseStart(c.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"), false))))
			.then(Commands.literal("here").requires(SpaceCommands::admin)
				.executes(c -> eclipseStart(c.getSource(), SpaceConfig.get().eclipseSeconds, true))
				.then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(60, 3600))
					.executes(c -> eclipseStart(c.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"), true))))
			.then(Commands.literal("stop").requires(SpaceCommands::admin).executes(c -> {
				if (!EclipseChaos.active()) {
					c.getSource().sendSystemMessage(Component.literal("Затмения сейчас нет.").withStyle(ChatFormatting.GRAY));
					return 0;
				}
				EclipseChaos.stop(c.getSource().getServer());
				return 1;
			}));
	}

	private static int eclipseStart(CommandSourceStack src, int seconds, boolean here) {
		if (here && !src.getLevel().dimension().equals(Level.OVERWORLD)) {
			src.sendSystemMessage(Component.literal("Затмение бывает только в обычном мире.").withStyle(s -> s.withColor(0xFF8A5C)));
			return 0;
		}
		ok(src, EclipseChaos.start(src.getServer(), seconds, here ? src.getPosition() : null));
		return 1;
	}

	// ───────── права ─────────

	private static boolean admin(CommandSourceStack src) {
		return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static boolean canTravel(CommandSourceStack src) {
		return admin(src) || SpaceConfig.get().playerCommands && src.getPlayer() != null;
	}

	private static Collection<ServerPlayer> self(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		return List.of(c.getSource().getPlayerOrException());
	}

	// ───────── подсказки ─────────

	private static final SuggestionProvider<CommandSourceStack> ALL_TARGETS = (c, b) -> {
		List<String> ids = new ArrayList<>();
		for (Body body : SolarSystem.BODIES) ids.add(body.id);
		ids.add("sun");
		for (Deep d : SolarSystem.DEEP) ids.add(d.id);
		return SharedSuggestionProvider.suggest(ids, b);
	};

	private static final SuggestionProvider<CommandSourceStack> WORLDS = (c, b) -> {
		List<String> ids = new ArrayList<>();
		for (Body body : SolarSystem.BODIES) if (body.dim != null) ids.add(body.id);
		return SharedSuggestionProvider.suggest(ids, b);
	};

	// ───────── команды ─────────

	private static int help(CommandSourceStack src) {
		SpaceConfig cfg = SpaceConfig.get();
		MutableComponent m = Component.literal("━━━━━━━━  КОСМОС PHASE  ━━━━━━━━\n").withStyle(s -> s.withColor(ACCENT));
		line(m, "Попасть в космос", "подняться в обычном мире выше Y " + cfg.overworldExitY + " (элитры + фейерверки).");
		line(m, "Вернуться", "спуститься на 2000 блоков ниже плоскости системы (Y " + cfg.spaceReturnY + ") — вход в атмосферу, "
			+ "или влететь в куб Земли.");
		line(m, "Миры планет", "влететь в куб планеты или спутника. Обратно — подняться к верху мира.");
		line(m, "Полёт", "в космосе невесомость. Бег по прямой — разгон до ~240 бл/с.");
		line(m, "Атмосфера", "быстрый спуск выше Y " + cfg.atmosphereY + " раскаляет: пламя, искры, на сильном нагреве жжёт. "
			+ "Элитры и огнестойкость спасают.");
		line(m, "Звёзды", "ночью в подзорную трубу изучайте созвездия (12 штук, у каждого игрового дня своё), "
			+ "наводите трубу на планеты в небе. Прогресс — /space atlas, небо сезона — /space sky.");
		line(m, "Затмение", "по команде над Фудзи: Луна закрывает Солнце, из кратера бьёт луч до космоса, "
			+ "по карте идут ударные волны (щит или присед), падают осколки, в темноте летают фантомы. /space eclipse");
		line(m, "Опасно", "Солнце и звёзды жгут, чёрная дыра Ёми притягивает, пульсар облучает.");
		line(m, "Команды", "/space info · /space atlas · /space sky · /space events · /space list"
			+ (canTravel(src) ? " · /space go · /space home · /space fall" : ""));
		if (admin(src)) {
			line(m, "Админ", "/space tp <тело> [игроки] · /space land <тело> [игроки] · /space go|home|fall [игроки] · "
				+ "/space open · /space close · /space reload · /space eclipse start|here [сек] · /space eclipse stop");
		}
		m.append(Component.literal("Космос сейчас: ").withStyle(ChatFormatting.GRAY))
			.append(cfg.spaceOpen ? Component.literal("открыт").withStyle(s -> s.withColor(0x7DFFB4)) : Component.literal("закрыт").withStyle(s -> s.withColor(0xFF8A5C)));
		src.sendSystemMessage(m);
		return 1;
	}

	private static void line(MutableComponent m, String head, String text) {
		m.append(Component.literal("◆ " + head + ": ").withStyle(s -> s.withColor(ACCENT)))
			.append(Component.literal(text + "\n").withStyle(ChatFormatting.GRAY));
	}

	private static int info(CommandSourceStack src) {
		SpaceConfig cfg = SpaceConfig.get();
		MinecraftServer server = src.getServer();
		double t = server.overworld().getGameTime();
		int inSpace = 0, onPlanets = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level().dimension().equals(SpaceTravel.SPACE)) inSpace++;
			else if (!p.level().dimension().equals(Level.OVERWORLD) && SolarSystem.byDimension(p.level().dimension().identifier().toString()) != null) onPlanets++;
		}
		MutableComponent m = Component.literal("Космос: ").withStyle(s -> s.withColor(ACCENT))
			.append(cfg.spaceOpen ? Component.literal("открыт").withStyle(s -> s.withColor(0x7DFFB4)) : Component.literal("закрыт").withStyle(s -> s.withColor(0xFF8A5C)))
			.append(Component.literal("  ·  в космосе " + inSpace + ", на планетах " + onPlanets + "\n").withStyle(ChatFormatting.GRAY));
		Event now = cfg.events ? SpaceEvents.at(t) : null;
		if (now != null) {
			m.append(Component.literal("Сейчас: " + now.type().title).withStyle(s -> s.withColor(now.type().color)))
				.append(Component.literal(" — ещё " + minutes(now.end() - t) + "\n").withStyle(ChatFormatting.GRAY));
		}
		Event next = cfg.events ? SpaceEvents.next(t) : null;
		if (next != null) {
			m.append(Component.literal("Дальше: " + next.type().title).withStyle(s -> s.withColor(next.type().color)))
				.append(Component.literal(" через " + minutes(next.start() - t)).withStyle(ChatFormatting.GRAY));
		}
		ServerPlayer p = src.getPlayer();
		if (p != null) {
			String where = p.level().dimension().equals(SpaceTravel.SPACE) ? "в космосе"
				: p.level().dimension().equals(Level.OVERWORLD) ? "на Земле, до космоса " + Math.max(0, Math.round(cfg.overworldExitY - p.getY())) + " бл вверх"
				: SolarSystem.byDimension(p.level().dimension().identifier().toString()) != null
					? "на " + SolarSystem.byDimension(p.level().dimension().identifier().toString()).name : "в другом мире";
			m.append(Component.literal("\nВы: " + where).withStyle(ChatFormatting.GRAY));
		}
		src.sendSystemMessage(m);
		return 1;
	}

	private static int events(CommandSourceStack src) {
		double t = src.getServer().overworld().getGameTime();
		MutableComponent m = Component.literal("События космоса на 2 часа:").withStyle(s -> s.withColor(ACCENT));
		long slot = (long) Math.floor(t / SpaceEvents.SLOT);
		int shown = 0;
		for (long k = slot; k < slot + 8; k++) {
			Event e = SpaceEvents.inSlot(k);
			if (e == null || e.end() < t) continue;
			String when = e.start() <= t ? "идёт, ещё " + minutes(e.end() - t) : "через " + minutes(e.start() - t);
			m.append(Component.literal("\n◆ " + e.type().title).withStyle(s -> s.withColor(e.type().color)))
				.append(Component.literal("  " + when).withStyle(ChatFormatting.GRAY));
			shown++;
		}
		if (shown == 0) m.append(Component.literal("\nтихо").withStyle(ChatFormatting.GRAY));
		if (!SpaceConfig.get().events) m.append(Component.literal("\n(события выключены в конфиге)").withStyle(ChatFormatting.DARK_GRAY));
		src.sendSystemMessage(m);
		return shown;
	}

	private static int sky(CommandSourceStack src) {
		double t = src.getServer().overworld().getGameTime();
		SolarSystem.Constellation c = Atlas.seasonal(t);
		long day = (long) (t / 24000);
		MutableComponent m = Component.literal("Небо Земли").withStyle(s -> s.withColor(ACCENT));
		if (c != null) m.append(Component.literal("\nСозвездие сезона (день " + (day % 12 + 1) + " из 12): ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(c.name).withStyle(s -> s.withColor(c.color)));
		m.append(Component.literal("\nПланеты: Меркурий и Венера — у Солнца в сумерках, Марс, Юпитер, Сатурн и фиолетовая Флорана — "
			+ "на пути Солнца. Наведите подзорную трубу — узнаете, что это.").withStyle(ChatFormatting.GRAY));
		m.append(Component.literal("\nИзучение: ночью смотрите на созвездие в подзорную трубу ~8 секунд. Атлас — /space atlas").withStyle(ChatFormatting.GRAY));
		src.sendSystemMessage(m);
		return 1;
	}

	private static int list(CommandSourceStack src) {
		MutableComponent m = Component.literal("Тела (id → /space tp | land):").withStyle(s -> s.withColor(ACCENT));
		for (Body b : SolarSystem.BODIES) {
			String kind = b.parent == null ? "" : "  спутник " + b.parent.name;
			m.append(Component.literal("\n" + (b.parent == null ? "◆ " : "   · ") + b.name).withStyle(s -> s.withColor(b.color)))
				.append(Component.literal("  " + b.id + kind + (b.dim == null ? "  (без мира)" : "")).withStyle(ChatFormatting.DARK_GRAY));
		}
		m.append(Component.literal("\nДальний космос:").withStyle(s -> s.withColor(ACCENT)));
		for (Deep d : SolarSystem.DEEP) {
			m.append(Component.literal("\n◆ " + d.name).withStyle(s -> s.withColor(d.color)))
				.append(Component.literal("  " + d.id + String.format(Locale.ROOT, "  (%.0f %.0f %.0f)", d.x, d.y, d.z)).withStyle(ChatFormatting.DARK_GRAY));
		}
		src.sendSystemMessage(m);
		return 1;
	}

	private static int go(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players, Body near) {
		CommandSourceStack src = c.getSource();
		if (!SpaceConfig.get().spaceOpen && !admin(src)) {
			src.sendFailure(Component.literal("Космос закрыт."));
			return 0;
		}
		for (ServerPlayer p : players) SpaceTravel.sendToSpace(src.getServer(), p, near);
		ok(src, Component.literal("В космос: " + names(players)).withStyle(s -> s.withColor(ACCENT)));
		return players.size();
	}

	private static int home(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players, boolean fall) {
		CommandSourceStack src = c.getSource();
		for (ServerPlayer p : players) SpaceTravel.sendHome(src.getServer(), p, fall);
		ok(src, Component.literal((fall ? "Вход в атмосферу: " : "Домой: ") + names(players)).withStyle(s -> s.withColor(0x8FC8FF)));
		return players.size();
	}

	private static int tp(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players) {
		CommandSourceStack src = c.getSource();
		String id = StringArgumentType.getString(c, "body").toLowerCase(Locale.ROOT);
		Deep d = SolarSystem.deep(id);
		Body b = id.equals("sun") ? null : SolarSystem.byId(id);
		if (d == null && b == null && !id.equals("sun")) {
			src.sendFailure(Component.literal("Нет такого тела: " + id + ". Список — /space list"));
			return 0;
		}
		for (ServerPlayer p : players) {
			if (d != null) SpaceTravel.sendToDeep(src.getServer(), p, d);
			else if (id.equals("sun")) SpaceTravel.sendToSpace(src.getServer(), p, SolarSystem.byId("mercury"));
			else SpaceTravel.sendToSpace(src.getServer(), p, b);
		}
		String name = d != null ? d.name : b != null ? b.name : "Солнце";
		ok(src, Component.literal(name + ": " + names(players)).withStyle(s -> s.withColor(ACCENT)));
		return players.size();
	}

	private static int land(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players) {
		CommandSourceStack src = c.getSource();
		String id = StringArgumentType.getString(c, "body").toLowerCase(Locale.ROOT);
		Body b = SolarSystem.byId(id);
		if (b == null || b.dim == null) {
			src.sendFailure(Component.literal("У этого тела нет своего мира: " + id + ". Список — /space list"));
			return 0;
		}
		for (ServerPlayer p : players) SpaceTravel.landOn(src.getServer(), p, b);
		ok(src, Component.literal(b.name + ", поверхность: " + names(players)).withStyle(s -> s.withColor(b.color)));
		return players.size();
	}

	private static int setOpen(CommandSourceStack src, boolean open) {
		SpaceConfig.get().spaceOpen = open;
		SpaceConfig.save();
		MinecraftServer server = src.getServer();
		int moved = open ? 0 : SpaceTravel.closeSpace(server);
		Component msg = open
			? Component.literal("◆ Космос открыт. Поднимитесь выше Y " + SpaceConfig.get().overworldExitY + ".").withStyle(s -> s.withColor(0x7DFFB4))
			: Component.literal("◆ Космос закрыт." + (moved > 0 ? " Возвращено на Землю: " + moved + "." : "")).withStyle(s -> s.withColor(0xFF8A5C));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(msg);
		if (src.getPlayer() == null) ok(src, msg);
		return 1;
	}

	// ───────── мелочи ─────────

	private static void ok(CommandSourceStack src, Component msg) {
		src.sendSuccess(() -> msg, true);
	}

	private static String names(Collection<ServerPlayer> players) {
		if (players.size() > 3) return players.size() + " игроков";
		StringBuilder sb = new StringBuilder();
		for (ServerPlayer p : players) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(p.getGameProfile().name());
		}
		return sb.toString();
	}

	private static String minutes(double ticks) {
		long sec = Math.max(0, Math.round(ticks / 20));
		return sec < 60 ? sec + " с" : (sec / 60) + " мин " + (sec % 60) + " с";
	}
}

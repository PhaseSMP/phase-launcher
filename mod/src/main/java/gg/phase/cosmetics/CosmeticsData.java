package gg.phase.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;

/**
 * Кто что надел: раз в 30 секунд спрашивает сервер аккаунтов Phase (то же самое видно в лаунчере).
 * Свою косметику игрок меняет прямо в игре (меню J) — изменения видны у него сразу, у остальных — в течение полуминуты.
 */
public final class CosmeticsData {
	public record Worn(String hat, String pet, String mode, String aura, String petColor, String trail, String clan, String clanColor, String badge) {}
	public record Item(String id, String slot, String name, int price) {}
	public record Mine(List<Item> items, List<Item> shop, String hat, String pet, String mode, String aura, String petColor, String trail) {}

	public static final String BASE = System.getProperty("phase.api", "https://phase.fujimc.workers.dev");
	private static volatile Map<String, Worn> worn = Map.of();
	private static volatile Worn TEST = parseTest(System.getProperty("phase.cosmetics.test"));
	private static final Map<String, Worn> OVERRIDE = new ConcurrentHashMap<>();
	private static final Map<String, Long> OVERRIDE_AT = new ConcurrentHashMap<>();
	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	public static volatile Mine mine;

	private CosmeticsData() {}

	/** Для автотеста: надеть всем игрокам (null — снять подмену) */
	public static void setTest(String hat, String pet) { setTest(hat, pet, null); }
	public static void setTest(String hat, String pet, String aura) { TEST = hat == null && pet == null && aura == null ? null : new Worn(hat, pet, "follow", aura, null, null, null, null, null); }
	/** Для автотеста: полный набор — раскраска дракончика, след, клан и значок */
	public static void setTestFull(String hat, String pet, String aura, String petColor, String trail) {
		TEST = new Worn(hat, pet, "follow", aura, petColor, trail, "FUJI", "sakura", "max");
	}
	public static boolean isTest() { return TEST != null; }

	public static Worn get(String name) {
		if (name == null) return null;
		String key = name.toLowerCase(Locale.ROOT);
		Long at = OVERRIDE_AT.get(key);
		if (at != null && System.currentTimeMillis() - at < 45_000) return OVERRIDE.get(key);
		if (TEST != null) return TEST;
		return worn.get(key);
	}

	/** Своё изменение — показываем сразу, не дожидаясь опроса сервера */
	static void overrideOwn(String name, String hat, String pet, String aura, String petColor, String trail) {
		String key = name.toLowerCase(Locale.ROOT);
		Worn polled = worn.get(key); // тег клана и значок берём из общего опроса — их меняют не в игре
		OVERRIDE.put(key, new Worn(hat, pet, Config.get().mode, aura, petColor, trail,
			polled == null ? null : polled.clan(), polled == null ? null : polled.clanColor(), polled == null ? null : polled.badge()));
		OVERRIDE_AT.put(key, System.currentTimeMillis());
	}

	static void start() {
		Thread t = new Thread(CosmeticsData::loop, "Phase cosmetics");
		t.setDaemon(true);
		t.start();
	}

	private static void loop() {
		while (true) {
			try {
				HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/api/cosmetics/worn")).timeout(Duration.ofSeconds(15))
					.header("User-Agent", "PhaseCosmetics/2").GET().build(), HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() == 200) {
					JsonObject players = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("players");
					Map<String, Worn> next = new ConcurrentHashMap<>();
					for (Map.Entry<String, JsonElement> e : players.entrySet()) {
						JsonObject o = e.getValue().getAsJsonObject();
						String mode = str(o, "mode");
						next.put(e.getKey().toLowerCase(Locale.ROOT), new Worn(str(o, "hat"), str(o, "pet"), mode == null ? "follow" : mode, str(o, "aura"), str(o, "petColor"),
							str(o, "trail"), str(o, "clan"), str(o, "clanColor"), str(o, "role") != null ? str(o, "role") : str(o, "plan")));
					}
					worn = next;
				}
			} catch (Exception ignored) {
				// нет сети — показываем то, что было
			}
			try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
		}
	}

	// ───────── API игрока (токен игровой сессии от лаунчера) ─────────
	private static String token() {
		try {
			return Minecraft.getInstance().getUser().getAccessToken();
		} catch (Exception e) {
			return "";
		}
	}

	private static CompletableFuture<JsonObject> call(String method, String path, String body) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(15))
			.header("User-Agent", "PhaseCosmetics/2").header("Authorization", "Bearer " + token());
		if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
		else b.method(method, HttpRequest.BodyPublishers.noBody());
		return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
			JsonObject o;
			try { o = JsonParser.parseString(r.body()).getAsJsonObject(); } catch (Exception e) { o = new JsonObject(); }
			if (r.statusCode() != 200) {
				String err = o.has("error") ? o.get("error").getAsString() : r.statusCode() == 401 ? "Войдите через лаунчер Phase" : "Сервер Phase недоступен";
				throw new IllegalStateException(err);
			}
			return o;
		});
	}

	/** Своя косметика: что есть, что надето, что можно купить */
	public static CompletableFuture<Mine> loadMine() {
		if (TEST != null) {
			Mine m = new Mine(List.of(new Item("kabuto", "hat", "Шлем кабуто", 0), new Item("straw_hat", "hat", "Соломенная каса", 0),
				new Item("ender_dragon", "pet", "Эндер-дракончик", 0), new Item("aura_sakura", "aura", "Сакура", 0), new Item("aura_ender", "aura", "Эндер", 0), new Item("aura_oni", "aura", "Пламя они", 0),
				new Item("trail_sakura", "trail", "След «Лепестки»", 0), new Item("trail_ember", "trail", "След «Угли»", 0), new Item("trail_frost", "trail", "След «Иней»", 0), new Item("trail_stars", "trail", "След «Звёздная пыль»", 0)),
				List.of(new Item("oni_horns", "hat", "Рога они", 199)), TEST.hat(), TEST.pet(), Config.get().mode, TEST.aura(), TEST.petColor(), TEST.trail());
			mine = m;
			return CompletableFuture.completedFuture(m);
		}
		return call("GET", "/api/game/me", null).thenApply(CosmeticsData::parseMine);
	}

	public static CompletableFuture<Mine> equip(String slot, String item) {
		String name = Minecraft.getInstance().getUser().getName();
		Mine cur = mine;
		if (cur != null) {
			String hat = slot.equals("hat") ? item : cur.hat(), pet = slot.equals("pet") ? item : cur.pet(), aura = slot.equals("aura") ? item : cur.aura();
			String trail = slot.equals("trail") ? item : cur.trail();
			overrideOwn(name, hat, pet, aura, cur.petColor(), trail);
			mine = new Mine(cur.items(), cur.shop(), hat, pet, cur.mode(), aura, cur.petColor(), trail);
		}
		if (TEST != null) {
			TEST = new Worn(mine.hat(), mine.pet(), "follow", mine.aura(), mine.petColor(), mine.trail(), null, null, null);
			return CompletableFuture.completedFuture(mine);
		}
		JsonObject b = new JsonObject();
		b.addProperty("slot", slot);
		if (item != null) b.addProperty("item", item);
		return call("POST", "/api/game/equip", b.toString()).thenApply(CosmeticsData::parseMine);
	}

	/** Раскраска дракончика из меню J: видна у себя сразу, у остальных — в течение полуминуты */
	public static CompletableFuture<Mine> setPetColor(String color) {
		Mine cur = mine;
		String c = color == null || color.equals("default") ? null : color;
		if (cur != null) {
			mine = new Mine(cur.items(), cur.shop(), cur.hat(), cur.pet(), cur.mode(), cur.aura(), c == null ? "default" : c, cur.trail());
			overrideOwn(Minecraft.getInstance().getUser().getName(), cur.hat(), cur.pet(), cur.aura(), c, cur.trail());
		}
		if (TEST != null) {
			TEST = new Worn(TEST.hat(), TEST.pet(), TEST.mode(), TEST.aura(), c, TEST.trail(), TEST.clan(), TEST.clanColor(), TEST.badge());
			return CompletableFuture.completedFuture(mine);
		}
		JsonObject b = new JsonObject();
		b.addProperty("color", c == null ? "default" : c);
		return call("POST", "/api/game/pet-color", b.toString()).thenApply(o -> mine);
	}

	public static void sendMode(String mode) {
		Config.get().mode = mode;
		Config.get().save();
		if (TEST != null) return;
		JsonObject b = new JsonObject();
		b.addProperty("mode", mode);
		call("POST", "/api/game/pet", b.toString()).exceptionally(e -> null);
	}

	private static Mine parseMine(JsonObject o) {
		JsonObject eq = o.getAsJsonObject("equipped");
		Mine m = new Mine(items(o.getAsJsonArray("items")), items(o.getAsJsonArray("shop")), str(eq, "hat"), str(eq, "pet"), str(o, "mode"), str(eq, "aura"), str(o, "petColor"), str(eq, "trail"));
		mine = m;
		overrideOwn(Minecraft.getInstance().getUser().getName(), m.hat(), m.pet(), m.aura(), m.petColor(), m.trail());
		return m;
	}

	private static List<Item> items(JsonArray a) {
		List<Item> out = new ArrayList<>();
		if (a == null) return out;
		for (JsonElement e : a) {
			JsonObject o = e.getAsJsonObject();
			out.add(new Item(str(o, "id"), str(o, "slot"), str(o, "name"), o.has("price") ? o.get("price").getAsInt() : 0));
		}
		return out;
	}

	static String str(JsonObject o, String k) {
		if (o == null) return null;
		JsonElement v = o.get(k);
		return v == null || v.isJsonNull() ? null : v.getAsString();
	}

	private static Worn parseTest(String s) {
		if (s == null || s.isBlank()) return null;
		String hat = null, pet = null;
		for (String part : s.split(",")) {
			String[] kv = part.split("=", 2);
			if (kv.length < 2) continue;
			if (kv[0].trim().equals("hat")) hat = kv[1].trim();
			if (kv[0].trim().equals("pet")) pet = kv[1].trim();
		}
		return new Worn(hat, pet, "follow", null, null, null, null, null, null);
	}
}

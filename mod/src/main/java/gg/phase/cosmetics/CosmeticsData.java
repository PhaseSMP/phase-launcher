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
 * Кто что надел: раз в 15 секунд спрашивает сервер аккаунтов Phase (то же самое видно в лаунчере).
 * Свою косметику игрок меняет прямо в игре (меню J) — изменения видны у него сразу, у остальных — в течение 15–20 секунд.
 */
public final class CosmeticsData {
	/** fx — включённые части пака: a — аура, t — след, b — приземление (null — всё включено);
	    fx2 — d — смерть, k — убийство игрока, s — звуки (null — всё включено) */
	public record Worn(String hat, String pet, String mode, String aura, String petColor, String fx, String clan, String clanColor, String badge, String fx2) {}
	public record Item(String id, String slot, String name, int price) {}
	/** fx — все шесть частей пака одной строкой (a, t, b, d, k, s; null — всё включено), vol — громкость звуков паков 0–100 */
	public record Mine(List<Item> items, List<Item> shop, String hat, String pet, String mode, String aura, String petColor, String fx, int vol) {}

	public static final String BASE = System.getProperty("phase.api", "https://phase.fujimc.workers.dev");
	private static volatile Map<String, Worn> worn = Map.of();
	private static volatile Worn TEST = parseTest(System.getProperty("phase.cosmetics.test"));
	private static final Map<String, Worn> OVERRIDE = new ConcurrentHashMap<>();
	private static final Map<String, Long> OVERRIDE_AT = new ConcurrentHashMap<>();
	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	public static volatile Mine mine;
	private static volatile Thread poller;
	/** Настройка шляпы (сдвиг и поворот) у других игроков: имя → "x,y,z,rx,ry,rz,s" */
	private static volatile Map<String, String> adjOf = Map.of();
	/** Мои настройки шляп: шляпа → {x, y, z, rx, ry, rz, s} */
	public static final Map<String, float[]> MY_ADJ = new ConcurrentHashMap<>();
	private static volatile long localChangeAt;

	private CosmeticsData() {}

	/** Для автотеста: надеть всем игрокам (null — снять подмену) */
	public static void setTest(String hat, String pet) { setTest(hat, pet, null); }
	public static void setTest(String hat, String pet, String aura) { TEST = hat == null && pet == null && aura == null ? null : new Worn(hat, pet, "follow", aura, null, null, null, null, null, null); }
	/** Для автотеста: полный набор — раскраска дракончика, след, клан и значок */
	public static void setTestFull(String hat, String pet, String aura, String petColor, String fx) {
		TEST = new Worn(hat, pet, "follow", aura, petColor, atb(fx), "FUJI", "sakura", "max", dks(fx));
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
	static void overrideOwn(String name, String hat, String pet, String aura, String petColor, String fx) {
		localChangeAt = System.currentTimeMillis();
		String key = name.toLowerCase(Locale.ROOT);
		Worn polled = worn.get(key); // тег клана и значок берём из общего опроса — их меняют не в игре
		OVERRIDE.put(key, new Worn(hat, pet, Config.get().mode, aura, petColor, atb(fx),
			polled == null ? null : polled.clan(), polled == null ? null : polled.clanColor(), polled == null ? null : polled.badge(), dks(fx)));
		OVERRIDE_AT.put(key, System.currentTimeMillis());
	}

	static void start() {
		Thread t = new Thread(CosmeticsData::loop, "Phase cosmetics");
		t.setDaemon(true);
		poller = t;
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
					Map<String, String> adj = new ConcurrentHashMap<>();
					for (Map.Entry<String, JsonElement> e : players.entrySet()) {
						JsonObject o = e.getValue().getAsJsonObject();
						String mode = str(o, "mode");
						next.put(e.getKey().toLowerCase(Locale.ROOT), new Worn(str(o, "hat"), str(o, "pet"), mode == null ? "follow" : mode, str(o, "aura"), str(o, "petColor"),
							str(o, "fx"), str(o, "clan"), str(o, "clanColor"), str(o, "role") != null ? str(o, "role") : str(o, "plan"), str(o, "fx2")));
						String a = str(o, "adj");
						if (a != null) adj.put(e.getKey().toLowerCase(Locale.ROOT), a);
					}
					worn = next;
					adjOf = adj;
					try {
						JsonElement me = players.get(Minecraft.getInstance().getUser().getName());
						if (me == null) for (Map.Entry<String, JsonElement> e : players.entrySet()) if (e.getKey().equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) me = e.getValue();
						String sig = me == null ? "" : me.toString();
						if (!sig.equals(ownSig)) { ownSig = sig; ownChanged = true; }
					} catch (Exception ignored) { ownChanged = true; }
				}
			} catch (Exception ignored) {
				// нет сети — показываем то, что было
			}
			// своё (раскраска, шляпа, пак) могли поменять в лаунчере — перечитываем, если в игре ничего не меняли только что
			// Перечитываем, только когда своё надетое поменялось (в лаунчере), или раз в 5 минут — меньше нагрузки на сервер при 50+ игроках
			long nowMs = System.currentTimeMillis();
			if (TEST == null && nowMs - localChangeAt > 8_000 && !token().isEmpty() && (ownChanged || nowMs - mineAt > 300_000)) {
				ownChanged = false;
				mineAt = nowMs;
				try { loadMine().get(15, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { /* нет сети */ }
			}
			try { Thread.sleep(20_000); } catch (InterruptedException e) { /* «Перезагрузить» — опрашиваем сразу */ }
		}
	}

	private static volatile String ownSig = null;
	private static volatile boolean ownChanged = true;
	private static volatile long mineAt = 0;

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
				new Item("ender_dragon", "pet", "Эндер-дракончик", 0), new Item("aura_sakura", "aura", "Пак «Сакура»", 0), new Item("aura_ender", "aura", "Пак «Эндер»", 0), new Item("aura_oni", "aura", "Пак «Пламя они»", 0), new Item("aura_eclipse", "aura", "Пак «Затмение»", 0)),
				List.of(new Item("oni_horns", "hat", "Рога они", 149)), TEST.hat(), TEST.pet(), Config.get().mode, TEST.aura(), TEST.petColor(), join(TEST.fx(), TEST.fx2()), 80);
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
			overrideOwn(name, hat, pet, aura, cur.petColor(), cur.fx());
			mine = new Mine(cur.items(), cur.shop(), hat, pet, cur.mode(), aura, cur.petColor(), cur.fx(), cur.vol());
		}
		if (TEST != null) {
			TEST = new Worn(mine.hat(), mine.pet(), "follow", mine.aura(), mine.petColor(), atb(mine.fx()), null, null, null, dks(mine.fx()));
			return CompletableFuture.completedFuture(mine);
		}
		JsonObject b = new JsonObject();
		b.addProperty("slot", slot);
		if (item != null) b.addProperty("item", item);
		return call("POST", "/api/game/equip", b.toString()).thenApply(CosmeticsData::parseMine);
	}

	static final String PARTS = "atbdks";

	/** Части пака из ответа сервера: {aura, trail, burst, death, kill, sound} → строка включённых букв (null — всё включено) */
	static String fxLetters(JsonObject fx) {
		if (fx == null) return null;
		String[] keys = { "aura", "trail", "burst", "death", "kill", "sound" };
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < keys.length; i++) if (!fx.has(keys[i]) || fx.get(keys[i]).getAsBoolean()) b.append(PARTS.charAt(i));
		return b.length() == PARTS.length() ? null : b.toString();
	}

	private static String pick(String fx, String parts) {
		if (fx == null) return null;
		StringBuilder b = new StringBuilder();
		for (char c : parts.toCharArray()) if (fx.indexOf(c) >= 0) b.append(c);
		return b.length() == parts.length() ? null : b.toString();
	}
	static String atb(String fx) { return pick(fx, "atb"); }
	static String dks(String fx) { return pick(fx, "dks"); }
	static String join(String fx, String fx2) {
		if (fx == null && fx2 == null) return null;
		return (fx == null ? "atb" : fx) + (fx2 == null ? "dks" : fx2);
	}

	/** Включена ли часть пака в строке букв (null — всё включено) */
	public static boolean fxOn(String fx, char part) { return fx == null || fx.indexOf(part) >= 0; }

	/** Включена ли часть пака у игрока: a, t, b — в fx; d, k, s — в fx2 */
	public static boolean on(Worn w, char part) { return fxOn("atb".indexOf(part) >= 0 ? w.fx() : w.fx2(), part); }

	/** Звуки паков у меня: включены ли и громкость 0..1 (настройка общая с лаунчером) */
	public static float packVolume() {
		Mine m = mine;
		if (m != null && !fxOn(m.fx(), 's')) return 0F;
		if (!Config.get().packSounds) return 0F;
		return (m == null ? 80 : m.vol()) / 100F;
	}

	/** Переключить часть пака в меню на Ё (у себя видно сразу, у остальных — в течение полуминуты) */
	public static CompletableFuture<Mine> toggleFx(char part) {
		Mine cur = mine;
		String fx = cur == null ? null : cur.fx();
		StringBuilder b = new StringBuilder();
		for (char c : PARTS.toCharArray()) if (fxOn(fx, c) != (c == part)) b.append(c);
		String nf = b.length() == PARTS.length() ? null : b.toString();
		return saveFx(nf, cur == null ? 80 : cur.vol());
	}

	/** Громкость звуков паков (0–100) */
	public static CompletableFuture<Mine> setVol(int vol) {
		Mine cur = mine;
		return saveFx(cur == null ? null : cur.fx(), Math.max(0, Math.min(100, vol)));
	}

	private static CompletableFuture<Mine> saveFx(String nf, int vol) {
		Mine cur = mine;
		if (cur != null) {
			mine = new Mine(cur.items(), cur.shop(), cur.hat(), cur.pet(), cur.mode(), cur.aura(), cur.petColor(), nf, vol);
			overrideOwn(Minecraft.getInstance().getUser().getName(), cur.hat(), cur.pet(), cur.aura(), cur.petColor(), nf);
		}
		if (TEST != null) {
			TEST = new Worn(TEST.hat(), TEST.pet(), TEST.mode(), TEST.aura(), TEST.petColor(), atb(nf), TEST.clan(), TEST.clanColor(), TEST.badge(), dks(nf));
			return CompletableFuture.completedFuture(mine);
		}
		JsonObject body = new JsonObject();
		String[] keys = { "aura", "trail", "burst", "death", "kill", "sound" };
		for (int i = 0; i < keys.length; i++) body.addProperty(keys[i], fxOn(nf, PARTS.charAt(i)));
		body.addProperty("vol", vol);
		return call("POST", "/api/game/fx", body.toString()).thenApply(o -> mine);
	}

	/** Кнопка «Перезагрузить»: заново спросить сервер, сбросить кэш моделей и скинов */
	public static CompletableFuture<Mine> reload() {
		OVERRIDE.clear();
		OVERRIDE_AT.clear();
		Thread t = poller;
		if (t != null) t.interrupt(); // опрос сразу, не дожидаясь 30 секунд
		Minecraft.getInstance().execute(CosmeticsModels::invalidate);
		SkinData.clear();
		return loadMine();
	}

	/** Раскраска дракончика из меню J: видна у себя сразу, у остальных — в течение полуминуты */
	public static CompletableFuture<Mine> setPetColor(String color) {
		Mine cur = mine;
		String c = color == null || color.equals("default") ? null : color;
		if (cur != null) {
			mine = new Mine(cur.items(), cur.shop(), cur.hat(), cur.pet(), cur.mode(), cur.aura(), c == null ? "default" : c, cur.fx(), cur.vol());
			overrideOwn(Minecraft.getInstance().getUser().getName(), cur.hat(), cur.pet(), cur.aura(), c, cur.fx());
		}
		if (TEST != null) {
			TEST = new Worn(TEST.hat(), TEST.pet(), TEST.mode(), TEST.aura(), c, TEST.fx(), TEST.clan(), TEST.clanColor(), TEST.badge(), TEST.fx2());
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
		JsonObject fx = o.getAsJsonObject("fx");
		int vol = fx != null && fx.has("vol") ? fx.get("vol").getAsInt() : 80;
		Mine m = new Mine(items(o.getAsJsonArray("items")), items(o.getAsJsonArray("shop")), str(eq, "hat"), str(eq, "pet"), str(o, "mode"), str(eq, "aura"), str(o, "petColor"), fxLetters(fx), vol);
		JsonObject adj = o.getAsJsonObject("hatAdj");
		if (adj != null && System.currentTimeMillis() - localChangeAt > 8_000) {
			MY_ADJ.clear();
			for (Map.Entry<String, JsonElement> e : adj.entrySet()) {
				float[] v = parseAdj(e.getValue().getAsString());
				if (v != null) MY_ADJ.put(e.getKey(), v);
			}
		}
		long keep = localChangeAt;
		mine = m;
		overrideOwn(Minecraft.getInstance().getUser().getName(), m.hat(), m.pet(), m.aura(), m.petColor(), m.fx());
		localChangeAt = keep;
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

	// ───────── Настройка шляпы ─────────
	public static final float[] NO_ADJ = { 0, 0, 0, 0, 0, 0, 1 };

	static float[] parseAdj(String s) {
		if (s == null) return null;
		String[] a = s.split(",");
		if (a.length != 7) return null;
		float[] v = new float[7];
		try { for (int i = 0; i < 7; i++) v[i] = Float.parseFloat(a[i]); } catch (NumberFormatException e) { return null; }
		return v;
	}

	static String adjStr(float[] v) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 7; i++) {
			if (i > 0) b.append(',');
			float x = Math.round(v[i] * 100F) / 100F;
			b.append(x == (int) x ? Integer.toString((int) x) : Float.toString(x));
		}
		return b.toString();
	}

	private static final Map<String, float[]> ADJ_CACHE = new ConcurrentHashMap<>();

	/** Сдвиг/поворот/размер шляпы у игрока (своя — из меню сразу, чужие — из общего опроса) */
	public static float[] adj(String name, String hat) {
		if (hat == null) return NO_ADJ;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getUser() != null && name.equalsIgnoreCase(mc.getUser().getName())) {
			float[] v = MY_ADJ.get(hat);
			return v == null ? NO_ADJ : v;
		}
		String s = adjOf.get(name.toLowerCase(Locale.ROOT));
		if (s == null) return NO_ADJ;
		return ADJ_CACHE.computeIfAbsent(s, k -> { float[] v = parseAdj(k); return v == null ? NO_ADJ : v; });
	}

	/** Сохранить настройку шляпы на сервере (у себя видно сразу) */
	public static CompletableFuture<Void> saveAdj(String hat, float[] v) {
		localChangeAt = System.currentTimeMillis();
		boolean zero = java.util.Arrays.equals(v, NO_ADJ);
		if (zero) MY_ADJ.remove(hat); else MY_ADJ.put(hat, v.clone());
		if (TEST != null) return CompletableFuture.completedFuture(null);
		JsonObject b = new JsonObject();
		b.addProperty("hat", hat);
		b.addProperty("adj", zero ? "" : adjStr(v));
		return call("POST", "/api/game/hat-adj", b.toString()).thenApply(o -> null);
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
		return new Worn(hat, pet, "follow", null, null, null, null, null, null, null);
	}
}

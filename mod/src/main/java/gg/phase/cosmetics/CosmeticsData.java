package gg.phase.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Кто что надел: раз в минуту спрашивает сервер аккаунтов Phase (то же самое видно в лаунчере). */
public final class CosmeticsData {
	public record Worn(String hat, String pet) {}

	private static final String API = System.getProperty("phase.cosmetics.api", "https://phase.fujimc.workers.dev/api/cosmetics/worn");
	private static volatile Map<String, Worn> worn = Map.of();
	/** Для проверки без сервера: -Dphase.cosmetics.test=hat=straw_hat,pet=ender_dragon — всем игрокам */
	private static volatile Worn TEST = parseTest(System.getProperty("phase.cosmetics.test"));

	/** Для автотеста: надеть всем игрокам (null — снять подмену) */
	public static void setTest(String hat, String pet) { TEST = hat == null && pet == null ? null : new Worn(hat, pet); }

	private CosmeticsData() {}

	public static Worn get(String name) {
		if (TEST != null) return TEST;
		return name == null ? null : worn.get(name.toLowerCase(Locale.ROOT));
	}

	static void start() {
		Thread t = new Thread(CosmeticsData::loop, "Phase cosmetics");
		t.setDaemon(true);
		t.start();
	}

	private static void loop() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		while (true) {
			try {
				HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(15))
					.header("User-Agent", "PhaseCosmetics/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() == 200) {
					JsonObject players = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("players");
					Map<String, Worn> next = new ConcurrentHashMap<>();
					for (Map.Entry<String, JsonElement> e : players.entrySet()) {
						JsonObject o = e.getValue().getAsJsonObject();
						next.put(e.getKey().toLowerCase(Locale.ROOT), new Worn(str(o, "hat"), str(o, "pet")));
					}
					worn = next;
				}
			} catch (Exception ignored) {
				// нет сети — показываем то, что было
			}
			try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
		}
	}

	private static String str(JsonObject o, String k) {
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
		return new Worn(hat, pet);
	}
}

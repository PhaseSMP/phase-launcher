package gg.phase.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Скины и плащи, загруженные в лаунчере Phase, — прямо из сервера аккаунтов, по нику.
 * Работает и в одиночной игре, и на серверах без проверки лицензии: подменяет то, что игра нашла сама.
 */
public final class SkinData {
	private record Entry(PlayerSkin skin, long at) {}

	private static final Map<String, Entry> SKINS = new ConcurrentHashMap<>();
	private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<ClientAsset.Texture>> TEXTURES = new ConcurrentHashMap<>();
	private static volatile PlayerSkin TEST;
	private static SkinTextureDownloader downloader;
	private static int tickIn;

	private SkinData() {}

	/** «Перезагрузить»: заново спросить скины всех игроков рядом (старые видны, пока не придут новые) */
	public static void clear() {
		ASKED.clear();
		tickIn = 0;
	}

	/** Для автотеста: этот скин всем игрокам */
	public static void setTest(PlayerSkin skin) { TEST = skin; }

	public static PlayerSkin lookup(String name) {
		if (TEST != null) return TEST;
		if (name == null) return null;
		Entry e = SKINS.get(name.toLowerCase(Locale.ROOT));
		return e == null ? null : e.skin();
	}

	/** Раз в пару секунд: кого из игроков рядом и в списке игроков ещё не спрашивали (или давно) */
	static void tick(Minecraft mc) {
		if (mc.level == null || --tickIn > 0) return;
		tickIn = 40;
		List<String> want = new ArrayList<>();
		long now = System.currentTimeMillis();
		java.util.function.Consumer<String> add = n -> {
			String k = n.toLowerCase(Locale.ROOT);
			Long at = ASKED.get(k);
			if ((at == null || now - at > 5 * 60_000) && want.size() < 50 && !want.contains(k)) want.add(k);
		};
		for (Player p : mc.level.players()) add.accept(p.getName().getString());
		if (mc.getConnection() != null) for (PlayerInfo pi : mc.getConnection().getOnlinePlayers()) add.accept(pi.getProfile().name());
		if (want.isEmpty()) return;
		for (String k : want) ASKED.put(k, now);
		HttpRequest req = HttpRequest.newBuilder(URI.create(CosmeticsData.BASE + "/api/game/skins?n=" + URLEncoder.encode(String.join(",", want), StandardCharsets.UTF_8)))
			.timeout(Duration.ofSeconds(15)).header("User-Agent", "PhaseCosmetics/2").GET().build();
		CosmeticsData.HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
			if (res.statusCode() != 200) return;
			JsonObject players = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("players");
			for (Map.Entry<String, JsonElement> e : players.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				String skin = CosmeticsData.str(o, "skin"), cape = CosmeticsData.str(o, "cape");
				boolean slim = o.has("slim") && o.get("slim").getAsBoolean();
				if (skin == null && cape == null) { SKINS.remove(e.getKey()); continue; }
				mc.execute(() -> apply(mc, e.getKey(), skin, cape, slim));
			}
		}).exceptionally(t -> null);
	}

	private static void apply(Minecraft mc, String name, String skinUrl, String capeUrl, boolean slim) {
		CompletableFuture<ClientAsset.Texture> body = skinUrl == null ? CompletableFuture.completedFuture(null) : texture(mc, skinUrl, true);
		CompletableFuture<ClientAsset.Texture> cape = capeUrl == null ? CompletableFuture.completedFuture(null) : texture(mc, capeUrl, false);
		body.thenCombine(cape, (b, c) -> {
			mc.execute(() -> {
				PlayerSkin base = PlayerSkinDefaults.of(name);
				ClientAsset.Texture tb = b != null ? b : base.body();
				ClientAsset.Texture tc = c != null ? c : base.cape();
				PlayerModelType model = b != null ? (slim ? PlayerModelType.SLIM : PlayerModelType.WIDE) : base.model();
				SKINS.put(name, new Entry(new PlayerSkin(tb, tc, tc, model, true), System.currentTimeMillis()));
			});
			return null;
		}).exceptionally(t -> null);
	}

	private static CompletableFuture<ClientAsset.Texture> texture(Minecraft mc, String url, boolean skin) {
		String hash = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
		return TEXTURES.computeIfAbsent(hash + (skin ? "s" : "c"), k -> {
			if (downloader == null) downloader = new SkinTextureDownloader(Proxy.NO_PROXY, mc.getTextureManager(), mc);
			Path cache = FabricLoader.getInstance().getGameDir().resolve("phase-skins").resolve(hash);
			Identifier id = Identifier.fromNamespaceAndPath("phase", (skin ? "skins/" : "capes/") + hash);
			return downloader.downloadAndRegisterSkin(id, cache, url, skin);
		});
	}
}

package gg.phase.space.client;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Связь с Iris (шейдеры Photon, Complementary, BSL…) без жёсткой зависимости: через публичный
 * Iris API v0 по рефлексии. Если Iris не стоит — всё возвращает false.
 *
 * Зачем: во время прохода теней Iris заново рисует сцену с точки зрения солнца шейдера.
 * Небо-призма вокруг камеры в этом проходе закрыло бы весь мир от солнца, поэтому в проходе
 * теней космос не рисуется.
 */
public final class ShaderCompat {
	private static boolean init;
	private static Object api;
	private static Method inUse, shadowPass;

	private ShaderCompat() {}

	private static void init() {
		init = true;
		if (!FabricLoader.getInstance().isModLoaded("iris")) return;
		try {
			Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			api = c.getMethod("getInstance").invoke(null);
			inUse = c.getMethod("isShaderPackInUse");
			shadowPass = c.getMethod("isRenderingShadowPass");
		} catch (Throwable t) {
			api = null;
		}
	}

	public static boolean shadersOn() {
		if (!init) init();
		if (api == null) return false;
		try {
			return (Boolean) inUse.invoke(api);
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean shadowPass() {
		if (!init) init();
		if (api == null) return false;
		try {
			return (Boolean) shadowPass.invoke(api);
		} catch (Throwable t) {
			return false;
		}
	}
}

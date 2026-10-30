package gg.phase.cosmetics.test;

import gg.phase.cosmetics.CosmeticsData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;

/** Скриншоты косметики в настоящем клиенте: шляпы спереди, дракончик в полёте, сидя и лёжа. */
public class CosmeticsGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule doDaylightCycle false");
			ctx.runOnClient(mc -> { mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); });
			for (String hat : new String[] { "straw_hat", "kitsune_mask", "oni_horns", "kabuto" }) {
				CosmeticsData.setTest(hat, "ender_dragon");
				ctx.waitTicks(10);
				ctx.takeScreenshot("hat-" + hat);
			}
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			ctx.waitTicks(5);
			ctx.takeScreenshot("back-fly");
			ctx.waitTicks(70);
			ctx.takeScreenshot("back-sit");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(5);
			ctx.takeScreenshot("front-sit");
			ctx.waitTicks(340);
			ctx.takeScreenshot("front-lie");
		}
	}
}

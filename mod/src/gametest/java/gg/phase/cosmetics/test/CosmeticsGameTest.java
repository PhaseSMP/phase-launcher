package gg.phase.cosmetics.test;

import com.mojang.blaze3d.platform.NativeImage;
import gg.phase.cosmetics.Config;
import gg.phase.cosmetics.CosmeticsData;
import gg.phase.cosmetics.SkinData;
import gg.phase.cosmetics.pet.Pet;
import gg.phase.cosmetics.pet.PetManager;
import gg.phase.cosmetics.ui.PhaseMenuScreen;
import java.io.InputStream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Скриншоты в настоящем клиенте: скин, шляпа поверх шлема, дракончик в разных позах, меню. */
public class CosmeticsGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule doDaylightCycle false");
			sp.getServer().runCommand("tp @p ~ ~ ~ 0 0");
			Config.get().mode = "follow";
			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				try (InputStream in = CosmeticsGameTest.class.getResourceAsStream("/assets/phase-test/textures/test_skin.png")) {
					Identifier id = Identifier.fromNamespaceAndPath("phase-test", "skin");
					mc.getTextureManager().register(id, new DynamicTexture(() -> "phase test skin", NativeImage.read(in)));
					ClientAsset.ResourceTexture tex = new ClientAsset.ResourceTexture(id, id);
					SkinData.setTest(new PlayerSkin(tex, null, null, PlayerModelType.WIDE, true));
				}
			});
			CosmeticsData.setTest("kabuto", "ender_dragon");
			ctx.waitTicks(40);
			ctx.takeScreenshot("01-skin-hat-pet-fly");
			sp.getServer().runCommand("item replace entity @p armor.head with minecraft:diamond_helmet");
			ctx.waitTicks(5);
			ctx.takeScreenshot("02-hat-over-helmet");
			CosmeticsData.setTest("straw_hat", "ender_dragon");
			ctx.waitTicks(5);
			ctx.takeScreenshot("03-straw-over-helmet");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			ctx.waitTicks(10);
			ctx.takeScreenshot("04-back-fly");
			ctx.waitTicks(80);
			ctx.takeScreenshot("05-back-landing");
			ctx.waitTicks(40);
			ctx.takeScreenshot("06-back-sit");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(5);
			ctx.takeScreenshot("07-front-sit");
			ctx.waitTicks(560);
			ctx.takeScreenshot("08-front-sleep");
			// погладить: подлетает к лицу
			ctx.runOnClient(mc -> PetManager.INSTANCE.command(Pet.Act.PETTED));
			ctx.waitTicks(30);
			ctx.takeScreenshot("09-petted");
			ctx.waitTicks(50);
			ctx.runOnClient(mc -> PetManager.INSTANCE.command(Pet.Act.LOOP));
			ctx.waitTicks(14);
			ctx.takeScreenshot("10-loop");
			ctx.waitTicks(30);
			ctx.runOnClient(mc -> PetManager.INSTANCE.command(Pet.Act.ROAR));
			ctx.waitTicks(20);
			ctx.takeScreenshot("11-roar");
			ctx.waitTicks(30);
			ctx.runOnClient(mc -> PetManager.INSTANCE.command(Pet.Act.SPIN));
			ctx.waitTicks(25);
			ctx.takeScreenshot("12-spin");
			ctx.waitTicks(70);
			Config.get().mode = "shoulder";
			ctx.waitTicks(40);
			ctx.takeScreenshot("13-shoulder-front");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			ctx.waitTicks(5);
			ctx.takeScreenshot("14-shoulder-back");
			Config.get().mode = "follow";
			ctx.waitTicks(30);
			ctx.getInput().holdKeyFor(o -> o.keyUp, 30);
			ctx.takeScreenshot("15-walking");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			ctx.waitTicks(20);
			ctx.takeScreenshot("16-first-person");
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			ctx.setScreen(PhaseMenuScreen::new);
			ctx.waitTicks(20);
			ctx.takeScreenshot("17-menu");
			ctx.setScreen(() -> null);
		}
	}
}

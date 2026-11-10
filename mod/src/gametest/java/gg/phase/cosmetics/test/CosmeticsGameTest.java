package gg.phase.cosmetics.test;

import com.mojang.blaze3d.platform.NativeImage;
import gg.phase.cosmetics.AuraFx;
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
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
				// ── 4.3: шарф и варежки ──
				CosmeticsData.setTestWear(null, null, null, null, null, "scarf_red", "mittens_red");
				ctx.waitTicks(30);
				ctx.takeScreenshot("w01-scarf-mittens-front");
				ctx.runOnClient(mc -> mc.player.setYRot(65F));
				ctx.waitTicks(15);
				ctx.takeScreenshot("w02-side");
				ctx.runOnClient(mc -> { mc.player.setYRot(0F); mc.player.setXRot(40F); });
				ctx.waitTicks(10);
				ctx.takeScreenshot("w03-look-down");
				ctx.runOnClient(mc -> { mc.player.setXRot(0F); mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); });
				ctx.waitTicks(10);
				ctx.takeScreenshot("w04-back");
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
				// идёт назад (лицом к камере) — концы прижимаются; резкая остановка — летят вперёд
				ctx.getInput().holdKey(o -> o.keyDown);
				ctx.waitTicks(20);
				ctx.takeScreenshot("w05-walking");
				ctx.getInput().releaseKey(o -> o.keyDown);
				ctx.waitTicks(3);
				ctx.takeScreenshot("w06-stopped");
				ctx.waitTicks(20);
				ctx.getInput().holdKey(o -> o.keyShift);
				ctx.waitTicks(10);
				ctx.takeScreenshot("w07-crouch");
				ctx.getInput().releaseKey(o -> o.keyShift);
				ctx.waitTicks(10);
				ctx.getInput().pressKey(o -> o.keyJump);
				ctx.waitTicks(9);
				ctx.takeScreenshot("w08-jump-fall");
				ctx.waitTicks(20);
				ctx.getInput().holdKey(o -> o.keyLeft);
				ctx.waitTicks(10);
				ctx.takeScreenshot("w09-strafe");
				ctx.getInput().releaseKey(o -> o.keyLeft);
				ctx.waitTicks(20);
				// варежки на тонких руках
				Identifier skinId = Identifier.fromNamespaceAndPath("phase-test", "skin");
				SkinData.setTest(new PlayerSkin(new ClientAsset.ResourceTexture(skinId, skinId), null, null, PlayerModelType.SLIM, true));
				ctx.waitTicks(5);
				ctx.takeScreenshot("w10-slim-mittens");
				SkinData.setTest(new PlayerSkin(new ClientAsset.ResourceTexture(skinId, skinId), null, null, PlayerModelType.WIDE, true));
				// ── 4.3: слизень ──
				CosmeticsData.setTestWear(null, "slime", null, null, null, "scarf_red", null);
				ctx.waitTicks(60);
				ctx.takeScreenshot("s01-slime-follow");
				String[] faces = { "normal", "blink", "happy", "sad", "wow", "sleep" };
				for (int f = 0; f < faces.length; f++) {
					int ff = f;
					ctx.runOnClient(mc -> { var s = PetManager.INSTANCE.ownSlime(); if (s != null) s.forceFace = ff; });
					ctx.waitTicks(3);
					ctx.takeScreenshot("s02-face-" + faces[f]);
				}
				ctx.runOnClient(mc -> { var s = PetManager.INSTANCE.ownSlime(); if (s != null) s.forceFace = -1; });
				for (String sk : new String[] { "sakura", "frost", "lava", "honey", "void", "prism" }) {
					CosmeticsData.setTestWear(null, "slime", null, sk, null, null, null);
					ctx.waitTicks(8);
					ctx.takeScreenshot("s03-skin-" + sk);
				}
				CosmeticsData.setTestWear(null, "slime", null, null, null, null, null);
				Object[][] tricks = { { "petted", Pet.Act.PETTED, 14 }, { "flip", Pet.Act.LOOP, 10 }, { "wow", Pet.Act.ROAR, 12 },
					{ "dance", Pet.Act.SPIN, 20 }, { "roll", Pet.Act.ROLL, 9 }, { "sulk", Pet.Act.SULK, 25 } };
				for (Object[] t : tricks) {
					ctx.waitTicks(40);
					ctx.runOnClient(mc -> PetManager.INSTANCE.command((Pet.Act) t[1]));
					ctx.waitTicks((Integer) t[2]);
					ctx.takeScreenshot("s04-trick-" + t[0]);
				}
				ctx.waitTicks(60);
				Config.get().mode = "shoulder";
				ctx.waitTicks(40);
				ctx.takeScreenshot("s05-shoulder-front");
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				ctx.waitTicks(5);
				ctx.takeScreenshot("s06-shoulder-back");
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
				Config.get().mode = "follow";
				ctx.waitTicks(30);
				ctx.getInput().holdKey(o -> o.keyDown);
				ctx.waitTicks(25);
				ctx.takeScreenshot("s07-hopping-after");
				ctx.getInput().releaseKey(o -> o.keyDown);
				ctx.waitTicks(30);
				CosmeticsData.setTestWear(null, "slime", null, "sakura", null, "scarf_red", "mittens_red");
				for (int t = 0; t < 3; t += 2) {
					int tt = t;
					ctx.runOnClient(mc -> PhaseMenuScreen.testTab(tt));
					ctx.setScreen(PhaseMenuScreen::new);
					ctx.waitTicks(10);
					ctx.takeScreenshot("s08-menu-" + t);
				}
				ctx.setScreen(() -> null);
				ctx.waitTicks(5);
				CosmeticsData.setTestWear(null, null, null, null, null, null, null);
				ctx.waitTicks(5);
			CosmeticsData.setTest("kabuto", "ender_dragon", "aura_sakura");
			ctx.waitTicks(40);
			ctx.takeScreenshot("01-skin-hat-pet-fly");
			CosmeticsData.setTest("kabuto", "ender_dragon", "aura_ender");
			ctx.waitTicks(40);
			ctx.takeScreenshot("01b-aura-ender");
			CosmeticsData.setTest("kabuto", "ender_dragon", "aura_oni");
			ctx.waitTicks(40);
			ctx.takeScreenshot("01c-aura-oni");
			CosmeticsData.setTest("hat_kitsune_gold", "ender_dragon", "aura_limbo");
			ctx.waitTicks(40);
			ctx.takeScreenshot("01d-exclusive-pass");
			for (String hat : new String[] { "hat_kabuto_shogun", "hat_oni_void", "hat_straw_ash" }) {
				CosmeticsData.setTestFull(hat, null, null, null, null);
				ctx.waitTicks(10);
				ctx.takeScreenshot("01d-" + hat);
			}
			// раскраски дракончика
			CosmeticsData.setTestFull("kabuto", "ender_dragon", null, "gold", null);
			ctx.waitTicks(30);
			ctx.takeScreenshot("01e-dragon-gold");
			CosmeticsData.setTestFull("kabuto", "ender_dragon", null, "void", null);
			ctx.waitTicks(10);
			ctx.takeScreenshot("01f-dragon-void");
			CosmeticsData.setTestFull("kabuto", "ender_dragon", null, "crimson", null);
			ctx.waitTicks(10);
			ctx.takeScreenshot("01g-dragon-crimson");
			// ауры-паки (аура + след): спереди стоя, затем в движении сзади
			for (String au : new String[] { "aura_eclipse", "aura_foxfire", "aura_limbo", "aura_ash" }) {
				CosmeticsData.setTestFull(null, null, au, null, null);
				ctx.waitTicks(30);
				ctx.takeScreenshot("01h-" + au + "-front");
			}
			// затмение: объёмный нимб — сбоку и сверху-сзади
			CosmeticsData.setTestFull(null, null, "aura_eclipse", null, null);
			ctx.runOnClient(mc -> mc.player.setYRot(70F));
			ctx.waitTicks(20);
			ctx.takeScreenshot("01h2-eclipse-side");
			ctx.runOnClient(mc -> { mc.player.setYRot(0F); mc.player.setXRot(35F); mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); });
			ctx.waitTicks(20);
			ctx.takeScreenshot("01h3-eclipse-above");
			ctx.runOnClient(mc -> { mc.player.setXRot(0F); mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); });
			// паки 4.0: смерть, убийство и приземление — каждый пак по очереди (рядом с игроком, лицом к камере)
			CosmeticsData.setTestFull(null, null, null, null, null);
			for (String au : new String[] { "aura_sakura", "aura_ender", "aura_oni", "aura_foxfire", "aura_ash", "aura_limbo", "aura_eclipse" }) {
				ctx.runOnClient(mc -> AuraFx.testDeath(mc.player.getX() + 1.4, mc.player.getY(), mc.player.getZ() + 0.6, au));
				ctx.waitTicks(14);
				ctx.takeScreenshot("01l-death-" + au);
				ctx.waitTicks(40);
				ctx.runOnClient(mc -> AuraFx.testKill(mc.player.getX() - 1.4, mc.player.getY(), mc.player.getZ() + 0.6, au));
				ctx.waitTicks(8);
				ctx.takeScreenshot("01m-kill-" + au);
				ctx.runOnClient(mc -> AuraFx.testLand(mc.player.getX(), mc.player.getY(), mc.player.getZ() + 1.0, au));
				ctx.waitTicks(5);
				ctx.takeScreenshot("01n-land-" + au);
				ctx.waitTicks(50);
			}
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			for (String au : new String[] { "aura_eclipse", "aura_foxfire", "aura_sakura" }) {
				CosmeticsData.setTestFull(null, null, au, null, null);
				ctx.getInput().holdKey(o -> o.keyUp);
				ctx.waitTicks(16);
				ctx.getInput().pressKey(o -> o.keyJump);
				ctx.waitTicks(14);
				ctx.takeScreenshot("01i-" + au + "-run");
				ctx.getInput().releaseKey(o -> o.keyUp);
				ctx.getInput().holdKey(o -> o.keyDown);
				ctx.waitTicks(30);
				ctx.getInput().releaseKey(o -> o.keyDown);
				ctx.waitTicks(10);
			}
			// от первого лица аура не должна лезть в глаза
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			CosmeticsData.setTestFull(null, null, "aura_foxfire", null, null);
			ctx.waitTicks(30);
			ctx.takeScreenshot("01j-first-person-foxfire");
			ctx.runOnClient(mc -> mc.player.setXRot(55F));
			ctx.waitTicks(10);
			ctx.takeScreenshot("01k-first-person-look-down");
			ctx.runOnClient(mc -> mc.player.setXRot(0F));
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			CosmeticsData.setTest("kabuto", "ender_dragon", "aura_sakura");
			sp.getServer().runCommand("item replace entity @p armor.head with minecraft:diamond_helmet");
			ctx.waitTicks(5);
			ctx.takeScreenshot("02-hat-over-helmet");
			CosmeticsData.setTest("straw_hat", "ender_dragon", "aura_sakura");
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
			// полноэкранное меню: все разделы; шляпа сдвинута и повёрнута — видно и в меню, и в мире
			CosmeticsData.setTestFull("kabuto", "ender_dragon", "aura_sakura", "gold", null);
			CosmeticsData.MY_ADJ.put("kabuto", new float[] { 2F, 1.5F, 0F, 10F, 30F, 0F, 1.2F });
			for (int t = 0; t < 4; t++) {
				int tt = t;
				ctx.runOnClient(mc -> PhaseMenuScreen.testTab(tt));
				ctx.setScreen(PhaseMenuScreen::new);
				ctx.waitTicks(10);
				ctx.takeScreenshot("17-menu-" + t);
			}
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			ctx.takeScreenshot("17e-hat-adjusted-world");
			CosmeticsData.MY_ADJ.clear();
			// «Ждать тут»: садится на землю и не телепортируется к хозяину
			Config.get().mode = "follow";
			ctx.waitTicks(40);
			Config.get().mode = "stay";
			ctx.waitTicks(100);
			ctx.takeScreenshot("18-stay-landed");
			sp.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~-30");
			ctx.waitTicks(40);
			sp.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~30");
			ctx.waitTicks(20);
			ctx.takeScreenshot("18b-stay-still-there");
			// блок под сидящим дракончиком сломали — падает
			int[] at = new int[3];
			ctx.runOnClient(mc -> {
				Pet p = PetManager.INSTANCE.own();
				if (p != null) { at[0] = (int) Math.floor(p.pos.x); at[1] = (int) Math.floor(p.pos.y - 0.5); at[2] = (int) Math.floor(p.pos.z); }
			});
			sp.getServer().runCommand("fill " + at[0] + " " + (at[1] - 2) + " " + at[2] + " " + at[0] + " " + at[1] + " " + at[2] + " air");
			ctx.waitTicks(4);
			ctx.takeScreenshot("18c-falling");
			ctx.waitTicks(30);
			ctx.takeScreenshot("18d-fell");
			// хозяин в воде: дракончик (даже «на плече») держится над водой
			Config.get().mode = "shoulder";
			ctx.waitTicks(40);
			sp.getServer().runCommand("execute at @p run fill ~-3 ~ ~-3 ~3 ~2 ~3 water");
			ctx.waitTicks(60);
			ctx.takeScreenshot("19-water-shoulder");
			ctx.runOnClient(mc -> {
				Pet p = PetManager.INSTANCE.own();
				System.out.println("PHASE-WATER owner=" + mc.player.position() + " underwater=" + mc.player.isUnderWater() + " pet=" + (p == null ? null : p.pos));
				mc.player.setXRot(-35F);
			});
			ctx.waitTicks(3);
			ctx.takeScreenshot("19b-water-look-up");
			ctx.runOnClient(mc -> mc.player.setXRot(0F));
			sp.getServer().runCommand("execute at @p run fill ~-3 ~ ~-3 ~3 ~2 ~3 air");
			Config.get().mode = "follow";
		}
	}
}

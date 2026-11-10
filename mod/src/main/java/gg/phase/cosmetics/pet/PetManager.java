package gg.phase.cosmetics.pet;

import gg.phase.cosmetics.Config;
import gg.phase.cosmetics.CosmeticsData;
import gg.phase.cosmetics.CosmeticsModels;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** Все питомцы в мире: свой и чужие. Тик — каждые 1/20 с, отрисовка — каждый кадр. */
public final class PetManager implements PetFx {
	public static final PetManager INSTANCE = new PetManager();
	private final Map<Integer, Pet> pets = new HashMap<>();
	private final Map<Integer, Slime> slimes = new HashMap<>();
	private final RandomSource rnd = RandomSource.create();
	private ClientLevel lastLevel;

	private PetManager() {}

	public Pet own() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : this.pets.get(mc.player.getId());
	}

	public Slime ownSlime() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : this.slimes.get(mc.player.getId());
	}

	/** Команда своему питомцу из меню (дракончику или слизню — кто сейчас рядом) */
	public void command(Pet.Act act) {
		Pet p = own();
		if (p != null) p.command(act);
		Slime s = ownSlime();
		if (s != null) s.command(act);
	}

	public void tick(Minecraft mc) {
		if (mc.level != this.lastLevel) { this.pets.clear(); this.slimes.clear(); this.lastLevel = mc.level; }
		if (mc.level == null || mc.isPaused()) return;
		Set<Integer> alive = new HashSet<>(), aliveSlimes = new HashSet<>();
		Config cfg = Config.get();
		for (Player pl : mc.level.players()) {
			boolean own = pl == mc.player;
			if (!own && !cfg.others) continue;
			CosmeticsData.Worn w = CosmeticsData.get(pl.getName().getString());
			if (w == null || w.pet() == null || pl.isSpectator() || pl.isInvisible()) continue;
			String mode = own ? cfg.mode : w.mode() == null ? "follow" : w.mode();
			String col = w.petColor();
			if ("slime".equals(w.pet())) {
				aliveSlimes.add(pl.getId());
				Slime sl = this.slimes.computeIfAbsent(pl.getId(), id -> {
					float by = pl.yBodyRot * Mth.DEG_TO_RAD;
					return new Slime(id, pl.position().add(Mth.cos(by) * 0.9, 0, Mth.sin(by) * 0.9));
				});
				sl.color = col == null || col.equals("default") ? "" : col;
				sl.tick(pl, mode, this, own);
				continue;
			}
			if (!"ender_dragon".equals(w.pet())) continue;
			alive.add(pl.getId());
			Pet pet = this.pets.computeIfAbsent(pl.getId(), id -> {
				float by = pl.yBodyRot * Mth.DEG_TO_RAD;
				return new Pet(id, pl.position().add(Mth.cos(by) * 0.9, 1.6, Mth.sin(by) * 0.9));
			});
			pet.color = col == null || col.equals("default") ? "" : col;
			pet.tick(pl, mode, this, own);
		}
		this.pets.keySet().retainAll(alive);
		this.slimes.keySet().retainAll(aliveSlimes);
	}

	public void render(PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || this.pets.isEmpty() && this.slimes.isEmpty() || !CosmeticsModels.ready()) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		boolean firstPerson = mc.options.getCameraType() == CameraType.FIRST_PERSON;
		for (Pet pet : this.pets.values()) {
			if (firstPerson && mc.player != null && pet.ownerId == mc.player.getId() && pet.act == Pet.Act.PERCH && pet.p[Pet.PERCH] > 0.5F) continue;
			Vec3 at = pet.pos;
			if (at.distanceToSqr(cam) > 96 * 96) continue;
			int light = LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(at.add(0, 0.3, 0)));
			PetRenderer.render(pet, pt, cam, pose, out, light);
		}
		for (Slime sl : this.slimes.values()) {
			// свой слизень на плече в виде от первого лица не загораживает обзор
			if (firstPerson && mc.player != null && sl.ownerId == mc.player.getId() && sl.perched) continue;
			Vec3 at = sl.pos;
			if (at.distanceToSqr(cam) > 96 * 96) continue;
			int light = LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(at.add(0, 0.2, 0)));
			SlimeRenderer.render(sl, pt, cam, pose, out, light);
		}
	}

	// ───────── Звуки и частицы ─────────
	private Vec3 headOf(Pet pet) {
		float y = pet.yaw * Mth.DEG_TO_RAD;
		return pet.pos.add(-Mth.sin(y) * 0.28, 0.32, Mth.cos(y) * 0.28);
	}

	private void sound(Pet pet, SoundEvent ev, float vol, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		int pv = Config.get().petVolume;
		if (mc.level == null || !Config.get().sounds || pv <= 0) return;
		vol *= Math.min(100, pv) / 100F;
		if (pet.pos.distanceToSqr(mc.gameRenderer.mainCamera().position()) > 20 * 20) return;
		mc.level.playLocalSound(pet.pos.x, pet.pos.y, pet.pos.z, ev, SoundSource.NEUTRAL, vol, pitch + (this.rnd.nextFloat() - 0.5F) * 0.12F, false);
	}

	@Override public void flap(Pet pet) { sound(pet, SoundEvents.ENDER_DRAGON_FLAP, 0.09F, 2.0F); }
	@Override public void land(Pet pet) { sound(pet, SoundEvents.ARMADILLO_LAND, 0.35F, 1.7F); }
	@Override public void takeoff(Pet pet) { sound(pet, SoundEvents.BAT_TAKEOFF, 0.12F, 1.4F); }

	@Override
	public void chirp(Pet pet) {
		switch (this.rnd.nextInt(3)) {
			case 0 -> sound(pet, SoundEvents.ENDER_DRAGON_AMBIENT, 0.1F, 2.0F);
			case 1 -> sound(pet, SoundEvents.PARROT_IMITATE_ENDER_DRAGON, 0.3F, 1.5F);
			default -> sound(pet, SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 0.25F, 1.25F);
		}
	}

	@Override
	public void purr(Pet pet) {
		sound(pet, SoundEvents.CAT_PURR_BABY.value(), 0.7F, 1.15F);
		sound(pet, SoundEvents.ALLAY_ITEM_GIVEN, 0.25F, 1.3F);
	}

	@Override
	public void hearts(Pet pet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 h = headOf(pet);
		mc.level.addParticle(ParticleTypes.HEART, h.x + (this.rnd.nextDouble() - 0.5) * 0.3, h.y + 0.2, h.z + (this.rnd.nextDouble() - 0.5) * 0.3, 0, 0.02, 0);
	}

	@Override
	public void sparkle(Pet pet, Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		for (int i = 0; i < 3; i++) {
			double a = this.rnd.nextDouble() * Mth.TWO_PI, r = 0.25 + this.rnd.nextDouble() * 0.2;
			mc.level.addParticle(i == 0 ? ParticleTypes.END_ROD : ParticleTypes.WAX_ON, at.x + Math.cos(a) * r, at.y + 0.15 + this.rnd.nextDouble() * 0.4, at.z + Math.sin(a) * r, 0, 0.015, 0);
		}
	}

	@Override
	public void roar(Pet pet) {
		sound(pet, SoundEvents.ENDER_DRAGON_GROWL, 0.22F, 1.9F);
	}

	@Override
	public void breath(Pet pet, Vec3 dir) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 h = headOf(pet).add(dir.scale(0.15));
		for (int i = 0; i < 3; i++) {
			Vec3 v = dir.scale(0.12 + this.rnd.nextDouble() * 0.08).add((this.rnd.nextDouble() - 0.5) * 0.03, (this.rnd.nextDouble() - 0.5) * 0.03, (this.rnd.nextDouble() - 0.5) * 0.03);
			mc.level.addParticle(i == 0 ? ParticleTypes.WITCH : ParticleTypes.REVERSE_PORTAL, h.x, h.y, h.z, v.x, v.y, v.z);
		}
	}

	@Override
	public void snore(Pet pet) {
		sound(pet, SoundEvents.FOX_SLEEP, 0.25F, 1.35F);
	}

	@Override
	public void teleport(Pet pet, Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		for (int i = 0; i < 12; i++) {
			mc.level.addParticle(ParticleTypes.PORTAL, at.x + (this.rnd.nextDouble() - 0.5) * 0.6, at.y + this.rnd.nextDouble() * 0.6, at.z + (this.rnd.nextDouble() - 0.5) * 0.6,
				(this.rnd.nextDouble() - 0.5) * 0.4, -this.rnd.nextDouble() * 0.2, (this.rnd.nextDouble() - 0.5) * 0.4);
		}
		sound(pet, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 1.6F);
	}

	// ───────── Слизень: звуки и частицы ─────────
	private void sound(Vec3 at, SoundEvent ev, float vol, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		int pv = Config.get().petVolume;
		if (mc.level == null || !Config.get().sounds || pv <= 0) return;
		vol *= Math.min(100, pv) / 100F;
		if (at.distanceToSqr(mc.gameRenderer.mainCamera().position()) > 20 * 20) return;
		mc.level.playLocalSound(at.x, at.y, at.z, ev, SoundSource.NEUTRAL, vol, pitch + (this.rnd.nextFloat() - 0.5F) * 0.12F, false);
	}

	private Vec3 top(Slime s) { return s.pos.add(0, 0.4, 0); }

	void slimeHop(Slime s) { sound(s.pos, SoundEvents.SLIME_JUMP_SMALL, 0.22F, 1.45F); }

	void slimeLand(Slime s, boolean big) {
		sound(s.pos, SoundEvents.SLIME_SQUISH_SMALL, big ? 0.3F : 0.18F, big ? 1.25F : 1.5F);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !big) return;
		boolean lime = s.color.isEmpty() || !SlimeRenderer.SKINS.contains(s.color);
		for (int i = 0; i < 5; i++) {
			double a = this.rnd.nextDouble() * Mth.TWO_PI;
			mc.level.addParticle(lime ? ParticleTypes.ITEM_SLIME : ParticleTypes.POOF, s.pos.x + Math.cos(a) * 0.22, s.pos.y + 0.05, s.pos.z + Math.sin(a) * 0.22,
				Math.cos(a) * 0.04, lime ? 0.05 : 0.01, Math.sin(a) * 0.04);
		}
	}

	void slimeChirp(Slime s) { sound(s.pos, SoundEvents.AXOLOTL_IDLE_AIR, 0.28F, 1.7F); }

	void slimeWow(Slime s) {
		sound(s.pos, SoundEvents.PUFFER_FISH_BLOW_UP, 0.35F, 1.8F);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 t = top(s);
		mc.level.addParticle(ParticleTypes.END_ROD, t.x, t.y + 0.2, t.z, 0, 0.03, 0);
	}

	void slimeSad(Slime s) {
		sound(s.pos, SoundEvents.SLIME_HURT_SMALL, 0.16F, 0.75F);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 t = top(s);
		mc.level.addParticle(ParticleTypes.FALLING_WATER, t.x - 0.08, t.y - 0.05, t.z, 0, 0, 0); // слезинка
	}

	void slimePurr(Slime s) {
		sound(s.pos, SoundEvents.CAT_PURR_BABY.value(), 0.6F, 1.35F);
		sound(s.pos, SoundEvents.SLIME_SQUISH_SMALL, 0.2F, 1.8F);
	}

	void slimeHearts(Slime s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 t = top(s);
		mc.level.addParticle(ParticleTypes.HEART, t.x + (this.rnd.nextDouble() - 0.5) * 0.3, t.y + 0.15, t.z + (this.rnd.nextDouble() - 0.5) * 0.3, 0, 0.02, 0);
	}

	void slimeNote(Slime s) {
		sound(s.pos, SoundEvents.AMETHYST_BLOCK_CHIME, 0.35F, 0.9F + this.rnd.nextFloat() * 0.8F);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 t = top(s);
		mc.level.addParticle(ParticleTypes.NOTE, t.x, t.y + 0.25, t.z, this.rnd.nextDouble(), 0, 0);
	}

	void slimePoof(Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		for (int i = 0; i < 8; i++) {
			mc.level.addParticle(ParticleTypes.POOF, at.x + (this.rnd.nextDouble() - 0.5) * 0.5, at.y + this.rnd.nextDouble() * 0.4, at.z + (this.rnd.nextDouble() - 0.5) * 0.5, 0, 0.02, 0);
		}
		sound(at, SoundEvents.SLIME_JUMP_SMALL, 0.3F, 1.9F);
	}
}

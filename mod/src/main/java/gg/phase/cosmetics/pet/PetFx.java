package gg.phase.cosmetics.pet;

import net.minecraft.world.phys.Vec3;

/** Звуки и частицы питомца */
public interface PetFx {
	void flap(Pet pet);
	void land(Pet pet);
	void takeoff(Pet pet);
	void chirp(Pet pet);
	void purr(Pet pet);
	void hearts(Pet pet);
	void roar(Pet pet);
	void breath(Pet pet, Vec3 dir);
	void snore(Pet pet);
	void teleport(Pet pet, Vec3 at);
	/** Подсветить найденную вещь: искры вокруг предмета на земле */
	void sparkle(Pet pet, Vec3 at);
}

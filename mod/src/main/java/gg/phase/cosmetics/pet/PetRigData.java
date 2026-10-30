package gg.phase.cosmetics.pet;

/** Скелет дракончика (сгенерирован cosmetics/build.py из models.RIG — руками не править). */
public final class PetRigData {
	public record Part(String name, int parent, float px, float py, float pz, String model) {}

	public static final Part[] PARTS = {
		new Part("body", -1, 0F, 0F, 0F, "pet_body"),
		new Part("neck", 0, 0F, 1.2F, -4.4F, "pet_neck"),
		new Part("head", 1, 0F, 1.0F, -2.6F, "pet_head"),
		new Part("jaw", 2, 0F, -1.6F, -4.6F, "pet_jaw"),
		new Part("wing_l", 0, -3.0F, 2.2F, -1.6F, "pet_wing_l"),
		new Part("wing_tip_l", 4, -6.2F, 0F, 0F, "pet_wing_tip_l"),
		new Part("wing_r", 0, 3.0F, 2.2F, -1.6F, "pet_wing_r"),
		new Part("wing_tip_r", 6, 6.2F, 0F, 0F, "pet_wing_tip_r"),
		new Part("leg_fl", 0, -2.2F, -1.8F, -2.8F, "pet_leg_f"),
		new Part("leg_fr", 0, 2.2F, -1.8F, -2.8F, "pet_leg_f"),
		new Part("leg_bl", 0, -2.5F, -1.6F, 2.6F, "pet_leg_b"),
		new Part("leg_br", 0, 2.5F, -1.6F, 2.6F, "pet_leg_b"),
		new Part("tail1", 0, 0F, 0.2F, 4.6F, "pet_tail1"),
		new Part("tail2", 12, 0F, 0F, 3.6F, "pet_tail2"),
		new Part("tail3", 13, 0F, 0F, 3.4F, "pet_tail3"),
		new Part("tail4", 14, 0F, 0F, 3.2F, "pet_tail4")
	};

	private PetRigData() {}
}

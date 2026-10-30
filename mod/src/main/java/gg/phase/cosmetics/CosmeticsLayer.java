package gg.phase.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Слой поверх игрока. Модели — обычные item-модели из ресурсов мода (assets/phase/models/item/cosm),
 * те же, что в превью лаунчера. Шляпа рисуется в пространстве головы и поворачивается вместе с ней,
 * дракончик — в пространстве тела: летает у левого плеча, а когда игрок стоит — садится и ложится рядом.
 */
public class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Map<String, ItemStackRenderState> CACHE = new HashMap<>();
	private static final float PET_SCALE = 0.42F / 0.9375F; // как в датапаке, но в пространстве модели игрока
	private final ItemModelResolver resolver;

	public CosmeticsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, ItemModelResolver resolver) {
		super(parent);
		this.resolver = resolver;
	}

	static void invalidate() { CACHE.clear(); }

	private ItemStackRenderState model(String name) {
		return CACHE.computeIfAbsent(name, n -> {
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("phase", "cosm/" + n));
			ItemStackRenderState st = new ItemStackRenderState();
			this.resolver.updateForTopItem(st, stack, ItemDisplayContext.NONE, null, null, 0);
			return st;
		});
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible || state.isSpectator) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Entity e = mc.level.getEntity(state.id);
		if (!(e instanceof Player player)) return;
		CosmeticsData.Worn worn = CosmeticsData.get(player.getName().getString());
		if (worn == null) return;
		if (worn.hat() != null) submitHat(pose, out, light, state, worn.hat());
		if ("ender_dragon".equals(worn.pet()) && !player.isSleeping()) submitDragon(pose, out, light, state, player);
	}

	/** Модель шляпы: голова занимает x 4..12, y 0..8, z 4..12 (1 единица = 1 пиксель головы), лицо — на север */
	private void submitHat(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, String hat) {
		pose.pushPose();
		this.getParentModel().root().translateAndRotate(pose);
		this.getParentModel().translateToHead(pose);
		// пространство головы: ось y вниз, лицо к −z. Поворот на 180° вокруг z переводит модель «вверх ногами» в «нормально»
		pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
		pose.translate(0.0F, 0.5F, 0.0F);
		this.model(hat).submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
	}

	private void submitDragon(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, Player player) {
		int st = PetMotion.state(player);
		float t = state.ageInTicks;
		float s = PET_SCALE;
		pose.pushPose();
		this.getParentModel().root().translateAndRotate(pose);
		if (st == 0) {
			// у левого плеча, чуть сзади и выше; покачивается
			float bob = (float) Math.sin(t * 0.15F) * 0.06F;
			pose.translate(0.62F, -0.28F + bob, 0.28F);
		} else {
			// на земле слева, чуть повернувшись к игроку
			float ground = 1.5F - (state.isCrouching ? 0.133F : 0.0F);
			pose.translate(0.85F, ground, -0.05F);
			pose.mulPose(Axis.YP.rotationDegrees(-30.0F));
		}
		pose.mulPose(Axis.ZP.rotationDegrees(180.0F)); // дальше ось y вверх, как в моделях
		if (st == 0) {
			this.part(pose, out, light, state, "dragon_fly", s, 0, 0, 0, 0);
			float flap = (float) Math.sin(t * 0.55F) * 34.0F + 8.0F;
			// шарниры крыльев — плечи дракончика (точки модели 4.8/11.2, 10.6, 8.6 → центр модели)
			this.part(pose, out, light, state, "dragon_wing_l", s, -0.2F * s, 0.1625F * s, 0.0375F * s, -flap);
			this.part(pose, out, light, state, "dragon_wing_r", s, 0.2F * s, 0.1625F * s, 0.0375F * s, flap);
		} else {
			this.part(pose, out, light, state, st == 1 ? "dragon_sit" : "dragon_lie", s, 0, 0.5F * s, 0, 0);
		}
		pose.popPose();
	}

	private void part(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, String name, float s, float x, float y, float z, float rollDeg) {
		pose.pushPose();
		pose.translate(x, y, z);
		if (rollDeg != 0) pose.mulPose(Axis.ZP.rotationDegrees(rollDeg));
		pose.scale(s, s, s);
		this.model(name).submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
	}
}

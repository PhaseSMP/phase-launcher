package gg.phase.world.mixin;

import gg.phase.world.compat.TrimBan;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Villagers and wandering traders never sell the admin-only armor trim templates. */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
	@Inject(method = "addOffersFromTradeSet", at = @At("TAIL"))
	private void phaseworld$dropBannedOffers(ServerLevel level, MerchantOffers offers, ResourceKey<TradeSet> key, CallbackInfo ci) {
		offers.removeIf(offer -> TrimBan.banned(offer.getResult()) || TrimBan.banned(offer.getCostA()) || TrimBan.banned(offer.getCostB()));
	}
}

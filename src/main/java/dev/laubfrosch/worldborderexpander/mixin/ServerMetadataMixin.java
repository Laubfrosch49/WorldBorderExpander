package dev.laubfrosch.worldborderexpander.mixin;

import dev.laubfrosch.worldborderexpander.WorldBorderExpander;
import net.minecraft.server.ServerMetadata;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerMetadata.class)
public abstract class ServerMetadataMixin {

	@Inject(method = "description", at = @At("RETURN"), cancellable = true)
	private void addWorldBorderToMotd(CallbackInfoReturnable<Text> cir) {
		Text originalDescription = cir.getReturnValue();
		double borderSize = WorldBorderExpander.getCurrentBorderSize();

		int amount = WorldBorderExpander.getExpansionAmount();
		String amountPrefix = (amount >= 0) ? "+" : "";

		String expansionInfo = WorldBorderExpander.isAutoExpansionEnabled() ?
				"§7 und täglich §6" + amountPrefix + amount + "§7 um §c" + String.format("%02d:%02d", WorldBorderExpander.getTargetHour(), WorldBorderExpander.getTargetMinute()) + " Uhr" :
				"§7 und die Größe bleibt.";

		String modifiedMotd = originalDescription.getString() +
				"\n§7Auf §a" + (int)borderSize + "² Blöcken" + expansionInfo;

		cir.setReturnValue(Text.literal(modifiedMotd));
	}
}
package nl.theepicblock.immersive_cursedness.mixin.interdimensionalpackets;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import nl.theepicblock.immersive_cursedness.PlayerInterface;
import nl.theepicblock.immersive_cursedness.PlayerManager;
import nl.theepicblock.immersive_cursedness.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerInteractionManager.class)
public class MixinInteractionManager {
	@Shadow public ServerPlayerEntity player;

	@Shadow public ServerWorld world;

	@Inject(method = "processBlockBreakingAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;canPlayerModifyAt(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/math/BlockPos;)Z"))
	public void breakInject(BlockPos pos, PlayerActionC2SPacket.Action action, Direction direction, int worldHeight, int sequence, CallbackInfo ci) {
		if (PlayerInterface.isCloseToPortal(player)) {
			PlayerManager manager = Util.getManagerFromPlayer(player);
			if (manager == null) return;
			BlockPos z = manager.transform(pos);
			if (z == null) {
				this.world = (ServerWorld)player.getWorld();
			} else {
				((BlockPos.Mutable)pos).set(z.getX(), z.getY(), z.getZ());
				this.world = Util.getDestination(player);
			}
		} else {
			this.world = (ServerWorld)player.getWorld();
		}
	}
}

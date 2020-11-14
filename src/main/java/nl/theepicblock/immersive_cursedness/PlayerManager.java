package nl.theepicblock.immersive_cursedness;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import nl.theepicblock.immersive_cursedness.mixin.EntityPositionS2CPacketAccessor;
import nl.theepicblock.immersive_cursedness.objects.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PlayerManager {
    private final Config config;
    private final ServerPlayerEntity player;
    private final PortalManager portalManager;
    private BlockCache blockCache = new BlockCache();
    private final List<UUID> hiddenEntities = new ArrayList<>();
    private ServerWorld previousWorld;

    public PlayerManager(ServerPlayerEntity player, Config config) {
        this.player = player;
        portalManager = new PortalManager(player, config);
        this.config = config;
    }

    @SuppressWarnings("ConstantConditions")
    public void tick(int tickCount) {
        if (tickCount % 30 == 0) {
            portalManager.update();
        }
        ServerWorld serverWorld = ((PlayerInterface)player).getUnfakedWorld();
        ServerWorld destination = Util.getDestination(serverWorld);

        if (serverWorld != previousWorld) {
            blockCache = new BlockCache();
        }

        List<FlatStandingRectangle> sentLayers = new ArrayList<>(blockCache.size());
        Chunk2IntMap sentBlocks = new Chunk2IntMap();

        List<Entity> entities = this.getEntitiesInRange();
        if (tickCount % 200 == 0) removeNoLongerExistingEntities(entities);

        BlockState atmosphereBlock = (serverWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_CONCRETE : Blocks.NETHER_WART_BLOCK).getDefaultState();
        BlockState atmosphereBetweenBlock = (serverWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_STAINED_GLASS : Blocks.RED_STAINED_GLASS).getDefaultState();

        if (player.hasNetherPortalCooldown())return;

        ((PlayerInterface)player).setCloseToPortal(false);
        //iterate through all portals
        portalManager.getPortals().forEach(portal -> {
            if (portal.isCloserThan(player.getPos(),6)) {
                ((PlayerInterface)player).setCloseToPortal(true);
            }
            TransformProfile transformProfile = portal.getTransformProfile();
            if (transformProfile == null) return;

            if (tickCount % 40 == 0) {
                //replace the portal blocks in the center of the portal with air
                BlockPos.iterate(portal.getLowerLeft(), portal.getUpperRight()).forEach(pos -> {
                    player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos.toImmutable(), Blocks.AIR.getDefaultState()));
                });
            }

            //iterate through all layers behind the portal
            FlatStandingRectangle rect = portal.toFlatStandingRectangle();
            for (int i = 1; i < config.portalDepth; i++) {
                FlatStandingRectangle rect2 = rect.expand(i, player.getCameraPosVec(1));
                sentLayers.add(rect2);

                entities.removeIf((entity) -> {
                    if (rect2.contains(entity.getPos())) {
                        for (UUID uuid : hiddenEntities) {
                            if (entity.getUuid().equals(uuid)) {
                                return true; //cancel if the uuid is already in hiddenEntities
                            }
                        }
                        //If we've reached this point. The entity isn't hidden yet. So we should hide it
                        EntityPositionS2CPacket packet = new EntityPositionS2CPacket(entity);
                        ((EntityPositionS2CPacketAccessor)packet).setX(entity.getX()+50);
                        ((EntityPositionS2CPacketAccessor)packet).setY(Double.MAX_VALUE);
                        player.networkHandler.sendPacket(packet);
                        hiddenEntities.add(entity.getUuid());
                        return true;
                    }
                    return false;
                });

                //go through all blocks in this layer and use the transformProfile to get the correct block in the nether. Then send it to the client
                rect2.iterateClamped(player.getPos(), config.horizontalSendLimit, (pos) -> {
                    double dist = Util.getDistance(pos, portal.getLowerLeft());
                    if (dist > config.squaredAtmosphereRadiusPlusOne) return;

                    BlockState ret;

                    if (dist > config.squaredAtmosphereRadius) {
                        ret = atmosphereBlock;
                    } else if (dist > config.squaredAtmosphereRadiusMinusOne) {
                        ret = atmosphereBetweenBlock;
                    } else {
                        ret = transformProfile.transformAndGetFromWorld(pos, destination);
                    }

                    if (pos.getY() == 1) ret = atmosphereBetweenBlock;
                    if (pos.getY() == 0) ret = atmosphereBlock;

                    BlockPos imPos = pos.toImmutable();
                    sentBlocks.increment(imPos);
                    if (!(blockCache.get(imPos) == ret)) {
                        blockCache.put(imPos, ret);
                        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(imPos, ret));
                    }
                });
            }
        });

        //get all of the old blocks and remove them
        blockCache.purge(sentBlocks, sentLayers, (pos) -> {
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, Util.getBlockAsync(serverWorld, pos)));
        });

        entities.forEach(entity -> {
            for (UUID uuid : hiddenEntities) {
                if (entity.getUuid().equals(uuid)) {
                    hiddenEntities.remove(uuid);
                    player.networkHandler.sendPacket(new EntityPositionS2CPacket(entity));
                    return;
                }
            }
        });
        previousWorld = serverWorld;
    }

    public BlockPos transform(BlockPos p) {
        for (Portal portal : portalManager.getPortals()) {
            if (portal.isBlockposBehind(p, player.getPos())) {
                return portal.getTransformProfile().transform(p);
            }
        }
        return null;
    }

    private List<Entity> getEntitiesInRange() {
        ServerWorld world = ((PlayerInterface)player).getUnfakedWorld();
        return ChunkPos.stream(new ChunkPos(player.getBlockPos()), config.renderDistance).flatMap((chunkPos) -> {
            Optional<Chunk> chunkOptional = Util.getChunkAsync(world, chunkPos.x,chunkPos.z);
            if (chunkOptional.isPresent()) {
                Chunk chunk = chunkOptional.get();
                if (chunk instanceof WorldChunk) {
                    WorldChunk worldChunk = (WorldChunk)chunk;
                    return Arrays.stream(worldChunk.getEntitySectionArray()).flatMap((Function<TypeFilterableList<Entity>,Stream<Entity>>)Collection::stream);
                }
            }
            return Stream.empty();
        }).collect(Collectors.toList());
    }

    private void removeNoLongerExistingEntities(List<Entity> existingEntities) {
        hiddenEntities.removeIf((uuid) ->
                existingEntities.stream().noneMatch(entity -> uuid.equals(entity.getUuid())));
    }
}

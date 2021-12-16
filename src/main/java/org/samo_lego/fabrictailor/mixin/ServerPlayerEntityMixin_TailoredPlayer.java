package org.samo_lego.fabrictailor.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.InsecureTextureException;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.biome.BiomeManager;
import org.samo_lego.fabrictailor.casts.TailoredPlayer;
import org.samo_lego.fabrictailor.mixin.accessors.ChunkMapAccessor;
import org.samo_lego.fabrictailor.mixin.accessors.TrackedEntityAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.samo_lego.fabrictailor.FabricTailor.config;
import static org.samo_lego.fabrictailor.FabricTailor.errorLog;
import static org.samo_lego.fabrictailor.mixin.accessors.PlayerEntityAccessor.getPLAYER_MODEL_PARTS;

@Mixin(ServerPlayer.class)
public class ServerPlayerEntityMixin_TailoredPlayer implements TailoredPlayer  {

    private final ServerPlayer player = (ServerPlayer) (Object) this;
    private final GameProfile gameProfile = player.getGameProfile();

    private String skinValue;
    private String skinSignature;
    private final PropertyMap map = this.gameProfile.getProperties();
    private long lastSkinChangeTime = 0;


    /**
     * <p>
     * This method has been adapted from the Impersonate mod's <a href="https://github.com/Ladysnake/Impersonate/blob/1.16/src/main/java/io/github/ladysnake/impersonate/impl/ServerPlayerSkins.java">source code</a>
     * under GNU Lesser General Public License.
     *
     * Reloads player's skin for all the players (including the one that has changed the skin)
     *
     * @author Pyrofab
     */
    @Override
    public void reloadSkin() {
        // Refreshing tablist for each player
        if(player.getServer() == null)
            return;
        PlayerList playerManager = player.getServer().getPlayerList();
        playerManager.broadcastAll(new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER, player));
        playerManager.broadcastAll(new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.ADD_PLAYER, player));

        ServerChunkCache manager = player.getLevel().getChunkSource();
        ChunkMap storage = manager.chunkMap;
        TrackedEntityAccessor trackerEntry = ((ChunkMapAccessor) storage).getEntityTrackers().get(player.getId());

        trackerEntry.getSeenBy().forEach(tracking -> trackerEntry.getServerEntity().addPairing(tracking.getPlayer()));

        // need to change the player entity on the client
        ServerLevel targetWorld = player.getLevel();
        player.connection.send(new ClientboundRespawnPacket(targetWorld.dimensionType(), targetWorld.dimension(), BiomeManager.obfuscateSeed(targetWorld.getSeed()), player.gameMode.getGameModeForPlayer(), player.gameMode.getPreviousGameModeForPlayer(), targetWorld.isDebug(), targetWorld.isFlat(), true));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        player.server.getPlayerList().sendPlayerPermissionLevel(player);
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        player.connection.send(new ClientboundSetHealthPacket(player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
        for (MobEffectInstance statusEffect : player.getActiveEffects()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), statusEffect));
        }
        player.onUpdateAbilities();
        playerManager.sendLevelInfo(player, targetWorld);
        playerManager.sendAllPlayerInfo(player);
    }

    /**
     * Sets the skin to the specified player and reloads it with {@link ServerPlayerEntityMixin_TailoredPlayer#reloadSkin()} reloadSkin().
     *
     * @param skinData skin texture data
     * @param reload whether to send packets around for skin reload
     */
    public void setSkin(Property skinData, boolean reload) {
        try {
            this.map.removeAll("textures");
        } catch (Exception ignored) {
            // Player has no skin data, no worries
        }

        try {
            this.map.put("textures", skinData);

            // Saving skin data
            this.skinValue = skinData.getValue();
            this.skinSignature = skinData.getSignature();

            // Reloading skin
            if(reload)
                this.reloadSkin();

            this.lastSkinChangeTime = System.currentTimeMillis();

        } catch (InsecureTextureException ignored) {
            // No skin data
        } catch (Error e) {
            // Something went wrong when trying to set the skin
            errorLog(e.getMessage());
        }
    }

    @Override
    public void setSkin(String value, String signature, boolean reload) {
        this.setSkin(new Property("textures", value, signature), reload);
    }

    @Override
    public String getSkinValue() {
        if(this.skinValue == null) {
            try {
                Property property = map.get("textures").iterator().next();
                this.skinValue = property.getValue();
            } catch (Exception ignored) {
                // Player has no skin data, no worries
            }
        }
        return this.skinValue;
    }

    @Override
    public String getSkinSignature() {
        if(this.skinSignature == null) {
            try {
                Property property = map.get("textures").iterator().next();
                this.skinSignature = property.getSignature();
            } catch (Exception ignored) {
                // Player has no skin data, no worries
            }
        }
        return this.skinSignature;
    }

    @Override
    public long getLastSkinChange() {
        return this.lastSkinChangeTime;
    }

    @Override
    public void clearSkin() {
        try {
            this.map.removeAll("textures");
            this.reloadSkin();
        } catch (Exception ignored) {
            // Player has no skin data, no worries
        }
    }

    @Override
    public void resetLastSkinChange() {
        this.lastSkinChangeTime = 0;
    }

    @Inject(method = "updateOptions", at = @At("TAIL"))
    private void disableCapeIfNeeded(ServerboundClientInformationPacket packet, CallbackInfo ci) {
        if(!config.allowCapes) {
            byte playerModel = (byte) packet.modelCustomisation();

            // Fake cape rule to be off
            playerModel = (byte) (playerModel & ~(1));
            this.player.getEntityData().set(getPLAYER_MODEL_PARTS(), playerModel);
        }
    }


    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void writeCustomDataToNbt(CompoundTag tag, CallbackInfo ci) {
        if(this.getSkinValue() != null && this.getSkinSignature() != null) {
            CompoundTag skinDataTag = new CompoundTag();
            skinDataTag.putString("value", this.getSkinValue());
            skinDataTag.putString("signature", this.getSkinSignature());

            tag.put("fabrictailor:skin_data", skinDataTag);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void readCustomDataFromNbt(CompoundTag tag, CallbackInfo ci) {
        CompoundTag skinDataTag = tag.getCompound("fabrictailor:skin_data");
        if(skinDataTag != null) {
            this.skinValue = skinDataTag.contains("value") ? skinDataTag.getString("value") : null;
            this.skinSignature = skinDataTag.contains("signature") ? skinDataTag.getString("signature") : null;

            if(this.skinValue != null && this.skinSignature != null) {
                this.setSkin(this.skinValue, this.skinSignature, false);
            }
        }
    }
}

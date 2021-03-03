package by.jackraidenph.dragonsurvival;

import by.jackraidenph.dragonsurvival.abilities.common.IDragonAbility;
import by.jackraidenph.dragonsurvival.abilities.common.utils.AbilityTree;
import by.jackraidenph.dragonsurvival.abilities.common.utils.AbilityType;
import by.jackraidenph.dragonsurvival.capability.Capabilities;
import by.jackraidenph.dragonsurvival.capability.DragonStateHandler;
import by.jackraidenph.dragonsurvival.capability.DragonStateProvider;
import by.jackraidenph.dragonsurvival.handlers.AbilityTickingHandler;
import by.jackraidenph.dragonsurvival.init.AbilityTreeInit;
import by.jackraidenph.dragonsurvival.init.EntityTypesInit;
import by.jackraidenph.dragonsurvival.network.*;
import by.jackraidenph.dragonsurvival.util.ConfigurationHandler;
import by.jackraidenph.dragonsurvival.util.DragonLevel;
import by.jackraidenph.dragonsurvival.util.DragonType;
import com.google.common.collect.ImmutableMap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.command.CommandSource;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;

import static net.minecraft.command.Commands.argument;
import static net.minecraft.command.Commands.literal;

@Mod(DragonSurvivalMod.MODID)
public class DragonSurvivalMod {
    public static final String MODID = "dragonsurvival";
    public static final Logger LOGGER = LogManager.getLogger();
    public static final HashMap<String, AbilityType<? extends IDragonAbility>> ABILITY_TYPES = new HashMap<>();
    public static final ImmutableMap<DragonType, AbilityTree> ABILITY_TREE_MAP = ImmutableMap.of(
            DragonType.CAVE, AbilityTreeInit.CAVE_DRAGON_TREE,
            DragonType.SEA, AbilityTreeInit.SEA_DRAGON_TREE,
            DragonType.FOREST, AbilityTreeInit.FOREST_DRAGON_TREE
    );
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"), () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
    private static int nextPacketId = 0;
    private static AbilityTickingHandler HANDLER;

    public DragonSurvivalMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::setup);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStart);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ConfigurationHandler.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(getTickHandler());

        //IMPORTANT TO ENSURE INITIALIZATION ORDER
        AbilityType.init();
        AbilityTreeInit.init();
    }

    public static AbilityTickingHandler getTickHandler() {
        if (HANDLER == null) {
            HANDLER = new AbilityTickingHandler();
            return HANDLER;
        }
        return HANDLER;
    }

    private static <T> void register(Class<T> clazz, IMessage<T> message) {
        CHANNEL.registerMessage(nextPacketId++, clazz, message::encode, message::decode, message::handle);
    }

    private void setup(final FMLCommonSetupEvent event) {
        Capabilities.register();
        LOGGER.info("Successfully registered " + DragonStateHandler.class.getSimpleName() + "!");
        register(PacketSyncCapabilityMovement.class, new PacketSyncCapabilityMovement());
        register(PacketSyncXPDevour.class, new PacketSyncXPDevour());
        register(PacketSyncPredatorStats.class, new PacketSyncPredatorStats());
        register(SetRespawnPosition.class, new SetRespawnPosition());
        register(ResetPlayer.class, new ResetPlayer());
        register(SynchronizeNest.class, new SynchronizeNest());
        register(OpenDragonInventory.class, new OpenDragonInventory());
        register(SyncLevel.class, new SyncLevel());
        register(ActivateAbilityInSlot.class, new ActivateAbilityInSlot());
        register(SynchronizeDragonAbilities.class, new SynchronizeDragonAbilities());
        register(ToggleWings.class, new ToggleWings());

        //TODO synchronize health
        CHANNEL.registerMessage(nextPacketId, SynchronizeDragonCap.class, (synchronizeDragonCap, packetBuffer) -> {
            packetBuffer.writeInt(synchronizeDragonCap.playerId);
            packetBuffer.writeByte(synchronizeDragonCap.dragonLevel.ordinal());
            packetBuffer.writeByte(synchronizeDragonCap.dragonType.ordinal());
            packetBuffer.writeBoolean(synchronizeDragonCap.hiding);
            packetBuffer.writeBoolean(synchronizeDragonCap.isDragon);
            packetBuffer.writeFloat(synchronizeDragonCap.health);
            packetBuffer.writeBoolean(synchronizeDragonCap.hasWings);

        }, packetBuffer -> {
            int id = packetBuffer.readInt();
            DragonLevel level = DragonLevel.values()[packetBuffer.readByte()];
            DragonType type = DragonType.values()[packetBuffer.readByte()];
            boolean hiding = packetBuffer.readBoolean();
            boolean isDragon = packetBuffer.readBoolean();
            float health = packetBuffer.readFloat();

            return new SynchronizeDragonCap(id, hiding, type, level, isDragon, health, packetBuffer.readBoolean());
        }, (synchronizeDragonCap, contextSupplier) -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> new PacketProxy().handleCapabilitySync(synchronizeDragonCap, contextSupplier));
            if (contextSupplier.get().getDirection().getReceptionSide() == LogicalSide.SERVER) {
                CHANNEL.send(PacketDistributor.ALL.noArg(), synchronizeDragonCap);
                ServerPlayerEntity serverPlayerEntity = contextSupplier.get().getSender();
                DragonStateProvider.getCap(serverPlayerEntity).ifPresent(dragonStateHandler -> {
                    dragonStateHandler.setIsHiding(synchronizeDragonCap.hiding);
                    dragonStateHandler.setLevel(synchronizeDragonCap.dragonLevel, serverPlayerEntity);
                    dragonStateHandler.setIsDragon(synchronizeDragonCap.isDragon);
                    dragonStateHandler.setType(synchronizeDragonCap.dragonType);
                    dragonStateHandler.setHealth(synchronizeDragonCap.health);
                    dragonStateHandler.setHasWings(synchronizeDragonCap.hasWings);
                });
            }
        });

        LOGGER.info("Successfully registered packets!");
        EntityTypesInit.addSpawn();
        LOGGER.info("Successfully registered entity spawns!");
    }

    private void onServerStart(FMLServerStartingEvent serverStartingEvent) {
        CommandDispatcher<CommandSource> commandDispatcher = serverStartingEvent.getCommandDispatcher();
        RootCommandNode<CommandSource> rootCommandNode = commandDispatcher.getRoot();
        LiteralCommandNode<CommandSource> dragon = literal("dragon").build();

        ArgumentCommandNode<CommandSource, String> dragonType = argument("dragon_type", StringArgumentType.string()).suggests((context, builder) -> ISuggestionProvider.suggest(new String[]{"cave", "sea", "forest"}, builder)).build();

        ArgumentCommandNode<CommandSource, Integer> dragonStage = argument("dragon_stage", IntegerArgumentType.integer(1, 3)).executes(context -> {
            String type = context.getArgument("dragon_type", String.class);
            int stage = context.getArgument("dragon_stage", Integer.TYPE);
            ServerPlayerEntity serverPlayerEntity = context.getSource().asPlayer();
            serverPlayerEntity.getCapability(DragonStateProvider.PLAYER_STATE_HANDLER_CAPABILITY).ifPresent(dragonStateHandler -> {
                DragonType dragonType1 = DragonType.valueOf(type.toUpperCase());
                dragonStateHandler.setType(dragonType1);
                DragonLevel dragonLevel = DragonLevel.values()[stage - 1];
                dragonStateHandler.setLevel(dragonLevel, serverPlayerEntity);
                dragonStateHandler.setIsDragon(true);
                //works
                CHANNEL.send(PacketDistributor.ALL.noArg(), new SynchronizeDragonCap(serverPlayerEntity.getEntityId(), false, dragonType1, dragonLevel, true, 20, false));
            });
            return 1;
        }).build();

        rootCommandNode.addChild(dragon);
        dragon.addChild(dragonType);
        dragonType.addChild(dragonStage);
        LOGGER.info("Registered commands");
    }
}

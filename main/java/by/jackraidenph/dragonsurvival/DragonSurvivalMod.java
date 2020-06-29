package by.jackraidenph.dragonsurvival;

import by.jackraidenph.dragonsurvival.capability.IPlayerStateHandler;
import by.jackraidenph.dragonsurvival.capability.PlayerStateCapability;
import by.jackraidenph.dragonsurvival.capability.PlayerStateProvider;
import by.jackraidenph.dragonsurvival.models.Dragon;
import by.jackraidenph.dragonsurvival.models.DragonRenderer;
import by.jackraidenph.dragonsurvival.network.IMessage;
import by.jackraidenph.dragonsurvival.network.MessageSyncCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Quaternion;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(DragonSurvivalMod.MODID)
public class DragonSurvivalMod {
    public static final String MODID = "dragonsurvival";
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int nextId = 0;
    IPlayerStateHandler cap;
    PlayerEntity player;
    Dragon dragonRenderer = new Dragon();
    DragonRenderer renderer = new DragonRenderer();

    public DragonSurvivalMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setupClient);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static <T> void register(Class<T> clazz, IMessage<T> message) {
        INSTANCE.registerMessage(nextId++, clazz, message::encode, message::decode, message::handle);
    }

    private void setup(final FMLCommonSetupEvent event) {
        PlayerStateCapability.register();
        LOGGER.info("Successfully registered PlayerStateCapabilityHandler!");
        register(MessageSyncCapability.class, new MessageSyncCapability());
        LOGGER.info("Successfully registered MessageSyncCapability!");
    }

    private void setupClient(final FMLClientSetupEvent event) {
        Minecraft.getInstance().getRenderManager().register(EntityType.PLAYER, new DragonRenderer(Minecraft.getInstance().getRenderManager()));
        LOGGER.info("Successfully registered DragonRenderer!");
    }

    @SubscribeEvent
    public void onCapability(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof PlayerEntity) {
            event.addCapability(new ResourceLocation(DragonSurvivalMod.MODID, "playerstatehandler"), new PlayerStateProvider());
            LOGGER.info("Successfully attached capability to the PlayerEntity!");
        }
    }

    @SubscribeEvent
    public void onRender(RenderLivingEvent.Pre e) {
        if (e.getEntity() instanceof PlayerEntity) {
            player = Minecraft.getInstance().player;
            if (player.getCapability(PlayerStateProvider.PLAYER_STATE_HANDLER_CAPABILITY).isPresent()) {
                cap = player.getCapability(PlayerStateProvider.PLAYER_STATE_HANDLER_CAPABILITY).orElseGet(null);
                if (cap.getIsDragon()) {
                    e.setCanceled(true);
                    e.getMatrixStack().translate(0, 1.5f, 0);
                    e.getMatrixStack().rotate(new Quaternion(180f, 0f, 0f, true));
                    renderer.render(player, player.getYaw(e.getPartialRenderTick()), e.getPartialRenderTick(), e.getMatrixStack(), e.getBuffers(), e.getRenderer().getRenderManager().getPackedLight(player, e.getPartialRenderTick()));
                }
            }
        }
    }
}
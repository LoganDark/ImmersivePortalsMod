package com.qouteall.immersive_portals.mixin_client;

import com.qouteall.immersive_portals.CGlobal;
import com.qouteall.immersive_portals.ModMain;
import com.qouteall.immersive_portals.ModMainClient;
import com.qouteall.immersive_portals.ducks.IEGameRenderer;
import com.qouteall.immersive_portals.render.MyRenderHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer implements IEGameRenderer {
    @Shadow
    @Final
    @Mutable
    private LightmapTextureManager lightmapTextureManager;
    
    @Shadow
    private boolean renderHand;
    @Shadow
    @Final
    @Mutable
    private Camera camera;
    
    @Shadow
    @Final
    private MinecraftClient client;
    
    @Shadow
    private int ticks;
    
    @Shadow
    private boolean renderingPanorama;
    
    @Shadow
    public abstract void method_22709(Matrix4f matrix4f);
    
    @Inject(method = "render", at = @At("HEAD"))
    private void onFarBeforeRendering(
        float partialTicks,
        long nanoTime,
        boolean renderWorldIn,
        CallbackInfo ci
    ) {
        if (client.world == null) {
            return;
        }
        MyRenderHelper.updatePreRenderInfo(partialTicks);
        CGlobal.clientTeleportationManager.manageTeleportation(MyRenderHelper.tickDelta);
        ModMain.preRenderSignal.emit();
        if (CGlobal.earlyClientLightUpdate) {
            MyRenderHelper.earlyUpdateLight();
        }
    }
    
    //before rendering world (not triggered when rendering portal)
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V"
        )
    )
    private void onBeforeRenderingCenter(
        float float_1,
        long long_1,
        boolean boolean_1,
        CallbackInfo ci
    ) {
        ModMainClient.switchToCorrectRenderer();
        
        CGlobal.renderer.prepareRendering();
    }
    
    //after rendering world (not triggered when rendering portal)
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterRenderingCenter(
        float float_1,
        long long_1,
        boolean boolean_1,
        CallbackInfo ci
    ) {
        CGlobal.renderer.finishRendering();
        
        MyRenderHelper.onTotalRenderEnd();
    }
    
    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void onRenderCenterEnded(
        float float_1,
        long long_1,
        MatrixStack matrixStack_1,
        CallbackInfo ci
    ) {
        CGlobal.renderer.onRenderCenterEnded(matrixStack_1);
    }
    
    //resize all world renderers when resizing window
    @Inject(method = "onResized", at = @At("RETURN"))
    private void onOnResized(int int_1, int int_2, CallbackInfo ci) {
        if (CGlobal.clientWorldLoader != null) {
            CGlobal.clientWorldLoader.worldRendererMap.values().stream()
                .filter(
                    worldRenderer -> worldRenderer != client.worldRenderer
                )
                .forEach(
                    worldRenderer -> worldRenderer.onResized(int_1, int_2)
                );
        }
    }
    
    //View bobbing will make the camera pos offset to actuall camera pos
    //Teleportation is based on camera pos. If the teleportation is incorrect
    //then rendering will have problem
    //So smoothly disable view bobbing when player is near a portal
    @Redirect(
        method = "bobView",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/util/math/MatrixStack;translate(DDD)V"
        )
    )
    private void redirectBobViewTranslate(MatrixStack matrixStack, double x, double y, double z) {
        double viewBobFactor = MyRenderHelper.viewBobFactor;
        matrixStack.translate(x * viewBobFactor, y * viewBobFactor, z * viewBobFactor);
    }
    
    @Redirect(
        method = "renderWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;method_22709(Lnet/minecraft/client/util/math/Matrix4f;)V"
        )
    )
    private void redirectLoadProjectionMatrix(GameRenderer gameRenderer, Matrix4f matrix4f) {
        if (CGlobal.renderer.isRendering()) {
            //load recorded projection matrix
            method_22709(MyRenderHelper.projectionMatrix);
        }
        else {
            //load projection matrix normally
            method_22709(matrix4f);
            
            //record projection matrix
            if (MyRenderHelper.projectionMatrix == null) {
                MyRenderHelper.projectionMatrix = matrix4f;
            }
        }
    }
    
    @Inject(
        method = "renderWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
            shift = At.Shift.AFTER
        )
    )
    private void onCameraUpdated(
        float tickDelta,
        long limitTime,
        MatrixStack matrix,
        CallbackInfo ci
    ) {
        if (CGlobal.renderer.isRendering()) {
            MyRenderHelper.adjustCameraPos(camera);
        }
    }
    
    @Override
    public void setLightmapTextureManager(LightmapTextureManager manager) {
        lightmapTextureManager = manager;
    }
    
    @Override
    public boolean getDoRenderHand() {
        return renderHand;
    }
    
    @Override
    public void setDoRenderHand(boolean e) {
        renderHand = e;
    }
    
    @Override
    public void setCamera(Camera camera_) {
        camera = camera_;
    }
    
    @Override
    public void setIsRenderingPanorama(boolean cond) {
        renderingPanorama = cond;
    }
}

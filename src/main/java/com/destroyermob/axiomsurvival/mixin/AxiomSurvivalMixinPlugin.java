package com.destroyermob.axiomsurvival.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class AxiomSurvivalMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> CAPTURE_MIXINS = Set.of(
            "com.destroyermob.axiomsurvival.mixin.AxiomClientActivationMixin",
            "com.destroyermob.axiomsurvival.mixin.AxiomSetBlockCaptureMixin",
            "com.destroyermob.axiomsurvival.mixin.AxiomSetBufferCaptureMixin",
            "com.destroyermob.axiomsurvival.mixin.AxiomServerPermissionsMixin"
    );

    private boolean captureEnabled;

    @Override
    public void onLoad(String mixinPackage) {
        captureEnabled = readCaptureEnabled();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !CAPTURE_MIXINS.contains(mixinClassName) || captureEnabled;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean readCaptureEnabled() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("axiom-survival.json");
            if (!Files.exists(path)) {
                return false;
            }
            String json = Files.readString(path);
            return json.matches("(?s).*\"enableAxiomVanillaEditCapture\"\\s*:\\s*true.*");
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}

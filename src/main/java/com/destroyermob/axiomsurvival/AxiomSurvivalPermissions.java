package com.destroyermob.axiomsurvival;

import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

public final class AxiomSurvivalPermissions {
    private static final List<String> SURVIVAL_PERMISSION_NAMES = List.of(
            "USE",
            "BUILD",
            "CHUNK",
            "CAN_IMPORT_BLOCKS",
            "CAN_EXPORT_BLOCKS",
            "BLUEPRINT",
            "EDITOR",
            "TOOL",
            "PLAYER_BYPASS_MOVEMENT_RESTRICTIONS",
            "PLAYER_HOTBAR",
            "BUILDERTOOL"
    );

    private static Object survivalPermissionSet;
    private static boolean warnedAboutPermissionProfile;

    private AxiomSurvivalPermissions() {
    }

    public static Object survivalPermissionSet(ServerPlayerEntity player) {
        if (!shouldGrantSurvivalAxiom(player)) {
            return null;
        }
        Object cached = survivalPermissionSet;
        if (cached != null) {
            return cached;
        }
        survivalPermissionSet = createSurvivalPermissionSet();
        return survivalPermissionSet;
    }

    private static boolean shouldGrantSurvivalAxiom(ServerPlayerEntity player) {
        return AxiomSurvivalConfig.captureEnabled()
                && player != null
                && player.interactionManager.getGameMode() == GameMode.SURVIVAL;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object createSurvivalPermissionSet() {
        try {
            Class<?> permissionClass = Class.forName("com.moulberry.axiom.restrictions.AxiomPermission");
            Class<? extends Enum> permissionEnumClass = permissionClass.asSubclass(Enum.class);
            EnumSet allowed = EnumSet.noneOf(permissionEnumClass);
            for (String permissionName : SURVIVAL_PERMISSION_NAMES) {
                allowed.add(Enum.valueOf(permissionEnumClass, permissionName));
            }
            EnumSet denied = EnumSet.noneOf(permissionEnumClass);
            Class<?> permissionSetClass = Class.forName("com.moulberry.axiom.restrictions.AxiomPermissionSet");
            Constructor<?> constructor = permissionSetClass.getConstructor(EnumSet.class, EnumSet.class);
            return constructor.newInstance(allowed, denied);
        } catch (ReflectiveOperationException exception) {
            if (!warnedAboutPermissionProfile) {
                warnedAboutPermissionProfile = true;
                AxiomSurvival.LOGGER.warn("Could not create Axiom Survival permission profile.", exception);
            }
            return null;
        }
    }
}

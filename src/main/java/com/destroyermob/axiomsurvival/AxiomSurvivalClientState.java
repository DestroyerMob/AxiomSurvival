package com.destroyermob.axiomsurvival;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.GameMode;

public final class AxiomSurvivalClientState {
    private static final String CLIENT_EVENTS_CLASS = "com.moulberry.axiom.ClientEvents";
    private static final String STATIC_VALUES_CLASS = "com.moulberry.axiom.StaticValues";
    private static final String AXIOM_CLASS = "com.moulberry.axiom.Axiom";
    private static final String EDITOR_UI_CLASS = "com.moulberry.axiom.editor.EditorUI";
    private static final String BUILDER_TOOL_MANAGER_CLASS = "com.moulberry.axiom.buildertools.BuilderToolManager";
    private static final Map<String, Field> STATIC_BOOLEAN_FIELDS = new HashMap<>();
    private static Method axiomGetInstanceMethod;
    private static Field axiomServerConfigField;
    private static Method replayGetReplayHandlerMethod;
    private static Field replayInstanceField;
    private static Method editorUiIsActiveMethod;
    private static Method builderCanUseToolsMethod;
    private static Method builderSetToolSlotActiveMethod;
    private static boolean warnedAboutActivationState;
    private static boolean warnedAboutBuilderSlot;
    private static boolean warnedAboutReplayState;

    private AxiomSurvivalClientState() {
    }

    public static boolean shouldTreatSurvivalAsAxiomActive(GameMode requestedMode) {
        if (requestedMode != GameMode.CREATIVE && requestedMode != GameMode.SPECTATOR) {
            return false;
        }
        return isSurvivalAxiomReady(MinecraftClient.getInstance());
    }

    public static void keepBuilderToolSlotVisible(MinecraftClient client) {
        if (!isSurvivalAxiomReady(client) || isEditorUiActive()) {
            return;
        }

        try {
            if (!canUseBuilderTools()) {
                return;
            }
            builderSetToolSlotActiveMethod().invoke(null, true);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!warnedAboutBuilderSlot) {
                warnedAboutBuilderSlot = true;
                AxiomSurvival.LOGGER.warn("Could not keep Axiom's builder tool slot visible in survival.", exception);
            }
        }
    }

    private static boolean isSurvivalAxiomReady(MinecraftClient client) {
        if (!AxiomSurvivalConfig.captureEnabled()
                || client == null
                || client.player == null
                || client.interactionManager == null
                || client.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL) {
            return false;
        }

        try {
            return staticBoolean(STATIC_VALUES_CLASS, "gameHasTicked")
                    && axiomServerConfigReady()
                    && staticBoolean(CLIENT_EVENTS_CLASS, "allowedOnServer")
                    && staticBoolean(CLIENT_EVENTS_CLASS, "serverSupportsAxiom")
                    && staticBoolean(CLIENT_EVENTS_CLASS, "processedServerSupportsAxiom")
                    && staticBoolean(CLIENT_EVENTS_CLASS, "processedAllowedOnServer")
                    && !staticBoolean(CLIENT_EVENTS_CLASS, "remotelyDisabled")
                    && !isReplayActive();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!warnedAboutActivationState) {
                warnedAboutActivationState = true;
                AxiomSurvival.LOGGER.warn("Could not inspect Axiom's client activation state.", exception);
            }
            return false;
        }
    }

    private static boolean axiomServerConfigReady() throws ReflectiveOperationException {
        Object axiom = axiomGetInstanceMethod().invoke(null);
        return axiom != null && axiomServerConfigField().get(axiom) != null;
    }

    private static boolean isReplayActive() throws ReflectiveOperationException {
        if (!FabricLoader.getInstance().isModLoaded("replaymod")) {
            return false;
        }

        try {
            Object replay = replayInstanceField().get(null);
            return replay != null && replayGetReplayHandlerMethod().invoke(replay) != null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!warnedAboutReplayState) {
                warnedAboutReplayState = true;
                AxiomSurvival.LOGGER.warn("Could not inspect ReplayMod state for Axiom Survival.", exception);
            }
            return true;
        }
    }

    private static boolean isEditorUiActive() {
        try {
            return (Boolean) editorUiIsActiveMethod().invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!warnedAboutBuilderSlot) {
                warnedAboutBuilderSlot = true;
                AxiomSurvival.LOGGER.warn("Could not inspect Axiom's editor UI state.", exception);
            }
            return true;
        }
    }

    private static boolean canUseBuilderTools() throws ReflectiveOperationException {
        return (Boolean) builderCanUseToolsMethod().invoke(null);
    }

    private static boolean staticBoolean(String className, String fieldName) throws ReflectiveOperationException {
        return staticBooleanField(className, fieldName).getBoolean(null);
    }

    private static Field staticBooleanField(String className, String fieldName) throws ReflectiveOperationException {
        String key = className + "#" + fieldName;
        Field cached = STATIC_BOOLEAN_FIELDS.get(key);
        if (cached != null) {
            return cached;
        }

        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        STATIC_BOOLEAN_FIELDS.put(key, field);
        return field;
    }

    private static Method axiomGetInstanceMethod() throws ReflectiveOperationException {
        if (axiomGetInstanceMethod == null) {
            axiomGetInstanceMethod = Class.forName(AXIOM_CLASS).getDeclaredMethod("getInstance");
            axiomGetInstanceMethod.setAccessible(true);
        }
        return axiomGetInstanceMethod;
    }

    private static Field axiomServerConfigField() throws ReflectiveOperationException {
        if (axiomServerConfigField == null) {
            axiomServerConfigField = Class.forName(AXIOM_CLASS).getDeclaredField("serverConfig");
            axiomServerConfigField.setAccessible(true);
        }
        return axiomServerConfigField;
    }

    private static Field replayInstanceField() throws ReflectiveOperationException {
        if (replayInstanceField == null) {
            replayInstanceField = Class.forName("com.replaymod.replay.ReplayModReplay").getDeclaredField("instance");
            replayInstanceField.setAccessible(true);
        }
        return replayInstanceField;
    }

    private static Method replayGetReplayHandlerMethod() throws ReflectiveOperationException {
        if (replayGetReplayHandlerMethod == null) {
            replayGetReplayHandlerMethod = Class.forName("com.replaymod.replay.ReplayModReplay")
                    .getDeclaredMethod("getReplayHandler");
            replayGetReplayHandlerMethod.setAccessible(true);
        }
        return replayGetReplayHandlerMethod;
    }

    private static Method editorUiIsActiveMethod() throws ReflectiveOperationException {
        if (editorUiIsActiveMethod == null) {
            editorUiIsActiveMethod = Class.forName(EDITOR_UI_CLASS).getDeclaredMethod("isActive");
            editorUiIsActiveMethod.setAccessible(true);
        }
        return editorUiIsActiveMethod;
    }

    private static Method builderCanUseToolsMethod() throws ReflectiveOperationException {
        if (builderCanUseToolsMethod == null) {
            builderCanUseToolsMethod = Class.forName(BUILDER_TOOL_MANAGER_CLASS).getDeclaredMethod("canUseBuilderTools");
            builderCanUseToolsMethod.setAccessible(true);
        }
        return builderCanUseToolsMethod;
    }

    private static Method builderSetToolSlotActiveMethod() throws ReflectiveOperationException {
        if (builderSetToolSlotActiveMethod == null) {
            builderSetToolSlotActiveMethod = Class.forName(BUILDER_TOOL_MANAGER_CLASS)
                    .getDeclaredMethod("setToolSlotActive", boolean.class);
            builderSetToolSlotActiveMethod.setAccessible(true);
        }
        return builderSetToolSlotActiveMethod;
    }
}

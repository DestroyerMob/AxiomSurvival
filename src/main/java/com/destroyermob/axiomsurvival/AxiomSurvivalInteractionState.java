package com.destroyermob.axiomsurvival;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.GameMode;

public final class AxiomSurvivalInteractionState {
    private static final String BUILDER_TOOL_MANAGER_CLASS = "com.moulberry.axiom.buildertools.BuilderToolManager";
    private static final String EDITOR_UI_CLASS = "com.moulberry.axiom.editor.EditorUI";
    private static Field builderToolSlotActiveField;
    private static Method editorUiActiveMethod;
    private static boolean warnedAboutInteractionState;

    private AxiomSurvivalInteractionState() {
    }

    public static boolean shouldBypassAxiomWorldInteraction() {
        if (!AxiomSurvivalClientState.shouldTreatSurvivalAsAxiomActive(GameMode.CREATIVE)) {
            return false;
        }

        try {
            return !editorUiActive() && !rawBuilderToolSlotActive();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!warnedAboutInteractionState) {
                warnedAboutInteractionState = true;
                AxiomSurvival.LOGGER.warn("Could not inspect Axiom's interaction state.", exception);
            }
            return false;
        }
    }

    private static boolean rawBuilderToolSlotActive() throws ReflectiveOperationException {
        if (builderToolSlotActiveField == null) {
            builderToolSlotActiveField = Class.forName(BUILDER_TOOL_MANAGER_CLASS).getDeclaredField("toolSlotActive");
            builderToolSlotActiveField.setAccessible(true);
        }
        return builderToolSlotActiveField.getBoolean(null);
    }

    private static boolean editorUiActive() throws ReflectiveOperationException {
        if (editorUiActiveMethod == null) {
            editorUiActiveMethod = Class.forName(EDITOR_UI_CLASS).getDeclaredMethod("isActive");
            editorUiActiveMethod.setAccessible(true);
        }
        return (boolean) editorUiActiveMethod.invoke(null);
    }
}

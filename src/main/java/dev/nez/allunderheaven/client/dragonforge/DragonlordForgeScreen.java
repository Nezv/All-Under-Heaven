package dev.nez.allunderheaven.client.dragonforge;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragonforge.DragonlordForgeMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Dragon-lord Forge screen: the custom forge panel, with the fuel flame
 * (dragon blood) filling bottom-up and the forging arrow sweeping left-right.
 * Both progress marks are blitted directly from the baked GUI sheet, so no
 * sprite-atlas plumbing is needed. (26.2 draws the container background in
 * {@code extractBackground}, not the old {@code renderBg}.)
 */
public class DragonlordForgeScreen extends AbstractContainerScreen<DragonlordForgeMenu> {
    private static final Identifier TEXTURE =
            AllUnderHeaven.id("textures/gui/container/dragonlord_forge.png");

    public DragonlordForgeScreen(DragonlordForgeMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = this.leftPos;
        int y = this.topPos;
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, 256, 256);

        // fuel flame under the blood slot, revealed bottom-up
        if (this.menu.isLit()) {
            int k = Mth.ceil(this.menu.burnProgress() * 14.0F);
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x + 56, y + 36 + (14 - k),
                    176.0F, (14 - k), 14, k, 256, 256);
        }
        // forging arrow, filled left-to-right
        int w = Mth.ceil(this.menu.cookProgress() * 24.0F);
        if (w > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x + 79, y + 34,
                    176.0F, 14.0F, w, 17, 256, 256);
        }
    }
}

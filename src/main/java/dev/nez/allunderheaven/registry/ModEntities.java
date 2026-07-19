package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragon.DragonEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entities. The dragon's hitbox covers the body mass (the wings overhang it;
 * the culling box is padded in {@link DragonEntity#getBoundingBoxForCulling}).
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AllUnderHeaven.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<DragonEntity>> DRAGON =
            ENTITY_TYPES.register("dragon", () -> EntityType.Builder
                    .of(DragonEntity::new, MobCategory.CREATURE)
                    .sized(4.2F, 5.2F)
                    .eyeHeight(4.9F)
                    .fireImmune()
                    .clientTrackingRange(12)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, AllUnderHeaven.id("dragon"))));

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(DRAGON.get(), DragonEntity.createAttributes().build());
    }

    private ModEntities() {
    }
}

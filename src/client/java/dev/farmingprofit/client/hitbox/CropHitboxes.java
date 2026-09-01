package dev.farmingprofit.client.hitbox;

import dev.farmingprofit.client.FarmingProfitClient;
import dev.farmingprofit.client.config.ModConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Hitbox de visée uniquement (pas la collision).
 * Mature : cube 1×1×1. Pas mature : hitbox la plus basse (stade 0).
 */
public final class CropHitboxes {
	public static final VoxelShape FULL_BLOCK = Shapes.block();
	/** Blé / carotte / pomme de terre âge 0 : 2 pixels de haut. */
	public static final VoxelShape LOW_CROP = Block.column(16.0, 0.0, 2.0);
	/** Nether wart âge 0 : 5 pixels de haut. */
	public static final VoxelShape LOW_NETHER_WART = Block.column(16.0, 0.0, 5.0);

	private CropHitboxes() {
	}

	public static boolean enabled() {
		ModConfig config = FarmingProfitClient.config();
		return config != null && config.fullCropHitboxes;
	}

	public static VoxelShape selectionShape(Object block, BlockState state) {
		if (!enabled()) {
			return null;
		}
		if (block instanceof MushroomBlock) {
			return FULL_BLOCK;
		}
		if (isMature(block, state)) {
			return FULL_BLOCK;
		}
		if (block instanceof CocoaBlock) {
			return null;
		}
		if (block instanceof NetherWartBlock) {
			return LOW_NETHER_WART;
		}
		if (block instanceof CropBlock) {
			return LOW_CROP;
		}
		return null;
	}

	private static boolean isMature(Object block, BlockState state) {
		if (block instanceof CropBlock crop) {
			return crop.isMaxAge(state);
		}
		if (block instanceof NetherWartBlock) {
			return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
		}
		if (block instanceof CocoaBlock) {
			return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
		}
		return false;
	}
}

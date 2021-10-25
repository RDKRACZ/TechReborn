package techreborn.blockentity.cable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import team.reborn.energy.api.EnergyStorage;
import techreborn.init.TRContent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
class CableTickManager {
	private static long tickCounter = 0;
	private static final List<CableBlockEntity> cableList = new ArrayList<>();
	private static final List<OfferedEnergyStorage> targetStorages = new ArrayList<>();
	private static final Deque<CableBlockEntity> bfsQueue = new ArrayDeque<>();

	static void handleCableTick(CableBlockEntity startingCable) {
		if (!(startingCable.getWorld() instanceof ServerWorld)) throw new IllegalStateException();

		try {
			gatherCables(startingCable);
			if (cableList.size() == 0) return;

			// Group all energy into the network.
			long networkCapacity = 0;
			long networkAmount = 0;

			for (CableBlockEntity cable : cableList) {
				networkAmount += cable.energyContainer.amount;
				networkCapacity += cable.energyContainer.getCapacity();

				// Update cable connections.
				cable.appendTargets(targetStorages);
				// Block any cable I/O while we access the network amount directly.
				// Some things might try to access cables, for example a p2p tunnel pointing back at a cable.
				// If the cables and the network go out of sync, we risk duping or voiding energy.
				cable.ioBlocked = true;
			}

			// Just in case.
			if (networkAmount > networkCapacity) {
				networkAmount = networkCapacity;
			}

			// Pull energy from storages.
			networkAmount += dispatchTransfer(startingCable.getCableType(), EnergyStorage::extract, networkCapacity - networkAmount);
			// Push energy into storages.
			networkAmount -= dispatchTransfer(startingCable.getCableType(), EnergyStorage::insert, networkAmount);

			// Split energy evenly across cables.
			int cableCount = cableList.size();
			for (CableBlockEntity cable : cableList) {
				cable.energyContainer.amount = networkAmount / cableCount;
				networkAmount -= cable.energyContainer.amount;
				cableCount--;
				cable.markDirty();
				cable.ioBlocked = false;
			}
		} finally {
			cableList.clear();
			targetStorages.clear();
			bfsQueue.clear();
		}
	}

	private static boolean shouldTickCable(CableBlockEntity current) {
		// Make sure we only gather and tick each cable once per tick.
		if (current.lastTick == tickCounter) return false;
		// Make sure we ignore cables in non-ticking chunks.
		if (!(current.getWorld() instanceof ServerWorld sw) || !sw.method_37117(current.getPos())) return false;

		return true;
	}

	/**
	 * Perform a BFS to gather all connected ticking cables.
	 */
	private static void gatherCables(CableBlockEntity start) {
		if (!shouldTickCable(start)) return;

		bfsQueue.add(start);
		start.lastTick = tickCounter;
		cableList.add(start);

		while (!bfsQueue.isEmpty()) {
			CableBlockEntity current = bfsQueue.removeFirst();

			for (Direction direction : Direction.values()) {
				BlockPos adjPos = current.getPos().offset(direction);

				if (current.getWorld().getBlockEntity(adjPos) instanceof CableBlockEntity adjCable && current.getCableType() == adjCable.getCableType()) {
					if (shouldTickCable(adjCable)) {
						bfsQueue.add(adjCable);
						adjCable.lastTick = tickCounter;
						cableList.add(adjCable);
					}
				}
			}
		}
	}

	/**
	 * Perform a transfer operation across a list of targets.
	 */
	private static long dispatchTransfer(TRContent.Cables cableType, TransferOperation operation, long maxAmount) {
		// Build target list.
		List<SortableStorage> sortedTargets = new ArrayList<>();
		for (var storage : targetStorages) {
			sortedTargets.add(new SortableStorage(operation, storage));
		}
		// Shuffle for better average transfer.
		Collections.shuffle(sortedTargets);
		// Sort by lowest simulation target.
		sortedTargets.sort(Comparator.comparingLong(sortableStorage -> sortableStorage.simulationResult));
		// Actually perform the transfer.
		try (Transaction transaction = Transaction.openOuter()) {
			long transferredAmount = 0;
			for (int i = 0; i < sortedTargets.size(); ++i) {
				SortableStorage target = sortedTargets.get(i);
				int remainingTargets = sortedTargets.size() - i;
				long remainingAmount = maxAmount - transferredAmount;
				// Limit max amount to the cable transfer rate.
				long targetMaxAmount = Math.min(remainingAmount / remainingTargets, cableType.transferRate);

				long localTransferred = operation.transfer(target.storage.storage, targetMaxAmount, transaction);
				if (localTransferred > 0) {
					transferredAmount += localTransferred;
					// Block duplicate operations.
					target.storage.afterTransfer();
				}
			}
			transaction.commit();
			return transferredAmount;
		}
	}

	private interface TransferOperation {
		long transfer(EnergyStorage storage, long maxAmount, Transaction transaction);
	}

	private static class SortableStorage {
		private final OfferedEnergyStorage storage;
		private final long simulationResult;

		SortableStorage(TransferOperation operation, OfferedEnergyStorage storage) {
			this.storage = storage;
			try (Transaction tx = Transaction.openOuter()) {
				this.simulationResult = operation.transfer(storage.storage, Long.MAX_VALUE, tx);
			}
		}
	}

	static {
		ServerTickEvents.START_SERVER_TICK.register(server -> tickCounter++);
	}
}

package dev.stowling.packing;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PackingTestFixtures {
    static final String DIMENSION = "minecraft:overworld";
    static final String ITEM_ID = "minecraft:diamond";
    static final String OTHER_ITEM_ID = "minecraft:emerald";

    private PackingTestFixtures() {
    }

    static ContainerRef container(String id, int x, ContainerType type) {
        return new ContainerRef(
                id,
                DIMENSION,
                new BlockPosition(x, 64, 0),
                type
        );
    }

    static PackingWorkcell workcell(
            String id,
            PackingTrigger trigger,
            int threshold,
            PackingReleaseMode releaseMode
    ) {
        ContainerRef product = container(
                "product-" + id,
                100 + id.length(),
                ContainerType.CHEST
        );
        PackingStation station = new PackingStation(
                id,
                DIMENSION,
                new BlockPosition(10, 64, 0),
                product,
                trigger,
                threshold
        );
        return new PackingWorkcell(station, releaseMode);
    }

    static PackingStationCandidate candidate(
            PackingWorkcell workcell,
            boolean reachable,
            boolean operational,
            long distanceSquared
    ) {
        return new PackingStationCandidate(
                workcell,
                reachable,
                operational,
                distanceSquared
        );
    }

    static PackingStationCandidate available(String id, long distanceSquared) {
        return candidate(
                workcell(
                        id,
                        PackingTrigger.ON_ITEM_COUNT,
                        64,
                        PackingReleaseMode.AUTO_ON_CLOSE
                ),
                true,
                true,
                distanceSquared
        );
    }

    static List<String> autoOnCloseCalls() {
        List<String> calls = new ArrayList<>();
        calls.add("inspect:station");
        calls.add("insert:station");
        calls.add("inspect:station");
        calls.add("close:station");
        calls.add("await:station");
        return List.copyOf(calls);
    }

    static List<String> explicitInteractCalls() {
        List<String> calls = new ArrayList<>();
        calls.add("inspect:station");
        calls.add("insert:station");
        calls.add("inspect:station");
        calls.add("close:station");
        calls.add("trigger:station");
        calls.add("await:station");
        return List.copyOf(calls);
    }

    static PackingRequest request(int quantity) {
        return request(ITEM_ID, quantity);
    }

    static PackingRequest request(String itemId, int quantity) {
        StorageRoute route = new StorageRoute(
                "route",
                List.of(itemId),
                List.of(),
                10,
                container("bulk", 20, ContainerType.CHEST),
                null,
                null
        );
        return new PackingRequest(
                "task",
                DIMENSION,
                itemId,
                quantity,
                route,
                container("overflow", 24, ContainerType.CHEST)
        );
    }

    static final class FakePackingPort implements PackingStationPort {
        private final Map<String, PackingBoxContents> boxes = new HashMap<>();
        private final Set<String> inspectFailures = new HashSet<>();
        private final Set<String> insertFailures = new HashSet<>();
        private final Set<String> closeFailures = new HashSet<>();
        private final Set<String> triggerFailures = new HashSet<>();
        private final Set<String> missingProducts = new HashSet<>();
        private final Set<String> unconfirmedInsertions = new HashSet<>();
        private final Map<String, Integer> insertionLimits = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private int capacitySlots = 27;

        void setContents(String stationId, PackingBoxContents contents) {
            boxes.put(stationId, contents);
        }

        void failInspection(String stationId) {
            inspectFailures.add(stationId);
        }

        void failInsertion(String stationId) {
            insertFailures.add(stationId);
        }

        void failClose(String stationId) {
            closeFailures.add(stationId);
        }

        void failTrigger(String stationId) {
            triggerFailures.add(stationId);
        }

        void omitProduct(String stationId) {
            missingProducts.add(stationId);
        }

        void doNotConfirmInsertion(String stationId) {
            unconfirmedInsertions.add(stationId);
        }

        void limitInsertion(String stationId, int maximum) {
            insertionLimits.put(stationId, maximum);
        }

        void setCapacitySlots(int capacitySlots) {
            this.capacitySlots = capacitySlots;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        int itemCount(String stationId, String itemId) {
            PackingBoxContents contents = boxes.get(stationId);
            return contents == null ? 0 : (int) contents.exactItemCount(itemId);
        }

        @Override
        public PackingBoxContents inspect(PackingWorkcell workcell)
                throws PackingStationException {
            String stationId = workcell.station().id();
            calls.add("inspect:" + stationId);
            if (inspectFailures.contains(stationId)) {
                throw new PackingStationTimeoutException("inspection timed out");
            }
            return boxes.getOrDefault(
                    stationId,
                    PackingBoxContents.empty(capacitySlots)
            );
        }

        @Override
        public int insert(
                PackingWorkcell workcell,
                String itemId,
                int maximumQuantity
        ) throws PackingStationException {
            String stationId = workcell.station().id();
            calls.add("insert:" + stationId);
            if (insertFailures.contains(stationId)) {
                throw new PackingStationException("insertion failed");
            }
            int stationLimit = insertionLimits.getOrDefault(
                    stationId,
                    maximumQuantity
            );
            int inserted = Math.min(maximumQuantity, stationLimit);
            if (unconfirmedInsertions.contains(stationId)) {
                return inserted;
            }
            PackingBoxContents current = boxes.getOrDefault(
                    stationId,
                    PackingBoxContents.empty(capacitySlots)
            );
            int newCount = Math.toIntExact(current.exactItemCount(itemId)) + inserted;
            int occupiedSlots = Math.max(1, (newCount + 63) / 64);
            PackingBoxContents updated = PackingBoxContents.exact(
                    itemId,
                    newCount,
                    occupiedSlots,
                    capacitySlots
            );
            boxes.put(stationId, updated);
            return inserted;
        }

        @Override
        public void close(PackingWorkcell workcell) throws PackingStationException {
            String stationId = workcell.station().id();
            calls.add("close:" + stationId);
            if (closeFailures.contains(stationId)) {
                throw new PackingStationException("close failed");
            }
        }

        @Override
        public void triggerBreak(PackingWorkcell workcell)
                throws PackingStationException {
            String stationId = workcell.station().id();
            calls.add("trigger:" + stationId);
            if (triggerFailures.contains(stationId)) {
                throw new PackingStationException("trigger failed");
            }
        }

        @Override
        public boolean awaitPackedBoxInProductContainer(
                PackingWorkcell workcell,
                String itemId
        ) throws PackingStationException {
            String stationId = workcell.station().id();
            calls.add("await:" + stationId);
            return !missingProducts.contains(stationId);
        }
    }
}

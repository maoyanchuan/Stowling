from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_overflow_only_advances_past_a_confirmed_full_unstackable_container() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    dedicated = source.split("private List<ContainerTarget> unstackableDestinations()", 1)[1]
    dedicated = dedicated.split("private void findReusableOverflowBox(", 1)[0]
    assert "scanComplete(unstackableRegion," in dedicated
    assert "if (!scan.succeeded())" in dedicated
    assert "if (scan.containers().isEmpty())" in dedicated

    store = dedicated.split("private void storeUnstackable(", 1)[1]
    assert store.index("if (index >= targets.size())") < store.index("findReusableOverflowBox(0)")
    assert store.index("transfer(target, items, false, contents ->") < store.index(
        "!contents.isEmpty() && contents.stream().noneMatch(ItemStack::isEmpty)"
    ) < store.index("storeUnstackable(index + 1, remaining)")
    assert store.count("findReusableOverflowBox(0)") == 1
    assert "不可堆叠溢出箱有空位但未能完成存放" in store


def test_overflow_extracts_unstackable_contents_before_forwarding_a_full_shulker() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    pack = source.split("private void packOwned() {", 1)[1].split(
        "private Map<String, Integer> unstackableOwned()", 1
    )[0]
    assert pack.index(".filter(MinecraftStorageAutomation::isUnstackableItem)") < pack.index(
        "unpackOverflowBox(unstackable, true)"
    ) < pack.index("MinecraftItemIdentity.full(box)")
    assert "storeUnstackable(0, items)" in pack


def test_packing_continues_after_storing_a_filled_box() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    choose = source.split("private void chooseBox() {", 1)[1].split(
        "private void requireOwned(", 1
    )[0]
    assert "packedBox" not in choose
    assert choose.index("box = targetContents.stream()") < choose.index(
        "if (box.isEmpty() && !supplied.isEmpty())"
    )
    assert "withdrawEmptyBox(0, suppliedBox ->" in choose
    assert "StoragePackingPlanner.targetItemsForPacking(" in choose
    store = source.split("private void storeBox() {", 1)[1].split(
        "private void defer() {", 1
    )[0]
    assert "inspectTarget();" in store


def test_preparation_reserves_a_spare_independently_of_full_box_packing() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    prepare = source.split("private final class PrepareBox", 1)[1].split(
        "private final class Packing", 1
    )[0]
    assert "RecordedStorageCapacity.shouldReserveBox(" in prepare
    assert "StoragePackingPlanner.shouldBringBox(" not in prepare
    select = (ROOT / "src/main/java/dev/stowling/v2/runtime/modules/"
              "SelectStorageTargetModule.java").read_text(encoding="utf-8")
    assert "if (context.workspace().isClassificationStorage(target)) {" in select
    assert "if (allowCacheFallback) {" not in select


def test_cache_packing_and_donor_selection_reuse_confirmed_contents() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    confirmed = source.split("private Optional<CacheSnapshot> confirmedCacheSnapshot(", 1)[1]
    confirmed = confirmed.split("@Override", 1)[0]
    assert "context.workspace().cacheContents(target)" in confirmed
    assert "counts.equals(snapshot.counts())" in confirmed

    scan = source.split("private void scan(int index,", 1)[1].split(
        "private void tracePackingSource", 1
    )[0]
    assert scan.index("confirmedCacheSnapshot(context, source)") < scan.index(
        "read(source, contents ->"
    )
    assert "cacheSnapshots.put(source.position(), snapshot)" in scan
    assert "refreshCacheSnapshot(access);" in source
    refresh = source.split("private void refreshCacheSnapshot(", 1)[1].split(
        "final void read(", 1
    )[0]
    assert "recordCacheContents(context, target, contents)" in refresh
    assert "cacheSnapshots.put(target.position(), snapshot)" in refresh
    assert "sameContainer(cache, target)" in refresh
    access = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAccess.java").read_text(encoding="utf-8")
    assert "recordCacheContents(" not in access

    work = source.split("private boolean queueSourceWork()", 1)[1].split(
        "private boolean prepareSource", 1
    )[0]
    assert work.index("confirmedCacheContents(context, currentSource)") < work.index(
        "readCache(currentSource, contents ->"
    )


def test_cache_packing_skips_empty_requests_and_reuses_source_stack_sizes() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    plan = source.split("private void gather(int index)", 1)[1].split(
        "private void finishCategoryLoad()", 1
    )[0]
    assert "inventoryFitting(wanted, snapshot)" in plan
    assert "inventoryFitting(requested, snapshot)" in plan
    fitting = source.split("private Map<String, Integer> inventoryFitting(", 1)[1].split(
        "private void gatherOtherCategories(", 1
    )[0]
    assert fitting.index("if (wanted.isEmpty()) return Map.of();") < fitting.index(
        "minecraft.player.getInventory()"
    )
    assert "wanted, snapshot.stackSizes()" in fitting
    assert "snapshots()" not in fitting


def test_companion_categories_use_the_same_snapshot_for_items_and_full_boxes() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    gather = source.split("private void gatherOtherCategories(int index)", 1)[1].split(
        "private int targetLooseDeficit()", 1
    )[0]
    assert "Set<String> present = snapshot.counts().keySet();" in gather
    assert "snapshot.fullBoxKeys().contains(key)" in gather
    assert "MinecraftItemIdentity.key(" not in gather
    assert "snapshot.stacks()" not in gather


def test_generic_mixed_box_batch_leaves_dedicated_pure_box_stock_to_packing() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    unpack = source.split("private boolean shouldUnpack(ItemStack candidate)", 1)[1].split(
        "private int layoutPriority", 1
    )[0]
    assert unpack.index("if (packingCategory != null)") < unpack.index("if (mixed) return true")
    assert "context.workspace().classificationTargets(category)" in unpack
    assert "policy.mode() == StorageMode.BOX_ONLY" in unpack
    assert "policy.partialBoxDestinationRef() != null) return false" in unpack


def test_pure_partial_box_categories_prepare_in_the_same_withdrawal_when_space_remains() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    batch = source.split("private final class CachePackingBatch", 1)[1].split(
        "private record CachedBox", 1
    )[0]
    finish = batch.split("private void finishCategoryLoad()", 1)[1].split(
        "private void loadCompanionCategories()", 1
    )[0]
    assert "loadCompanionCategories()" in finish
    companion = batch.split("private void loadCompanionCategories()", 1)[1].split(
        "private boolean hasCompanionStock()", 1
    )[0]
    assert "remainingBatchSlots() > 0" in companion
    assert "RESERVED_INVENTORY_SLOTS" in companion
    assert "nextPolicy.mode() == StorageMode.NORMAL" in companion
    assert "retainedByDestination.computeIfAbsent(" in companion
    assert "nextPolicy.partialBoxDestinationRef()," in companion
    assert "partialBoxContainers(context, nextPolicy, configuration)" in companion
    assert "allMatch(source -> isRetainedPartialSource(source, retained))" in companion
    assert companion.index("startCategory(retained)") < companion.index("gatherOtherCategories(0)")
    assert "preparedCategories.add(category)" in batch
    assert "!preparedCategories.contains(next)" in batch
    assert "!ItemIdentity.MIXED_BOX_CATEGORY.equals(next)" in batch
    assert "int slots = remainingBatchSlots();" in batch


def test_partial_box_lookup_uses_resolved_settings() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    lookup = source.split("private List<ContainerTarget> partialBoxContainers(", 1)[1].split(
        "private static boolean sameContainer(", 1
    )[0]
    assert "configuration.emptyBoxContainers()" in lookup
    assert "settings.resolved()" not in lookup


def test_box_only_refill_space_comes_from_the_configured_partial_cache() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    space = source.split("private int recordedBoxSpace()", 1)[1].split("@Override", 1)[0]
    assert space.index("policy.partialBoxDestinationRef() != null") < space.index(
        "warehouse.get()"
    )



from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


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
    confirmed = source.split("private Optional<List<ItemStack>> confirmedCacheContents(", 1)[1]
    confirmed = confirmed.split("@Override", 1)[0]
    assert "context.workspace().cacheContents(target)" in confirmed
    assert "counts.equals(snapshot.counts())" in confirmed

    scan = source.split("private void scan(int index,", 1)[1].split(
        "private void tracePackingSource", 1
    )[0]
    assert scan.index("confirmedCacheContents(context, source)") < scan.index(
        "read(source, contents ->"
    )
    assert "cacheSnapshots.put(source.position(), contents)" in scan
    assert "refreshCacheSnapshot(access);" in source
    refresh = source.split("private void refreshCacheSnapshot(", 1)[1].split(
        "final void read(", 1
    )[0]
    assert "recordCacheContents(context, target, contents)" in refresh

    work = source.split("private boolean queueSourceWork()", 1)[1].split(
        "private boolean prepareSource", 1
    )[0]
    assert work.index("confirmedCacheContents(context, currentSource)") < work.index(
        "readCache(currentSource, contents ->"
    )


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

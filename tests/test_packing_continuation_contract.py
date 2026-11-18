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


def test_preparation_uses_existing_box_room_and_repackable_loose_stock() -> None:
    source = (ROOT / "src/main/java/dev/stowling/client/automation/"
              "MinecraftStorageAutomation.java").read_text(encoding="utf-8")
    prepare = source.split("private final class PrepareBox", 1)[1].split(
        "private final class Packing", 1
    )[0]
    assert "RecordedStorageCapacity.existingBoxSpace(" in prepare
    assert "Math.max(0, sameLoose - limit), boxSpace, sameLoose" in prepare

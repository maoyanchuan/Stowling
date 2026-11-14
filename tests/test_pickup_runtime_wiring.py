"""Protect the child-pickup checkpoint lifetime without starting Minecraft."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "src/main/java/dev/stowling/client/StowlingRuntime.java"


def test_child_pickup_factory_allocates_checkpoints_per_engine() -> None:
    source = RUNTIME.read_text(encoding="utf-8")
    start = source.index("PickupWorkflowPort pickups = new ClientPickupWorkflowPort(")
    end = source.index("CraftingPort crafting =", start)
    wiring = source[start:end]
    factory_start = wiring.index("plan -> createPickupEngine(")
    factory_end = wiring.index("                ),", factory_start)
    factory = wiring[factory_start:factory_end]
    assert "new InMemoryAutomationCheckpointStore()" in factory
    assert "pickupCheckpoints" not in wiring

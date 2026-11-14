"""Keep explicit task actions wired without reintroducing shared pickup side effects."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/dev/stowling"


def source(relative_path: str) -> str:
    return (JAVA / relative_path).read_text(encoding="utf-8")


def test_real_packing_module_is_registered_with_client_ports() -> None:
    registry = source("automation/module/DefaultAutomationModuleRegistryFactory.java")
    runtime = source("client/StowlingRuntime.java")
    assert "new PackItemsAutomationModule(packing, configSupplier)" in registry
    assert "new ClientTaskPackingPort(packingPort, scheduler)" in runtime
    assert "() -> configLoader.load(configPath)" in runtime
    assert "PACK_ITEMS" in source("automation/AutomationStepType.java")


def test_completed_native_transfer_waits_for_menu_exit() -> None:
    interaction = source("client/interaction/MinecraftContainerInteraction.java")
    assert "transaction.completionResult = new ContainerAttemptResult(" in interaction
    assert "if (transaction.completionResult != null)" in interaction
    assert "beginSafeStop(token);" in interaction
    assert "return finishConfirmedTransfer(token, transaction);" in interaction
    assert "case CLEAN -> confirmed;" in interaction
    assert 'case UNSAFE -> unavailable(token, "confirmed transfer could not safely exit")' in (
        interaction
    )


def test_explicit_packing_success_requires_safe_stop() -> None:
    packing = source("automation/TickPackingPort.java")
    assert "state.completed = true;" in packing
    assert "StopOutcome outcome = requestStop(request.taskId());" in packing
    assert "case PENDING -> PackingDisposition.PENDING;" in packing
    assert "case UNSAFE -> PackingDisposition.UNAVAILABLE;" in packing


def test_region_script_is_served_by_http_static_routes() -> None:
    server = source("api/StowlingHttpServer.java")
    assert '"/coordinate-regions.js"' in server
    assert "stowling-web/coordinate-regions.js" in server


def test_coordinate_deletion_checks_pickup_interruption_actions() -> None:
    control = source("control/StowlingControlServiceImpl.java")
    assert "for (PickupWorkflow workflow : source.pickupWorkflows())" in control
    assert "workflow.settings().interruption().steps().stream()" in control
    for key in ("sourceCoordinateId", "destinationCoordinateId", "boxCoordinateId",
                "outputCoordinateId", "fallbackCoordinateId"):
        assert f'"{key}"' in control

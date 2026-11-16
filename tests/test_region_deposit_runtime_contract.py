from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PORT = (
    ROOT
    / "src/main/java/dev/stowling/client/automation/"
    / "MinecraftContainerTransferPort.java"
)


def port_source() -> str:
    return PORT.read_text(encoding="utf-8")


def method_source(source: str, start: str, end: str) -> str:
    return source.split(start, 1)[1].split(end, 1)[0]


def test_capacity_blocked_requested_stacks_end_attempt_successfully() -> None:
    ready = method_source(
        port_source(),
        "private ContainerTransferResult pollReady() {",
        "private ContainerTransferResult pollConfirmation() {",
    )

    candidate_missing = ready.split("if (candidate == null) {", 1)[1]
    assert "if (hasRequestedStack(menu, active.remaining)) {" in candidate_missing
    assert 'return succeed("当前容器容量不足，仍有物品待存入");' in candidate_missing
    assert 'return fail("当前容器没有可安全转移的完整物品堆");' in candidate_missing
    assert candidate_missing.index("hasRequestedStack") < candidate_missing.index(
        'return fail("当前容器没有可安全转移的完整物品堆");'
    )


def test_candidate_rules_are_shared_and_failures_are_not_swallowed() -> None:
    source = port_source()
    candidate = method_source(
        source,
        "private Candidate findCandidate(",
        "private static boolean hasRequestedStack(",
    )
    requested = method_source(
        source,
        "private static boolean hasRequestedStack(",
        "private static int destinationCapacity(",
    )

    assert "if (!matchesRequest(stack, remaining)) {" in candidate
    assert "destinationCapacity(menu, stack) < stack.getCount()" in candidate
    assert "matchesRequest(menu.getSlot(slotIndex).getItem(), remaining)" in requested
    assert "return wanted > 0 && stack.getCount() <= wanted;" in requested
    completion = method_source(
        source,
        "private ContainerTransferResult succeed(String message) {",
        "private ContainerTransferResult fail(String message) {",
    )
    failure = method_source(
        source,
        "private ContainerTransferResult fail(String message) {",
        "private Map<String, Integer> remaining() {",
    )
    assert "Map<String, Integer> remaining = remaining();" in completion
    assert "closeContainer();" in completion
    assert "active = null;" in completion
    assert "ContainerTransferResult.succeeded(message, remaining)" in completion
    assert "ContainerTransferResult.failed(message, remaining)" in failure
    assert "catch (" not in completion + failure

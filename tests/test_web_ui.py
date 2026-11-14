from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
HTML = (WEB_ROOT / "index.html").read_text(encoding="utf-8")
SCRIPT = (WEB_ROOT / "app.js").read_text(encoding="utf-8")
STYLES = (WEB_ROOT / "styles.css").read_text(encoding="utf-8")


def test_pickup_editor_exposes_all_resource_sections() -> None:
    required_ids = {
        "pickup-config-panel",
        "pickup-config-form",
        "pickup-regions-list",
        "pickup-caches-list",
        "pickup-workflows-list",
        "pickup-config-save",
        "pickup-config-discard",
        "pickup-config-errors",
    }

    for element_id in required_ids:
        assert f'id="{element_id}"' in HTML


def test_editor_has_three_crud_entry_points_and_ordering_support() -> None:
    for element_id in (
        "add-region-button",
        "add-cache-button",
        "add-workflow-button",
    ):
        assert f'id="{element_id}"' in HTML

    assert "removePickupResource" in SCRIPT
    assert "movePickupResource" in SCRIPT
    assert "moveWorkflowBinding" in SCRIPT
    assert '.draggable = true' in SCRIPT
    assert 'addEventListener("drop"' in SCRIPT


def test_editor_reads_and_atomically_saves_pickup_configuration() -> None:
    assert 'requestJson("/v1/config/pickup")' in SCRIPT
    assert 'requestJson("/v1/config/pickup", {' in SCRIPT
    assert "revision: pickupConfigSnapshot.revision" in SCRIPT
    assert "pickupRegions: pickupConfigDraft.pickupRegions" in SCRIPT
    assert "cacheContainers: pickupConfigDraft.cacheContainers" in SCRIPT
    assert "pickupWorkflows: pickupConfigDraft.pickupWorkflows" in SCRIPT

    save_block = re.search(
        r'pickupConfigForm\.addEventListener\("submit"[\s\S]+?'
        r'pickupConfigDiscard\.addEventListener',
        SCRIPT,
    )
    assert save_block is not None
    assert "refreshPickupConfig(true)" in save_block.group(0)
    assert "refreshPickupWorkflows()" in save_block.group(0)


def test_pickup_start_uses_workflow_and_optional_region() -> None:
    assert 'id="workflow-select"' in HTML
    assert 'id="pickup-region-select"' in HTML
    assert 'const body = { workflowId };' in SCRIPT
    assert "body.regionId = regionId;" in SCRIPT
    assert 'requestJson("/v1/tasks/pickup"' in SCRIPT
    assert "siteId" not in SCRIPT


def test_client_side_validation_covers_required_chinese_errors() -> None:
    expected_messages = (
        "重复 ID",
        "显示名称不能为空",
        "跨维度",
        "至少选择一个区域",
        "至少选择一个缓存",
        "第一角点不能大于第二角点",
        "半径必须为 1-128",
        "被流程",
    )

    for message in expected_messages:
        assert message in SCRIPT

    assert "active_task_conflict" in SCRIPT
    assert "活动或暂停任务存在" in SCRIPT


def test_user_content_uses_dom_apis_instead_of_html_interpolation() -> None:
    assert "innerHTML" not in SCRIPT
    assert ".textContent" in SCRIPT
    assert ".replaceChildren" in SCRIPT
    assert "sessionStorage.setItem(TOKEN_KEY, token)" in SCRIPT
    assert "localStorage" not in SCRIPT


def test_task_cards_show_workflow_progress_fields() -> None:
    for field in ("workflowId", "currentRegionId", "round", "stage"):
        assert f"task.{field}" in SCRIPT


def test_layout_is_responsive_and_preserves_coordinate_scope() -> None:
    assert ".pickup-config-grid" in STYLES
    assert ".workflow-column" in STYLES
    assert "@media (max-width: 760px)" in STYLES
    assert "拾取区域与缓存请在上方" in HTML
    assert '["CONTAINER", "仓库与溢出容器"]' in SCRIPT
    assert "PICKUP_SITE_CENTER" not in SCRIPT

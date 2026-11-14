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
    assert "pickupRegions: pickupConfigForSave.pickupRegions" in SCRIPT
    assert "cacheContainers: pickupConfigForSave.cacheContainers" in SCRIPT
    assert "pickupWorkflows: pickupConfigForSave.pickupWorkflows" in SCRIPT

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
        "必须选择一个已保存的命名坐标",
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
    assert "命名坐标库" in HTML
    assert "基础坐标（仓库/溢出/装盒）" in HTML
    assert '["CONTAINER", "仓库与溢出容器"]' in SCRIPT
    assert "PICKUP_SITE_CENTER" not in SCRIPT


def test_navigation_exposes_independent_named_coordinate_page() -> None:
    required_ids = (
        "workspace-nav",
        "coordinate-library-nav",
        "coordinate-library-panel",
        "coordinate-library-title",
        "coordinates-nav",
    )
    for element_id in required_ids:
        assert f'id="{element_id}"' in HTML

    assert 'href="#coordinate-library-panel"' in HTML
    assert "命名坐标库" in HTML
    assert "基础坐标（仓库/溢出/装盒）" in HTML


def test_named_coordinates_support_crud_and_draft_ordering() -> None:
    assert 'id="add-coordinate-point-button"' in HTML
    assert "function addCoordinatePoint()" in SCRIPT
    assert "function removeCoordinatePoint(index)" in SCRIPT
    assert "function moveCoordinatePoint(index, offset)" in SCRIPT
    assert "function createCoordinatePointEditor(point, index)" in SCRIPT
    assert "card.draggable = true" in SCRIPT
    assert "coordinateLibraryDirty = true" in SCRIPT
    assert "coordinateLibraryDraft.coordinatePoints.push(point)" in SCRIPT
    assert "coordinateLibraryDraft.coordinatePoints.splice(index, 1)" in SCRIPT
    assert 'createTextField("显示名称", point.name' in SCRIPT
    assert 'createDimensionField(point.dimension' in SCRIPT
    assert 'for (const axis of ["x", "y", "z"])' in SCRIPT


def test_coordinate_page_saves_atomic_revision_snapshot_and_reads_back() -> None:
    save_block = re.search(
        r'coordinateLibraryForm\.addEventListener\("submit"[\s\S]+?'
        r'elements\.coordinateLibraryDiscard\.addEventListener',
        SCRIPT,
    )
    assert save_block is not None
    block = save_block.group(0)
    assert 'requestJson("/v1/config/coordinate-points"' in block
    assert 'method: "PUT"' in block
    assert "revision: coordinateLibrarySnapshot.revision" in block
    assert "coordinatePoints" in block
    assert "refreshCoordinateLibrary(true)" in block
    assert "error.status === 409" in block
    assert "error.status === 400" in block


def test_pickup_editors_reference_named_coordinates() -> None:
    for field in (
        "standbyCoordinateId",
        "centerCoordinateId",
        "firstCornerCoordinateId",
        "secondCornerCoordinateId",
        "coordinateId",
    ):
        assert field in SCRIPT

    assert "function createCoordinateSelectField(" in SCRIPT
    assert 'select.dataset.coordinateId = String(selectedId || "")' in SCRIPT
    assert "跨维度：坐标属于" in SCRIPT
    region_editor = re.search(
        r"function createRegionEditor\(region, index\) \{[\s\S]+?"
        r"function changeRegionShape",
        SCRIPT,
    )
    cache_editor = re.search(
        r"function createCacheEditor\(cache, index\) \{[\s\S]+?"
        r"function containerTypeOptions",
        SCRIPT,
    )
    assert region_editor is not None
    assert cache_editor is not None
    assert 'createPositionEditor("待机点"' not in region_editor.group(0)
    assert "region.standbyPosition" not in region_editor.group(0)
    assert "cache.position" not in cache_editor.group(0)


def test_referenced_coordinate_delete_is_blocked_with_resource_list() -> None:
    assert "function coordinatePointReferences(coordinateId)" in SCRIPT
    assert '`拾取区域“${region.name || region.id}”（${fields.join("、")}）`' in SCRIPT
    assert '`缓存容器“${cache.name || cache.id}”（坐标）`' in SCRIPT
    assert "仍被 ${references.join" in SCRIPT
    assert "请先到拾取配置中解除引用并保存" in SCRIPT
    assert "setCoordinateLibraryErrors([message])" in SCRIPT


def test_pickup_save_does_not_resend_named_coordinate_collection() -> None:
    save_block = re.search(
        r'pickupConfigForm\.addEventListener\("submit"[\s\S]+?'
        r'pickupConfigDiscard\.addEventListener',
        SCRIPT,
    )
    assert save_block is not None
    block = save_block.group(0)
    assert 'requestJson("/v1/config/pickup"' in block
    assert "pickupRegions: pickupConfigForSave.pickupRegions" in block
    assert "cacheContainers: pickupConfigForSave.cacheContainers" in block
    assert "pickupWorkflows: pickupConfigForSave.pickupWorkflows" in block
    assert "coordinatePoints" not in block


def test_pickup_save_hydrates_strict_legacy_positions_from_coordinates() -> None:
    hydrate_block = re.search(
        r"function hydratePickupConfigForSave\(\) \{[\s\S]+?"
        r"function requireSavedCoordinatePosition",
        SCRIPT,
    )
    assert hydrate_block is not None
    hydration = hydrate_block.group(0)
    assert "const requestDraft = cloneJson(pickupConfigDraft);" in hydration
    assert "region.standbyPosition = cloneJson(" in hydration
    assert "region.shape.center = cloneJson(" in hydration
    assert "region.shape.firstCorner = cloneJson(" in hydration
    assert "region.shape.secondCorner = cloneJson(" in hydration
    assert "cache.position = cloneJson(" in hydration
    assert "requireSavedCoordinatePosition(" in hydration
    assert "pickupConfigDraft.pickupRegions =" not in hydration
    assert "命名坐标不存在或无效" in SCRIPT

    save_block = re.search(
        r'pickupConfigForm\.addEventListener\("submit"[\s\S]+?'
        r'pickupConfigDiscard\.addEventListener',
        SCRIPT,
    )
    assert save_block is not None
    block = save_block.group(0)
    assert "pickupConfigForSave = hydratePickupConfigForSave();" in block
    assert "setPickupConfigErrors([error.message]);" in block
    assert "拾取配置引用不完整，暂未保存" in block

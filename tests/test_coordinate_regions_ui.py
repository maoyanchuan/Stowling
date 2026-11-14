from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
HTML = (WEB_ROOT / "coordinates.html").read_text(encoding="utf-8")
COORDINATES = (WEB_ROOT / "coordinates.js").read_text(encoding="utf-8")
REGIONS = (WEB_ROOT / "coordinate-regions.js").read_text(encoding="utf-8")
RESOURCES = (WEB_ROOT / "task-resources.js").read_text(encoding="utf-8")


def js_function(source: str, name: str) -> str:
    start = source.index(f"    function {name}(")
    end = source.find("\n    /**", start)
    return source[start:end] if end != -1 else source[start:]


def async_js_function(source: str, name: str) -> str:
    start = source.index(f"    async function {name}(")
    end = source.find("\n    /**", start)
    return source[start:end] if end != -1 else source[start:]


def test_coordinate_page_only_mounts_named_coordinates() -> None:
    assert 'src="/coordinates.js"' in HTML
    assert 'id="coordinate-form"' in HTML
    assert 'id="coordinate-list"' in HTML
    assert 'src="/coordinate-regions.js"' not in HTML
    assert 'id="region-title"' not in HTML
    assert 'id="coordinate-region-form"' not in HTML
    assert 'id="coordinate-region-list"' not in HTML
    assert 'id="add-coordinate-region-button"' not in HTML
    assert 'id="save-coordinate-region-button"' not in HTML
    assert 'id="discard-coordinate-region-button"' not in HTML
    assert "拾取区域" not in HTML
    assert "旧缓存配置会原样保留" not in HTML


def test_coordinate_save_refreshes_regions_and_usage() -> None:
    save = async_js_function(COORDINATES, "saveCoordinates")
    assert "window.StowlingCoordinateRegions" in save
    assert "refreshAfterCoordinates(saved)" in save
    assert 'document.addEventListener("stowling:coordinate-regions-changed"' in (
        COORDINATES
    )
    refresh = async_js_function(REGIONS, "refreshAfterCoordinates")
    assert 'Stowling.request("/v1/config/coordinate-points")' in refresh
    assert 'Stowling.request("/v1/config/task-resources")' in refresh
    assert "const preserveDraft = state.dirty;" in refresh
    assert "applyResourceSnapshot(resourceSnapshot, preserveDraft);" in refresh


def test_region_editor_uses_named_points_without_standby_controls() -> None:
    card = js_function(REGIONS, "createRegionCard")
    assert "区域维度" in card
    assert "范围形状" in card
    assert "球心命名点" in REGIONS
    assert "第一角点" in REGIONS
    assert "第二角点" in REGIONS
    assert 'region.shape?.type === "CUBOID"' in REGIONS
    assert "coordinate-region-id-" in REGIONS
    assert "coordinate-region-name-" in REGIONS
    assert "coordinate-region-center-" in REGIONS
    assert "coordinate-region-first-" in REGIONS
    assert "coordinate-region-radius-" in REGIONS
    assert "待机命名坐标" not in REGIONS
    assert "standbyCoordinateId" in REGIONS
    assert "任务按区域 ID 引用" in REGIONS


def test_cuboid_request_normalizes_positions_to_min_max() -> None:
    bounds = js_function(REGIONS, "boundsFor")
    assert 'for (const axis of ["x", "y", "z"])' in bounds
    assert "Math.min(firstValue, secondValue)" in bounds
    assert "Math.max(firstValue, secondValue)" in bounds
    request = js_function(REGIONS, "toRequestRegion")
    assert "const bounds = boundsFor(first, second);" in request
    assert "firstCornerCoordinateId: first.id" in request
    assert "secondCornerCoordinateId: second.id" in request
    assert "firstCorner: clonePosition(bounds.min)" in request
    assert "secondCorner: clonePosition(bounds.max)" in request
    assert "顺序不限" in REGIONS


def test_region_request_preserves_latest_legacy_caches_and_compat_fields() -> None:
    request = js_function(REGIONS, "regionRequest")
    assert "revision: state.resourceSnapshot.revision" in request
    assert "pickupRegions," in request
    assert "cacheContainers: Stowling.clone(" in request
    assert "state.resourceSnapshot.cacheContainers" in request
    assert "/v1/config/task-resources" in REGIONS
    assert 'method: "PUT"' in REGIONS
    assert "standbyCoordinateId: standbyPoint.id" in REGIONS
    assert "standbyPosition: clonePosition(standbyPoint.position)" in REGIONS
    assert "standbyPoint = first;" in REGIONS
    assert "standbyPoint = center;" in REGIONS
    assert 'Stowling.request("/v1/config/coordinates")' not in REGIONS


def test_region_deletion_checks_legacy_and_task_references() -> None:
    remove = js_function(REGIONS, "removeRegion")
    assert "referencesFor(region._originalId || region.id)" in remove
    assert "区域仍被引用，暂未删除" in remove
    validation = js_function(REGIONS, "validateDraft")
    assert "state.resourceSnapshot.pickupRegions" in validation
    assert "仍被" in validation
    assert "task.settings?.pickup?.regions" in REGIONS
    assert "step.parameters?.regionId" in REGIONS
    assert "workflow.regions" in REGIONS
    assert "旧拾取流程" in REGIONS
    assert "保存区域" not in HTML


def test_task_resource_facade_is_dom_free_and_omits_base_coordinates() -> None:
    assert "document." not in RESOURCES
    assert "addEventListener" not in RESOURCES
    assert "/v1/config/coordinates" not in RESOURCES
    assert '"/v1/config/coordinate-points"' in RESOURCES
    assert '"/v1/config/task-resources"' in RESOURCES
    assert "async function load()" in RESOURCES
    assert "function getSnapshot()" in RESOURCES
    assert "function onChanged(handler)" in RESOURCES
    assert "window.StowlingResources = Object.freeze({" in RESOURCES
    registration = RESOURCES.rsplit("window.StowlingResources", 1)[1]
    assert "getSnapshot," in registration
    assert "load," in registration
    assert "onChanged" in registration
    assert "baseCoordinates" not in RESOURCES


def test_region_save_keeps_draft_on_local_revision_conflict() -> None:
    save = async_js_function(REGIONS, "saveRegions")
    assert 'Stowling.request("/v1/config/task-resources")' in save
    assert 'String(latest.revision || "") !== body.revision' in save
    assert 'code: "config_revision_conflict"' in save
    assert "body.cacheContainers = Stowling.clone(latest.cacheContainers || []);" in save
    conflict = js_function(REGIONS, "isConflict")
    assert "error?.status === 409" in conflict


def test_coordinate_references_include_single_and_multiple_container_coordinates() -> None:
    assert 'key === "coordinateId"' in COORDINATES
    assert 'key.endsWith("CoordinateId")' in COORDINATES


def test_existing_region_keeps_legacy_standby_behavior_when_saving() -> None:
    request = js_function(REGIONS, "toRequestRegion")
    assert "state.resourceSnapshot.pickupRegions.find" in request
    assert "entry.id === (region._originalId || region.id)" in request
    assert "original.dimension === standbyPoint.dimension" in request
    assert "original.standbyCoordinateId," in request

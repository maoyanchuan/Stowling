"""Integrated coordinate range editor regressions."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
HTML = (WEB_ROOT / "coordinates.html").read_text(encoding="utf-8")
COORDINATES = (WEB_ROOT / "coordinates.js").read_text(encoding="utf-8")


def js_function(source: str, name: str) -> str:
    start = source.index(f"    function {name}(")
    end = source.find("\n    /**", start)
    return source[start:end] if end != -1 else source[start:]


def async_js_function(source: str, name: str) -> str:
    start = source.index(f"    async function {name}(")
    end = source.find("\n    /**", start)
    return source[start:end] if end != -1 else source[start:]


def test_coordinate_page_mounts_only_the_integrated_editor() -> None:
    assert 'src="/coordinates.js"' in HTML
    assert 'id="coordinate-form"' in HTML
    assert 'id="coordinate-list"' in HTML
    assert 'src="/coordinate-regions.js"' not in HTML
    for element_id in (
        "region-title",
        "coordinate-region-form",
        "coordinate-region-list",
        "add-coordinate-region-button",
        "save-coordinate-region-button",
        "discard-coordinate-region-button",
    ):
        assert f'id="{element_id}"' not in HTML
    assert "拾取区域" not in HTML
    assert "第二角点允许反向输入" in HTML


def test_coordinate_editor_builds_range_controls_in_each_card() -> None:
    card = js_function(COORDINATES, "createPointCard")
    assert "pointTypeSelect(point)" in card
    assert "pointCategorySelect(point)" in card
    assert "radiusEditor(point, index)" in card
    assert "secondCornerEditor(point, index)" in card
    assert "positionLegend(point.type)" in card
    assert "coordinate-category-" in COORDINATES
    assert "coordinate-type-" in COORDINATES
    assert "coordinate-second-" in COORDINATES
    assert "仅坐标点" in COORDINATES
    assert "立方体半径" in COORDINATES
    assert "球半径" in COORDINATES
    assert "区域第一角点" in COORDINATES


def test_coordinate_save_uses_integrated_fields_without_region_bridge() -> None:
    save = async_js_function(COORDINATES, "saveCoordinates")
    request = js_function(COORDINATES, "toRequestPoint")
    assert "state.draft.coordinatePoints.map(toRequestPoint)" in save
    assert "await refreshSavedReferences();" in save
    assert "window.StowlingCoordinateRegions" not in COORDINATES
    assert "refreshAfterCoordinates" not in COORDINATES
    assert "stowling:coordinate-regions-changed" not in COORDINATES
    assert "type" in request
    assert "requestPoint.radius = Number(point.radius);" in request
    assert "requestPoint.secondCorner =" in request


def test_revision_conflict_is_handled_from_the_save_response() -> None:
    save = async_js_function(COORDINATES, "saveCoordinates")
    assert 'Stowling.request("/v1/config/coordinate-points")' in save
    assert "error instanceof Stowling.ApiError && error.status === 409" in save
    assert '"配置版本冲突或任务正在运行，草稿已保留"' in save
    assert "revision: state.snapshot.revision" in save


def test_immediate_delete_and_range_fields_share_request_serializer() -> None:
    remove = async_js_function(COORDINATES, "removePoint")
    assert "state.snapshot.coordinatePoints" in remove
    assert ".filter((item) => item.id !== savedId).map(toRequestPoint)" in remove
    assert "const remainingDraft = state.draft.coordinatePoints" in remove
    assert "remainingDraft.map(toRequestPoint)" in remove
    assert "renderSnapshot(saved);" in remove


def test_coordinate_references_include_single_and_multiple_ids() -> None:
    assert 'key === "coordinateId"' in COORDINATES
    assert 'key.endsWith("CoordinateId")' in COORDINATES
    assert 'key === "coordinateIds"' in COORDINATES
    assert 'key.endsWith("CoordinateIds")' in COORDINATES

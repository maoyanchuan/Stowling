import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"
TAB_EVENT = 'const TAB_EVENT = "stowling:resource-tab-change"'
DIRTY_STATUS = "有未保存修改，已同步版本"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_top_navigation_has_single_coordinate_entry() -> None:
    common = read("common.js")
    nav_items = common.split("const NAV_ITEMS", 1)[1].split("]);", 1)[0]
    coordinate_entry = (
        '{ page: "coordinates", href: "/coordinates.html", label: "坐标" }'
    )

    assert coordinate_entry in nav_items
    assert "/coordinates.html#coordinate-points" not in nav_items
    assert "/coordinates.html#regions" not in nav_items
    assert "/category-zones.html" not in nav_items
    assert "children" not in nav_items


def test_coordinate_page_uses_template_sidebar_and_real_creation_types() -> None:
    html = read("coordinates.html")
    assert html.count('id="coordinate-list"') == 1
    assert 'aria-label="坐标列表"' in html
    assert 'role="tablist"' not in html
    create = html.split('id="coordinate-kind"', 1)[1].split("</select>", 1)[0]
    for label in ("坐标点", "区域", "分类区"):
        assert f'>{label}</option>' in create


def test_inactive_editor_fields_use_hidden_attribute() -> None:
    html = read("coordinates.html")
    for panel in ("region-fields", "category-zone-fields"):
        assert re.search(rf'id="{panel}"[^>]*\bhidden\b', html)


def test_selecting_a_resource_opens_template_editor_and_old_links_keep_their_type() -> None:
    editor = read("coordinate-editor.js")
    assert "state.selectedId = coordinate.id" in editor
    assert "populateEditor(coordinate)" in editor
    assert 'window.addEventListener("hashchange"' in editor
    for anchor in ("#coordinate-points", "#regions", "#category-zones"):
        assert anchor in editor


def test_directory_updates_preserve_dirty_editor_dom() -> None:
    editor = read("coordinate-editor.js")
    assert "if (!dirty) populateEditor(coordinate)" in editor
    assert "await loadDirectory(false)" in editor
    assert "const drafts = coordinates.filter(item => !item.saved)" in editor
    assert "当前草稿已保留" in editor


def test_category_script_avoids_duplicate_mount_when_embedded() -> None:
    javascript = read("category-zones.js")
    embedded_check = (
        'const embedded = document.body.dataset.page === "coordinates"'
    )

    assert embedded_check in javascript
    assert "if (!embedded)" in javascript
    assert 'Stowling.mountPage("category-zones")' in javascript


def test_legacy_category_zone_url_redirects_to_embedded_tab() -> None:
    html = read("category-zones.html")

    assert "url=/coordinates.html#category-zones" in html
    assert "window.location.replace" not in html
    assert "<script>" not in html

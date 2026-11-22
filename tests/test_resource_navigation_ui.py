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


def test_coordinate_page_uses_one_sidebar_and_three_creation_types() -> None:
    html = read("coordinates.html")
    assert html.count('id="coordinate-bookmarks"') == 1
    assert 'aria-label="坐标和文件夹"' in html
    assert 'id="coordinate-folder-filter"' not in html
    assert 'role="tablist"' not in html
    create = html.split('id="coordinate-resource-kind"', 1)[1].split("</select>", 1)[0]
    assert '>坐标点</option>' in create
    assert '>区域</option>' in create
    assert '>储存区</option>' in create
    assert '>分类区</option>' not in create
    assert 'id="coordinate-area-kind"' in html
    assert '<option value="category-zones">分类区</option>' in html


def test_inactive_resource_panels_use_hidden_attribute() -> None:
    html = read("coordinates.html")
    for panel in ("coordinate-points", "regions", "category-zones", "warehouses"):
        assert re.search(rf'id="{panel}"[^>]*\bhidden\b', html)


def test_selecting_a_bookmark_opens_its_editor_and_old_links_keep_their_type() -> None:
    browser = read("resource-browser.js")
    editor = read("coordinates.js")
    assert 'new CustomEvent("stowling:coordinate-panel-open"' in browser
    assert 'document.addEventListener("stowling:coordinate-panel-open"' in editor
    assert "panel.hidden = panel.id !== panelId" in editor
    assert 'window.addEventListener("hashchange"' in browser
    assert "window.location.hash.slice(1)" in browser


def test_tab_refresh_preserves_dirty_editor_dom() -> None:
    coordinates = read("coordinates.js")
    categories = read("category-zones.js")

    assert TAB_EVENT in coordinates
    assert TAB_EVENT in categories
    assert 'card.dataset.dirty = "true"' in coordinates
    assert 'card.dataset.dirty = "true"' in categories
    assert "hasDirtyEditor(elements.list)" in coordinates
    assert "hasDirtyEditor(elements.regionList)" in coordinates
    assert "hasDirtyZone(elements.list)" in categories
    assert "loadCoordinatePoints(true)" in coordinates
    assert "loadRegions(true)" in coordinates
    assert "loadAll(true)" in categories
    assert DIRTY_STATUS in coordinates
    assert DIRTY_STATUS in categories


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
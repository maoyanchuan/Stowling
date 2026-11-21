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


def test_coordinate_page_embeds_four_resource_tabs() -> None:
    html = read("coordinates.html")
    tab_class = 'class="page-subnav-tab"'
    panel_class = 'class="resource-tab-panel resource-list-section"'

    assert 'class="page-subnav"' in html
    assert html.count(tab_class) == 4
    assert html.count(panel_class) == 3
    assert 'id="coordinate-points"' in html
    assert 'id="regions"' in html
    assert 'id="category-zones"' in html
    assert 'id="facilities"' in html
    assert 'href="#coordinate-points"' in html
    assert 'href="#regions"' in html
    assert 'href="#category-zones"' in html
    assert 'src="/category-zone-model.js"' in html
    assert 'src="/category-zones.js"' in html


def test_inactive_resource_panels_use_hidden_attribute() -> None:
    html = read("coordinates.html")

    assert re.search(r'id="coordinate-points"[^>]*>', html)
    assert re.search(r'id="regions"[^>]*\bhidden\b', html)
    assert re.search(r'id="category-zones"[^>]*\bhidden\b', html)
    assert 'aria-selected="true"' in html
    assert 'aria-selected="false"' in html


def test_each_resource_panel_owns_actions_and_search() -> None:
    html = read("coordinates.html")
    points = html.split('id="coordinate-points"', 1)[1]
    points = points.split('id="regions"', 1)[0]
    regions = html.split('id="regions"', 1)[1]
    regions = regions.split('id="category-zones"', 1)[0]
    categories = html.split('id="category-zones"', 1)[1]

    assert 'id="add-coordinate-button"' in points
    assert 'id="resource-search"' in points
    assert 'id="add-region-button"' in regions
    assert 'id="region-resource-search"' in regions
    assert 'id="add-category-zone-button"' in categories
    assert 'id="category-zone-search"' in categories
    assert 'id="add-region-button"' not in points
    assert 'id="add-category-zone-button"' not in regions


def test_tabs_toggle_hidden_and_aria_selected_from_hash() -> None:
    javascript = read("coordinates.js")

    assert 'window.addEventListener("hashchange", activateHashTab)' in javascript
    assert 'tab.setAttribute("aria-selected", String(selected))' in javascript
    assert "panel.hidden = !selected" in javascript
    assert "scrollIntoView" not in javascript


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
    assert 'id="new-category-zone-form"' not in html
    assert '<script src=' not in html
    assert "window.location.replace" not in html
    assert "<script>" not in html
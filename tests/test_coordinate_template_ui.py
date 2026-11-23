from pathlib import Path


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
STANDALONE = Path(__file__).resolve().parents[1] / "docs/ui/coordinates-template.html"
SERVER = Path(__file__).resolve().parents[1] / (
    "src/main/java/dev/stowling/v2/api/V2ControlServer.java"
)


def test_coordinate_template_is_a_static_preview_without_live_page_scripts() -> None:
    html = (WEB / "coordinates-template.html").read_text(encoding="utf-8")

    assert 'data-template="coordinates"' in html
    assert "坐标管理模板" in html
    assert 'href="/coordinates-template.css"' in html
    assert 'src="/coordinates-template.js"' in html
    assert 'src="/common.js"' not in html
    assert 'src="/coordinates.js"' not in html
    assert 'id="coordinate-bookmarks"' in html
    assert 'id="template-coordinate-name"' in html


def test_coordinate_template_route_and_assets_are_served() -> None:
    source = SERVER.read_text(encoding="utf-8")

    assert '"/coordinates-template.html"' in source
    assert '"/coordinates-template.js"' in source
    assert '"/coordinates-template.css"' in source


def test_coordinate_template_can_open_directly_from_the_repository() -> None:
    html = STANDALONE.read_text(encoding="utf-8")

    assert '../../src/main/resources/stowling-web/styles.css' in html
    assert '../../src/main/resources/stowling-web/coordinates-template.js' in html
    assert '../../src/main/resources/assets/stowling/icon.png' in html
    assert 'src="/coordinates-template.js"' not in html


def test_coordinate_template_directory_stays_flat_and_creates_category_zones() -> None:
    html = (WEB / "coordinates-template.html").read_text(encoding="utf-8")

    assert 'coordinate-bookmark-name">我的坐标' not in html
    assert 'data-panel="category-zones"' not in html
    assert 'option value="category-zones">分类区</option>' in html
    assert 'class="coordinate-bookmark-folder"' in html
    assert 'data-resource-ref="coordinate:main-base"' in html
    assert 'draggable="true"' in html


def test_coordinate_template_exposes_folder_drop_interaction() -> None:
    script = (WEB / "coordinates-template.js").read_text(encoding="utf-8")

    assert 'dragstart' in script
    assert 'drop' in script
    assert 'children.append(draggedRow)' in script
    assert '已将“${name.trim()}”移入文件夹' in script

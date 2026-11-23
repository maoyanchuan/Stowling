from pathlib import Path


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
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

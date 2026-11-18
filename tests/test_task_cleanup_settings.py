import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_task_destination_and_resume_choices_round_trip_and_classification_restarts() -> None:
    runner = r"""
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
    /** @returns {void} */
    constructor() {
        this.children = []; this.value = ''; this.listeners = {};
        this.classList = {/** @returns {void} */ add() {}};
    }
    /** @param {string} name @param {Function} callback @returns {void} */
    addEventListener(name, callback) { this.listeners[name] = callback; }
    /** @returns {void} */
    setAttribute() {}
    /** @param {...Element} children @returns {void} */
    append(...children) { this.children.push(...children); }
    /** @param {...Element} children @returns {void} */
    replaceChildren(...children) { this.children = children; }
    /** @returns {Array<Element>} */
    get options() { return this.children; }
}
globalThis.document = {
    /** @returns {Element} */ createElement() { return new Element(); },
    /** @returns {void} */ addEventListener() {}
};
globalThis.Option = class extends Element {
    /** @param {string} text @param {string} value */
    constructor(text, value) { super(); this.textContent = text; this.value = value; }
};
globalThis.window = {Stowling: {/** @returns {Element} */ element() { return new Element(); }}};
vm.runInThisContext(fs.readFileSync(process.argv[2], 'utf8'));
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
const widget = window.StowlingTaskCleanupSettings.create(new Element());
widget.resources([{id:'box', name:'Delivery'}], [{id:'area', name:'Cache'}]);
widget.set({destinationRef:'coordinate:box', resumeMode:'CONTINUE'});
const coordinate = widget.read();
widget.set({destinationRef:'region:area', resumeMode:'RESTART'});
const region = widget.read();
const automatic = window.StowlingTaskCleanupSettings.create(new Element(), {allowContinue:false});
automatic.resources([], [{id:'area', name:'Cache'}]);
automatic.set({destinationRef:'region:area', resumeMode:'CONTINUE'});
process.stdout.write(JSON.stringify({coordinate, region, automatic: automatic.read()}));
"""
    result = subprocess.run(
        [str(NODE), "-e", runner, str(WEB / "task-cleanup-settings.js"),
         str(WEB / "settings-ui.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    value = json.loads(result.stdout)
    assert value["coordinate"] == {"destinationRef": "coordinate:box", "resumeMode": "CONTINUE"}
    assert value["region"] == {"destinationRef": "region:area", "resumeMode": "RESTART"}
    assert value["automatic"] == {"destinationRef": "region:area", "resumeMode": "RESTART"}


def test_task_page_loads_shared_cleanup_editor_for_both_task_types() -> None:
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    assert 'src="/task-cleanup-settings.js"' in html
    assert html.index('/task-cleanup-settings.js') < html.index('/tasks.js')
    assert 'id="auto-cleanup-settings"' in html
    assert 'id="move-cleanup-settings"' in html

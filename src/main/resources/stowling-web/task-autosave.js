(() => {
    "use strict";

    let writing = false;
    const pending = new Set();

    window.addEventListener("beforeunload", (event) => {
        if (!writing && pending.size === 0) return;
        event.preventDefault();
        event.returnValue = true;
    });

    /**
     * @param {HTMLFormElement} form
     * @param {() => Promise<void>} save
     * @param {() => boolean} blocked
     * @returns {{schedule: () => void, flush: () => Promise<void>, cancel: () => void}}
     */
    function create(form, save, blocked) {
        let timer;

        /** @returns {void} */
        function cancel() {
            window.clearTimeout(timer);
            pending.delete(form);
        }

        /** @returns {void} */
        function schedule() {
            cancel();
            pending.add(form);
            timer = window.setTimeout(() => void flush(), 450);
        }

        /** @returns {Promise<void>} */
        async function flush() {
            cancel();
            if (writing || blocked()) {
                schedule();
                return;
            }
            const input = document.activeElement;
            const retainFocus = input && form.contains(input);
            const start = input?.selectionStart;
            const end = input?.selectionEnd;
            writing = true;
            try {
                await save();
            } finally {
                writing = false;
                if (retainFocus && input.isConnected && document.activeElement === document.body) {
                    input.focus({preventScroll: true});
                    if (typeof start === "number") input.setSelectionRange(start, end);
                }
            }
        }

        return {schedule, flush, cancel};
    }

    /**
     * @param {string} path
     * @param {string} method
     * @param {object} payload
     * @param {() => Promise<string>} [rebase]
     * @returns {Promise<object>}
     */
    async function write(path, method, payload, rebase) {
        /** @param {object} body @returns {Promise<object>} */
        const send = (body) => window.Stowling.request(path, {
            method,
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(body)
        });
        try {
            return await send(payload);
        } catch (error) {
            if (!rebase || error.status !== 409 || error.code !== "revision_conflict") throw error;
            return send({...payload, revision: await rebase()});
        }
    }

    /**
     * @param {string} endpoint
     * @param {object|null} original
     * @param {(response: object) => object} [find]
     * @returns {Promise<string>}
     */
    async function latestRevision(endpoint, original, find) {
        const latest = await window.Stowling.request(endpoint);
        if (find && JSON.stringify(find(latest)) !== JSON.stringify(original)) {
            throw new Error("此任务已在其他位置修改，请刷新后重新打开任务。");
        }
        return latest.revision;
    }

    window.StowlingTaskAutosave = Object.freeze({create, write, latestRevision});
})();

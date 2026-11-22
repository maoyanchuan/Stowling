try {
    if (window.localStorage.getItem("stowling.theme") === "gray") {
        document.documentElement.dataset.theme = "gray";
    }
} catch {
    // Keep the document's default theme when storage is blocked.
}

# Brand icon generation

`generate-brand-icon.ps1` validates the live `.brand-mark` declarations in
`styles.css`, verifies that `assets/stowling/icon.svg` uses the same dimensions, radius,
colors, gradient, text color, size, and weight, then renders the SVG to a transparent
512x512 PNG in headless Chrome or Edge.

Run from the repository root:

```powershell
./scripts/generate-brand-icon.ps1
```

Set `STOWLING_BROWSER` or pass `-BrowserPath` when Chrome or Edge is not installed in a
standard location. The script requires Node-free, package-free browser tooling and fails
if the browser output is not a 512x512 RGBA PNG.

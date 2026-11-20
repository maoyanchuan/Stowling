# Brand icon generation

`generate-brand-icon.ps1` renders `assets/stowling/icon.svg` to a transparent 512x512
PNG in headless Chrome or Edge. Fabric, the web console, and the repository READMEs
use the generated PNG.

Run from the repository root:

```powershell
./scripts/generate-brand-icon.ps1
```

Set `STOWLING_BROWSER` or pass `-BrowserPath` when Chrome or Edge is not installed in a
standard location. The script requires Node-free, package-free browser tooling and fails
if the browser output is not a 512x512 RGBA PNG.

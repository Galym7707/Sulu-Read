#!/usr/bin/env bash
# Deploy the web app. Stamps the service-worker cache version first: without a bump an
# installed PWA keeps its cached shell for one more launch, which strands a change whose files
# must move together (a new export in speech.js that app.js calls, say).
set -euo pipefail
cd "$(dirname "$0")"
STAMP="sulu-$(date -u +%Y%m%d-%H%M%S)"
sed -i "s/^const VERSION = \".*\";/const VERSION = \"$STAMP\";/" sw.js
echo "service-worker cache version -> $STAMP"
node --check sw.js
for f in app.js speech.js api.js focus.js icons.js strings.js; do node --check "$f"; done
node test_focus.js
vercel deploy --prod --yes 2>&1 | grep -oE "Aliased.*" | head -1

#!/bin/bash
# Stops the whole system. Data volumes are kept; pass -v to delete them too.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down "$@"

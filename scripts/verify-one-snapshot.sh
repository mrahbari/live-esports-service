#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://127.0.0.1:8080"

echo "==> Pass 1: Checking Meta and Count"
META=$(curl -s "$BASE_URL/v1/teams/live" | jq '.meta')
COUNT=$(echo "$META" | jq '.count')
FETCHED_AT=$(echo "$META" | jq -r '.fetchedAt')

if [ "$COUNT" -gt 0 ]; then
  echo "OK: Teams count is $COUNT (fetched at $FETCHED_AT)"
else
  echo "FAIL: Teams count is 0"
  exit 1
fi

echo "==> Pass 2: Verifying Team Names (No ID-as-name fallbacks)"
TEAMS=$(curl -s "$BASE_URL/v1/teams/live" | jq '.items[0:5]')
INVALID_TEAMS=$(echo "$TEAMS" | jq '[.[] | select(.name | contains("Team ") and contains(.teamId))]')

if [ "$INVALID_TEAMS" == "[]" ]; then
  echo "OK: Team names look real (no fallbacks found in first 5 items)"
else
  echo "WARN: Found potential fallback team names: $INVALID_TEAMS"
fi

echo "==> Pass 3: Verifying Players and Team Affiliation"
PLAYERS=$(curl -s "$BASE_URL/v1/players/live" | jq '.items[0:5]')
EMPTY_TEAM_NAMES=$(echo "$PLAYERS" | jq '[.[] | select(.teamName == null or .teamName == "")]')

if [ "$EMPTY_TEAM_NAMES" == "[]" ]; then
  echo "OK: Player team names are populated"
else
  echo "FAIL: Found players with empty team names: $EMPTY_TEAM_NAMES"
  exit 1
fi

echo "==> Pass 4: Atlas Calls Breakdown"
echo "$META" | jq '.atlasCalls'

echo "==> All automated checks passed!"

#!/usr/bin/env sh
set -eu
mvn -q clean test package
if grep -RInE 'TODO|FIXME|Implement later|Remaining code omitted|Add your logic here' src/main/java src/main/resources; then
  echo "Incomplete marker found." >&2
  exit 1
fi
echo "WildMare Market static checks passed."

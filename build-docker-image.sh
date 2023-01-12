#!/bin/bash
set -ex

version="8.5.4"
patchversion="7"

./gradlew --write-verification-metadata sha256 precommit
./gradlew localDistro
cd distribution/archives/linux-tar/build/install
tar -czf ../../../../../../dockerfiles/elasticsearch/elasticsearch-$version-linux-x86_64.tar.gz elasticsearch-$version-SNAPSHOT
cd ../../../../../../dockerfiles/elasticsearch
docker build --platform=amd64 -t gcr.io/prophecy-share/elasticsearch:$version-$patchversion .

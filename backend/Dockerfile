# Copyright 2026 Sriram (Sriram-Codes-SW)
#
# This file is part of Doorprints.
#
# Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
# Public License as published by the Free Software Foundation, version 3 of the License.
#
# Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
# warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
# the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only

# Build: docker build -t doorprints-api backend  (the Doorprints API)
FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
WORKDIR /app
# Take the distribution's security fixes that are newer than the base image (Trivy's HIGH gate, TC-S-05, failed on an
# OpenSSL fix published the day before the image was rebuilt); the lists are removed again so the layer stays small.
RUN apt-get update && apt-get -y --no-install-recommends upgrade && rm -rf /var/lib/apt/lists/*
# The Ubuntu 26.04 base layer (a Canonical rock) ships /usr/bin/pebble, a Go service manager nothing here runs (the
# ENTRYPOINT is java). Its Go standard library has three HIGH CVEs (CVE-2026-78667, CVE-2026-78669, CVE-2026-97031)
# whose fixes are not in any base image yet, so Trivy's gate (TC-S-05) fails. Drop the unused binary; remove this line
# when the base ships a pebble built with Go 1.26.9 or later (S4b-BL-195).
RUN rm -f /usr/bin/pebble
# Run as an unprivileged user (threat model F-23). Fixed UID so volume permissions are predictable.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
COPY --from=build --chown=10001:10001 /src/target/*.jar app.jar
USER 10001:10001
# Keeps memory low enough for free-tier hosts (512 MB-1 GB); exit (and let the platform restart) on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# The JRE image has no curl/wget, so probe with bash's /dev/tcp. /actuator/health is public and rate-limit exempt.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

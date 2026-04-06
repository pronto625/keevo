# ─── Flutter Android APK Builder ──────────────────────────────────────────────
#
# PURPOSE  : Produces a release APK inside a hermetic container.
#            The built artefact is written to /output/keevo.apk.
#
# USAGE
#   Build the image (first time / after pubspec changes):
#     docker build -t keevo-flutter-builder ./app
#
#   Run to get the APK:
#     docker run --rm \
#       -v "$(pwd)/output:/output" \
#       keevo-flutter-builder
#
#   With release signing (optional — see android/key.properties.example):
#     docker run --rm \
#       -v "$(pwd)/output:/output" \
#       -v "$(pwd)/keevo-release.keystore:/app/android/app/keevo-release.keystore:ro" \
#       -e ANDROID_STORE_PASSWORD=secret \
#       -e ANDROID_KEY_ALIAS=upload \
#       -e ANDROID_KEY_PASSWORD=secret \
#       keevo-flutter-builder
#
# NOTE: iOS builds CANNOT run in Docker — they require macOS + Xcode.
#       iOS is handled by the GitHub Actions workflow (macos-latest runner).
# ──────────────────────────────────────────────────────────────────────────────

FROM ubuntu:22.04

# ─── Build-time arguments ─────────────────────────────────────────────────────
ARG FLUTTER_VERSION=3.41.0
ARG ANDROID_CMDTOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
ARG ANDROID_PLATFORM_VERSION=34
ARG ANDROID_BUILD_TOOLS_VERSION=34.0.0

# ─── Environment ──────────────────────────────────────────────────────────────
ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_HOME=/opt/android-sdk \
    FLUTTER_HOME=/opt/flutter \
    PUB_CACHE=/root/.pub-cache

ENV PATH="${FLUTTER_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${JAVA_HOME}/bin:${PATH}"

# ─── System dependencies ──────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        git \
        unzip \
        xz-utils \
        wget \
        openjdk-17-jdk \
        # Android emulator / build dependencies
        lib32stdc++6 \
        libglu1-mesa \
    && rm -rf /var/lib/apt/lists/*

# ─── Android SDK command-line tools ───────────────────────────────────────────
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && wget -q "${ANDROID_CMDTOOLS_URL}" -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/ct \
    && mv /tmp/ct/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/ct /tmp/cmdline-tools.zip

# Accept licences once, then install platform + build-tools
RUN yes | sdkmanager --licenses > /dev/null 2>&1; \
    sdkmanager \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM_VERSION}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}"

# ─── Flutter SDK ──────────────────────────────────────────────────────────────
RUN git clone --branch "${FLUTTER_VERSION}" --depth 1 \
        https://github.com/flutter/flutter.git "${FLUTTER_HOME}"

# Pre-heat Flutter caches and disable analytics/telemetry
RUN flutter precache --android \
    && flutter config --no-analytics \
    && flutter doctor --android-licenses

# ─── App source ───────────────────────────────────────────────────────────────
WORKDIR /app
COPY . .

# ─── Dependencies + code generation ───────────────────────────────────────────
RUN flutter pub get \
    && flutter pub run build_runner build --delete-conflicting-outputs

# ─── Release APK build ────────────────────────────────────────────────────────
# key.properties is read by android/app/build.gradle.kts at build time.
# If no keystore is mounted, signing falls back to debug keys automatically.
RUN flutter build apk --release

# ─── Output ───────────────────────────────────────────────────────────────────
RUN mkdir -p /output \
    && cp build/app/outputs/flutter-apk/app-release.apk /output/keevo.apk

CMD ["echo", "Build complete — APK is at /output/keevo.apk"]

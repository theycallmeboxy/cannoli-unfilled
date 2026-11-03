# The build container for cannoli-unfilled: JDK + Android SDK for Gradle builds.
# Apply with `boxen env apply` (from the project folder). Only the project
# folder and /root/.cache survive a rebuild, so the Gradle cache lives there.
FROM boxen-env:latest

RUN apt-get update \
 && apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH
ENV GRADLE_USER_HOME=/root/.cache/gradle
# Keeps the debug signing key across container rebuilds, so debug builds install over each other.
ENV ANDROID_USER_HOME=/root/.cache/android-user

RUN mkdir -p /opt/android-sdk/cmdline-tools \
 && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/clt.zip \
 && unzip -q /tmp/clt.zip -d /opt/android-sdk/cmdline-tools \
 && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm /tmp/clt.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platform-tools" "platforms;android-36.1" "platforms;android-36" "build-tools;36.0.0"

# bun builds the Kitchen web page (CannoliHQ/kitchen) into app/src/main/assets/kitchen.
RUN curl -fsSL https://bun.sh/install | BUN_INSTALL=/opt/bun bash
ENV PATH=/opt/bun/bin:$PATH

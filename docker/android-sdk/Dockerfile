# Android SDK 빌드 이미지 (Actions 러너용).
# cirruslabs/android-sdk(Android SDK/NDK 포함)에 GitHub Actions 스타일 액션이
# 요구하는 Node 20을 추가한다. Synology 러너에서 빌드·사용:
#   docker build -t synology.local/android-sdk:36 .
FROM ghcr.io/cirruslabs/android-sdk:36

# checkout/setup-java 등 node 기반 액션 실행용 Node 20
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

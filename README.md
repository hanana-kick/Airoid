# Airoid

Android용 AirPlay 수신기. 맥/아이폰의 화면 미러링을 안드로이드 기기를 보조 화면으로
받아 표시하고, AirPlay 오디오를 수신한다. 수신 엔진은 [UxPlay](app/src/main/cpp/third_party/UxPlay)를 기반으로 한다.

## 주요 기능

- **화면 미러링** — H.264/H.265 하드웨어 디코딩, 저지연 파이프라인
- **오디오 수신** — AAC(하드웨어), ALAC(하드웨어 부재 시 ffmpeg 소프트웨어 폴백)
- **전환 애니메이션** — 연결/종료 시 기기 프레임(링)이 커지고 줄어들며 영상이 페이드인/아웃
- **빠른 설정 타일** — 미러링 상태 표시(ACTIVE/INACTIVE) + 탭하면 앱 실행
- **다국어** — 한국어/English/日本語/中文(간체), Android per-app 언어(앱 언어) 지원
- **페어링 코드** — 언어별 문학 작품 제목(원어 표기), 앱 재시작 시 유지, 언어 변경 시 갱신
- **화면 켜짐 유지** — 미러링 중 앱이 포그라운드일 때만

## 요구사항

- Android SDK + **NDK 29** (`ndkVersion = "29.0.14206865"` — 라이선스가 승인되면 자동 다운로드)
- **JDK 17 또는 21** — Gradle 8.11.1 내장 Kotlin 컴파일러가 Java 25를 지원하지 않는다
  (Android Studio JBR(Java 25)로는 빌드 실패. `~/.gradle/jdks`의 Zulu 21 또는 Homebrew openjdk@17 사용)
- 첫 빌드는 ffmpeg 소스 빌드(`cmake/BuildFFmpeg.cmake` — ALAC 디코더만 최소 구성)를 포함한다

## 빌드·설치

```bash
export JAVA_HOME=<JDK 17/21 경로>
./gradlew :app:installDebug
```

- 여러 기기가 연결된 경우 설치 대상을 지정한다:
  ```bash
  export ANDROID_SERIAL=<adb serial>
  ./gradlew :app:installDebug
  ```
- 네이티브 빌드 시간을 줄이려면 ABI를 제한한다 (실기기는 arm64):
  ```bash
  ./gradlew :app:installDebug -Pandroid.injected.build.abi=arm64-v8a
  ```

## 사용법

1. 앱을 실행하면 대기 화면에 페어링 코드가 표시된다 (예: `Airoid 태백산맥`).
2. 맥의 AirPlay 메뉴에서 기기 이름을 선택하면 미러링이 시작된다.
3. 미러링 중 위로 스와이프하면 "미러링 종료" 패널이 열린다.
4. (선택) 빠른 설정 편집에서 "Airoid" 타일을 추가하면 미러링 상태를 보고, 탭으로 앱을
   실행할 수 있다.

## 구조

```
app/src/main/java/com/airoid/
├── MainActivity.kt            # 미러링/스탠바이 UI + 전환 애니메이션
├── StandbyScreen.kt           # 대기 화면(코드) + 종료 패널
├── QuickSettingsTileService.kt# 빠른 설정 타일
├── renderer/                  # MediaCodec + GL 파이프라인 (영상/오디오)
├── service/AirPlayService.kt  # 수신 서버 수명주기, 콜백 라우팅
└── bridge/                    # JNI 바인딩

app/src/main/cpp/              # UxPlay 기반 네이티브 수신 엔진 (+ffmpeg, libplist, oboe)
```

## 라이선스

서드파티 구성요소(UxPlay, FFmpeg, libplist, openssl, oboe)는 각각의 라이선스를 따른다.

# Airoid 구현 방안 — AirPlay 수신기 (맥 보조 모니터)

목표: 안드로이드 기기를 **AirPlay 수신기**로 만들어 맥의 **보조 모니터**(화면 미러링)로 쓰고, 부수적으로 오디오/영상 재생을 받는 앱.

## 1. 핵심 결정: AirPlay 프로토콜은 직접 구현하지 않고 UxPlay(C, GPL-3.0) 엔진을 JNI로 이식

AirPlay 2는 문서화되지 않은 프로토콜(RTSP 제어 + Pairing/FairPlay 인증 + RAOP 오디오 + 화면 미러링 H.264/H.265 + HLS)으로, 처음부터 구현하면 수 개월의 역설계와 높은 실패 리스크가 있다.

**UxPlay**(`FDH2/UxPlay`, ~2.9k star)는 이미 AirPlay 2 미러 + 오디오를 완성한 성숙한 C 엔진이고, Android 이식이 **검증된 구현**이 존재한다:
- `jqssun/android-airplay-server` — Android/Android TV용 AirPlay 2 수신기, UxPlay 기반
- `medjakani/mirroring` — 동일 아키텍처 (UxPlay + JNI)

이 두 구현은 "UxPlay C 엔진 + JNI 브리지 + Android MediaCodec/AudioTrack 렌더러 + NsdManager mDNS + 포그라운드 서비스 + Compose UI"라는 정확히 우리가 원하는 구조를 증명한다. 우리의 작업은 프로토콜 역설계가 아니라 **Android 통합 레이어**(NDK 빌드, JNI, MediaCodec 렌더링, mDNS 등록, 서비스, Compose UI)에 집중하게 된다.

### 라이선스 (중요)
UxPlay는 **GPL-3.0** (강한 카플렙트). 이식 시 전체 앱이 GPL-3.0으로 공개되어야 한다. 개인 프로젝트로는 문제없지만, 폐쇄/상업 배포를 계획 중이면 재검토 필요.
- 대안(권장 안 함): 프로토콜 자체 구현 — 비용/리스크가 훨씬 큼. (상세: §7)

## 2. 아키텍처

```
Mac (Sender)
   │  AirPlay 2: mDNS 발견 → RTSP 제어 + Pairing/FairPlay → RAOP(H.264/H.265 + AAC/ALAC)
   ▼
Android (Receiver, 이 앱)
   ├─ NsdServiceManager  : mDNS 광고 (_raop._tcp + _airplay._tcp) + multicast lock
   ├─ UxPlay (C/JNI)     : RTSP 서버, Pairing/FairPlay, RAOP 디코딩·역다중화, TXT 레코드
   │     └─ native_bridge.cpp  : raop_init/start_httpd/stop/destroy + 콜백
   ├─ VideoRenderer      : MediaCodec H.264/H.265 디코드 → SurfaceView/TextureView
   ├─ AudioRenderer      : MediaCodec/AudioTrack AAC-ELD/LC, ALAC
   ├─ AirPlayService     : 포그라운드 서비스 — 네이티브 핸들 수명주기, wake lock, 알림
   └─ Compose UI         : 상태/설정/로그 화면
```

## 3. 모듈 구성 (클린 컷오버)

### A. 네이티브 레이어 `app/src/main/cpp/`
- `third_party/UxPlay` — git submodule로 vendor (CMake FetchContent 대신 submodule: 참조 구현과 동일, 패치 적용 용이)
- `native_bridge.cpp` — JNI: `nativeInit/nativeStart/nativeStop/nativeDestroy/nativeSetDisplaySize/nativeGet*TxtRecords/nativeSetH265Enabled` (참조 구현의 `NativeBridge` 시그니처 그대로 채택)
- `android_dnssd_shim.c` — UxPlay의 `dnssd.c`를 대체하는 shim. **실제 mDNS 등록은 Kotlin(NsdManager)이 맡고**, shim은 TXT 레코드만 생성/보관. (Android의 NsdManager가 multicast DNS를 처리하므로 C에서 Bonjour 구현 불필요)
- `android_raop_callbacks.c` — 비디오/오디오 프레임 콜백 → Kotlin 렌더러 전달, PIN 인증 콜백
- 암호화: UxPlay는 OpenSSL 필요 → `openssl-cmake`로 소스 빌드(참조 구현: OpenSSL 3.4.4) 또는 NDK prebuilt 활용
- `CMakeLists.txt` — UxPlay 소스 + 브리지 + llhttp + playfair + plist + OpenSSL + oboe(선택)로 `airplay_native.so` 생성

### B. Kotlin 레이어 (`com.airoid` 패키지에 맞게)
- `bridge/NativeBridge.kt` — JNI 메서드 선언 + `System.loadLibrary("airplay_native")`
- `bridge/RaopCallbackHandler.kt` — 네이티브 → Kotlin 콜백 인터페이스 (프레임, 로그, PIN)
- `discovery/NsdServiceManager.kt` — `_raop._tcp`, `_airplay._tcp` NsdManager 등록, multicast lock
- `renderer/VideoRenderer.kt` — MediaCodec 디코드: KEY_LOW_LATENCY, KEY_PRIORITY, KEY_ALLOW_FRAME_DROP, 키프레임 대기, HW→SW 폴백, PTS 기반 스케줄링 릴리스(지연 최소화)
- `renderer/AudioRenderer.kt` — AudioTrack/MediaCodec
- `service/AirPlayService.kt` — 포그라운드 서비스: 네이티브 수명주기, wake lock, StateFlow 상태 노출, MediaSession
- `ui/` — Compose 화면 (아래)

### C. 매니페스트 추가
- 포그라운드 서비스 권한 + `<service android:foregroundServiceType="mediaPlayback">` 선언
- 기존 스캐폴드의 네트워크 권한(INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE)은 이미 있음 — 그대로 사용

## 4. 구현 순서 (점진적 마일스톤, 각 단계에서 검증)

**M0 — 툴체인/빌드 골격**
- NDK + CMake 설치 (현재 미설치), `app/build.gradle.kts`에 `externalNativeBuild` 추가
- UxPlay 서브모듈 vendor, CMake가 `.so`를 컴파일하는 것까지. **검증: `assembleDebug`에서 네이티브 빌드 성공**

**M1 — 발견 가능한 수신기**
- `NativeBridge` + `NsdServiceManager` 연결. 네이티브는 TXT 레코드만 만들고 Kotlin이 mDNS 등록
- **검증: 맥의 AirPlay 목록에 장치가 표시됨** (스트림 없이)

**M2 — 오디오 경로** (가장 작은 end-to-end win)
- RAOP 오디오(AAC-ELD/LC, ALAC) → AudioTrack 재생
- **검증: 맥에서 사운드 전송 시 재생**

**M3 — 화면 미러링** (보조 모니터 = 핵심)
- H.264/H.265 → MediaCodec → SurfaceView. 해상도/재생률 TXT 광고, 저지연 설정
- **검증: 맥에서 "보조 화면으로 사용" 시 화면 표시**

**M4 — HLS/영상 재생 + 리모컨** (선택, 이후)
- ExoPlayer 또는 참조 구현의 로컬 플레이리스트 프록시 방식

**M5 — UI/설정 다듬기**
- PIN 인증, 장치 이름/포트, H.265 토글, 로그 화면, 연결 상태/해상도 표시

## 5. 리스크

| 리스크 | 대응 |
|---|---|
| GPL-3.0 카플렙트 (전체 앱 공개 의무) | 개인 프로젝트에 한정 허용; 상업 배포 시 §7 검토 |
| DRM 콘텐츠(AV 앱 등) 재생 불가 | 원천적 한계 — AirPlay DRM 미지원, 문서화 |
| mDNS 멀티캐스트 차단 라우터/AP isolation | multicast lock 획득; 일부 네트워크에서 발견 불가를 한계로 인지 |
| 미러링 지연 | MediaCodec 저지연 키, PTS 스케줄링 릴리스, SPS max_dec_frame_buffering 조정 |
| H.265 디코더 없는 기기 | HW→SW 폴백 + H.265 토글 (참조 구현 방식) |
| macOS 페어링 요구 | PIN 인증 지원 (UxPlay 내장) |

## 6. 검증 전략
- 각 마일스톤의 검증은 위에 명시한 대로 **실제 맥 AirPlay로 확인** (에뮬레이터 불가 — mDNS/멀티캐스트 필요, 실기기 필요)
- 네이티브 브리지 존재는 unit test로 확인 가능하나, 프로토콜 동작은 실기기 확인이 유일한 증명

## 7. 대안: 프로토콜 자체 구현 (권장 안 함)
RTSP 제어, Pairing(ed25519/SRP), FairPlay, RAOP RTP 역다중화, NTP 동기화를 모두 직접 구현해야 함. UxPlay가 이미 해결한 문제의 재구현 = 수 개월, 높은 실패 리스크. GPL을 피해야 하는 경우에만 고려. (오픈 구현 `nto.github.io/AirPlay.html` 사양 참고)

## 8. 다음 실행 단계
1. NDK + CMake 설치
2. M0 빌드 골격 (UxPlay 서브모듈 + CMake) — **이것부터 시작**

## 9. 현재 구현 상태 (2026-08-12)
완료: 네이티브 UxPlay(OpenSSL+libplist+ffmpeg+oboe) CMake 빌드 → `libairplay_native.so`, JNI 브리지, NsdManager mDNS(_raop/_airplay), 포그라운드 서비스, AudioRenderer(네이티브 오디오 엔진), VideoRenderer(MediaCodec→SurfaceView), 대기 화면 + 제스처 옵션 UI. 에뮬레이터 검증: 서버 7000 LISTEN, mDNS 등록, 포그라운드 알림, 제스처/전체 화면/노치 가리기 동작 확인.
보류(문서화된 범위 축소): HLS 비디오 재생(ExoPlayer), DACP 리모컨/미디어 세션, ALAC 오디오(기본 끔, AAC-ELD/LC는 네이티브 ffmpeg로 지원), TV UI. 실제 맥 AirPlay 스트림은 에뮬레이터 NAT 때문에 실기기에서 확인 필요.

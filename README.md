<p align="center">
  <img src="docs/icon.png" width="160" height="160" alt="Airoid" />
</p>

<p align="center">
  <a href="README.md">한국어</a> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh.md">中文</a>
</p>

# Airoid

Android 기기를 AirPlay 수신기로 바꿔주는 앱입니다. 맥이나 아이폰의 화면을 무선으로
미러링해 안드로이드 기기를 보조 화면처럼 쓰고, AirPlay 오디오도 재생합니다.

## 주요 기능

- **화면 미러링** — H.264/H.265 하드웨어 디코딩, 저지연 파이프라인
- **오디오 수신** — AAC(하드웨어), ALAC(하드웨어 부재 시 소프트웨어 폴백)
- **전환 애니메이션** — 연결/종료 시 기기 프레임(링) 확대·축소 + 영상 페이드
- **빠른 설정 타일** — 미러링 상태 표시(ACTIVE/INACTIVE), 탭해서 앱 실행
- **다국어 UI** — 한국어/English/日本語/中文(简体), 앱 언어 별도 지정 지원
- **페어링 코드** — 언어별 문학 작품 제목으로 기기 식별 (예: `Airoid 태백산맥`)

## 설치

요구 사항: **Android 8.0 이상**, ARM64 기기

1. 아래 릴리스 페이지에서 최신 `app-release.apk`를 내려받습니다.
   - GitHub: <https://github.com/hanana-kick/Airoid/releases/latest>
2. 내려받은 APK를 엽니다. "출처를 알 수 없는 앱 설치" 경고가 뜨면, 내려받은
   브라우저/파일 관리자에 설치 권한을 허용합니다.
3. 설치가 끝나면 앱을 실행합니다.

이후 업데이트도 같은 방식으로 새 APK를 덮어쓰기 설치하면 됩니다 (데이터는 유지됩니다).

## 사용법

1. 앱을 실행하면 대기 화면에 페어링 코드가 표시됩니다 (예: `Airoid 태백산맥`).
2. 맥의 AirPlay 메뉴에서 해당 기기 이름을 선택하면 미러링이 시작됩니다.
3. 미러링 중 위로 스와이프하면 "미러링 종료" 패널이 열립니다.
4. (선택) 빠른 설정 편집에서 "Airoid" 타일을 추가하면 상태 확인과 앱 실행을 할 수 있습니다.

## 라이선스

서드파티 구성요소(UxPlay, FFmpeg, libplist, openssl, oboe)는 각각의 라이선스를 따릅니다.

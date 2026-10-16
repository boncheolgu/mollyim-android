# Memoly

[![Test](https://github.com/boncheolgu/mollyim-android/actions/workflows/test.yml/badge.svg)](https://github.com/boncheolgu/mollyim-android/actions/workflows/test.yml)

Memoly는 Android용 메신저 포크입니다. [Molly](https://github.com/mollyim/mollyim-android)와 [Signal](https://github.com/signalapp/Signal-Android)을 기반으로 하며 Signal 서비스에 연결됩니다.

## 다운로드

이 포크의 [GitHub Releases](https://github.com/boncheolgu/mollyim-android/releases)에서 Memoly APK를 확인할 수 있습니다. 공개 전 draft 릴리스는 저장소 권한이 있는 계정에만 표시됩니다.

앱 패키지는 `im.memoly.app`입니다. 기존 Memoly를 업데이트할 때는 동일한 개인 서명으로 만든 APK를 사용해야 합니다. 원본 앱의 APK와 서명은 다릅니다.

## 추가 기능

- 시스템 인증 또는 Memoly 전용 PIN으로 앱 접근 제한
- 잠금 화면에서 위장 PIN으로 외부 앱 또는 내장 메모장 실행
- 내장 메모장 작성·수정·삭제·자동 저장과 처음 한 번 제공되는 편집 가능한 메모
- 메모장 아이콘과 Memoly 앱 이름
- 최근 사용 스티커 개수 직접 입력: 설정 → 채팅 → 키보드, 1~1,000개
- GitHub Actions를 통한 APK 빌드·서명·설치 검증

자세한 동작은 [위장 PIN](DECOY_PIN.md), [최근 사용 스티커](RECENT_STICKERS.md), [앱 이름](BRANDING.md) 문서를 참고하세요.

## 원본 프로젝트와 라이선스

원본 프로젝트의 저작권, 라이선스 및 외부 서비스 표기는 유지합니다. [원본 README](UPSTREAM.md)의 배포·서명·지원 안내는 원본 프로젝트에 관한 정보입니다. 이 포크의 배포는 위 Memoly 릴리스 페이지를 이용하세요.

[라이선스](LICENSE)와 [Signal 서비스 약관 및 개인정보 처리방침](https://signal.org/legal/)을 확인할 수 있습니다.

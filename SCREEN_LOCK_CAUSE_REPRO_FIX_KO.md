# Memoly .10 잠금 문제: 원인·재현·해결책 재검토

기준: 2026-10-20, Asia/Seoul. .10 소스 `52d530cebbd8e5ad178a4ededbe7fc3ac458c61b`. 수정안 기준 `f9b5ba7bbba709751da06fa2720547eeacd5c820` 및 미커밋 테스트 보완.

## 1. 결론과 확실성

사용자가 겪은 두 증상의 정확한 실기기 원인은 아직 확정되지 않았다. 코드에 실제로 존재하는 실패 분기, 그 분기를 만드는 인위적인 컴포넌트 조건, 정상 Android 생명주기를 따라 그 조건에 도달하는지 여부를 구분해야 한다.

| 항목 | 코드에서 확인한 사실 | 남은 불확실성 |
| --- | --- | --- |
| 정상 PIN 결과 소실 | PIN 창의 요청 번호와 완료 콜백의 요청 번호를 따로 갱신한다. 불일치하면 올바른 검증 결과를 거부한다. | 사용자 기기에서 어떤 사건이 불일치를 만들었는지 미확인 |
| 새 인증의 재무효화 | 작업 감지 코드와 서비스 콜백이 각각 전체 세션을 종료한다. 서비스 콜백에 중복·작업 상관관계 검사가 없다. | 이전 작업의 콜백이 새 화면 재개/인증 이후 도착했는지 미관측 |
| 검은 화면의 복구 누락 | .10의 잠금 변경 및 비사용자 취소 처리에 인증 창을 다시 표시하는 전이가 빠져 있다. | 보고된 수신·발신 종료가 그 전이를 유발했는지 미확인 |
| 입력 중단 | 검증 전에 입력을 지우고, 화면 중단 시 검사 작업을 취소한다. | 사용자가 본 현상이 이 경로인지, 인증 완료 후 재잠금인지 구분 필요 |

기본 권고는 인증 요청의 일관성, 작업 제거의 상관관계·중복 처리, 잠긴 전경 화면의 인증 UI 복구를 함께 검증하는 것이다. PIN 알고리즘 교체나 인증 결과를 무조건 수용하는 변경은 해결책이 아니다.

## 2. PIN 재입력의 가능한 경로

### A. 요청 번호 불일치 — 코드상 실패 분기 확인

아래 줄 번호는 모두 .10 커밋의 파일 기준이다.

- `ScreenLockAuthentication.kt:22–26`: 요청 시점의 세대 번호를 완료 콜백에 캡처한다.
- `ScreenLockPinDialogFragment.kt:279–288`: 일반 앱 잠금 창은 세대가 달라도 기존 창을 재사용하고 콜백만 바꿀 수 있다. 세대 불일치 검사가 설정용 창에만 적용된다.
- 같은 파일 `100–114`: 복귀 시에는 반대로 창 인자의 세대 번호만 바꾸고 기존 콜백은 유지할 수 있다.
- 같은 파일 `193`: PIN 검증에 성공했어도 요청이 오래되었으면 결과를 버린다. 이미 입력을 지운 상태이고 오류 문구도 표시하지 않는다.

두 실패 모양이 다르다. 창의 번호만 오래되면 빈 PIN 창이 그대로 남을 수 있다. 콜백만 오래되면 창을 먼저 닫은 뒤 콜백의 보호 검사에서 거부되어, 여전히 잠긴 본 화면만 남을 수 있다.

다만 일반 홈 복귀에서는 창의 `onResume()`와 최상단 알림에 따른 새 인증 요청이 모두 수행되어 서로 같은 번호로 맞춰질 수 있다. 따라서 “홈에 갔다 오면 항상 재현된다”는 결론은 성립하지 않는다. 재개된 빈 호스트에서 세션 무효화 함수를 직접 호출하는 테스트도 실기기 홈 복귀를 증명하지 않는다.

### B. 뒤늦은 작업 제거 콜백 — 가장 먼저 통합 검증할 가설

`ScreenLockVisibilityObserver.kt:63`은 새 작업에 진입하면서 이전 작업이 사라졌으면 세션을 종료한다. 별도로 `KeyCachingService.java:149–153`의 시스템 `onTaskRemoved()`도 세션을 종료한다. 후자는 `rootIntent`를 상관관계 확인에 사용하지 않고 작업 번호나 이미 처리한 제거 사건도 확인하지 않는다.

검증해야 할 순서는 다음과 같다.

1. 이전 작업 T1 제거를 앱이 감지해 잠근다.
2. 새 작업 T2가 시작·재개되고 새 PIN 창을 표시한다. 또는 새 PIN으로 인증을 완료한다.
3. T1에 대한 서비스 콜백이 나중에 도착한다.
4. 콜백이 전체 세션을 다시 종료하여 T2의 요청 또는 완료한 인증까지 무효화한다.

`ScreenLockSession.end()`는 세대 번호를 올리고 `background=true`로 만든다. 따라서 3번이 전경 화면의 재개 이후라면 새 번호를 얻어도 다음 정상 `resume()` 전까지 `isCurrent()`는 거짓이다. 단순히 PIN 창만 다시 만드는 것으로는 복구되지 않는다.

이 순서가 Android 17 실기기에서 실제로 발생했다는 증거는 없다. 서비스가 이어서 띄우는 `DummyActivity`가 후속 재개를 유발하는지도 통합 테스트에 포함해야 한다. 이 가설은 일반적인 통화 종료까지 자동으로 설명하지 않는다. 작업 제거가 없었던 통화 증상에 그대로 적용해서는 안 된다.

### C. 검사 중 화면 중단 — 재잠금과 구분할 정상 보안 동작

PIN 입력은 검사 시작 전에 지워진다(`147`). `onPause()`는 검사 작업을 취소한다(`125–131`). 화면이 실제로 일시 중단되면 사용자가 올바른 PIN을 입력했더라도 앱 인증은 완료되지 않을 수 있다. 단순히 알림창을 내리면 반드시 `onPause()`가 온다고 가정해서도 안 된다.

이 경로를 없애려고 백그라운드의 이전 인증 결과를 허용하면 안 된다. 화면 복귀 후 유효한 새 요청을 표시하는지가 검증 대상이다.

## 3. 통화 종료 후 검은 화면

`.10 BaseActivity.java:177–181`은 잠금 상태 변경 시 내용을 숨기고 창을 포커스·터치 불가로 만든다. 그러나 인증 창을 띄우지는 않는다. `256–267`의 비사용자 인증 취소도 재시도하지 않는다. 이미 최상단인 화면에는 이 변경만으로 새 최상단 알림이 생기지 않을 수 있고, 포커스를 금지한 창이 포커스 이벤트로 복구되리라 기대할 수도 없다.

통화 화면의 일반 종료 경로(`WebRtcCallActivity.kt:940–952, 1250–1252`)는 종료 표시, 서비스 이벤트 제거, 약 1초 후 `finish()` 예약이다. 이 경로 자체에서 화면 잠금을 명시적으로 무효화하는 호출은 찾지 못했다. 통화 종료 후 어느 Activity가 남았는지와 그 화면의 보호 상태가 필요하다.

검은 화면이 발생하면 다음을 구분해야 한다.

- 보호 내용이 `INVISIBLE`이고 입력 금지 플래그가 남음: 잠금 UI 복구 문제와 직접 연결된다.
- 내용이 보이는 상태인데 화면이 검음, 또는 디스플레이가 꺼짐: 통화 UI·창 전환·센서 경로를 별도로 조사해야 한다.

두 증상이 같은 원인이라는 결론도 아직 내릴 수 없다.

## 4. 제외하거나 낮춘 기존 가설

- **복귀 시 최상단 알림이 onPostResume보다 먼저 와서 PIN 창을 놓친다는 설명:** 표준 복귀 경로의 근거로 사용하지 않는다. AOSP `Activity.performResume()`는 `onPostResume()`까지 호출하고, `ActivityThread.performResumeActivity()`는 그 뒤 최상단 변경을 보고한다. 이전 테스트의 인위적인 순서는 기기 재현 증거가 아니다. 정상 복귀 제어 테스트를 이 순서로 고쳤다. Samsung Android 17의 모든 경로를 이 확인만으로 단정하지는 않는다.
- **창을 닫고 성공 콜백을 나중에 호출하므로 반드시 두 번째 PIN이 남는다는 설명:** 지연 구간은 존재하지만, 인증 상태 관찰자가 뒤이어 추가 창을 정리할 수도 있다. 지속적인 재입력의 확정 원인으로 삼지 않는다. 앱 잠금 해제 시 완료 처리를 먼저 하는 것은 전이 단순화 차원에서 검증한다.
- **잘못된 PIN 알고리즘이라는 설명:** 현재까지 확인한 실패 분기는 PIN 일치 검사 이후의 상태 처리다. 실제 기기에서 검증에 성공했는지는 로그 없이 단정하지 않는다.

AOSP 확인 자료:

- [Activity.performResume와 onPostResume](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/app/Activity.java)
- [ActivityThread.performResumeActivity와 최상단 보고](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r14/core/java/android/app/ActivityThread.java)
- [최상단 알림이 생략될 수도 있다는 API 계약](https://source.android.com/docs/core/display/multi_display/multi-resume)

## 5. 재현·검증 방안

### 자동 테스트 — 우선 수행할 항목

아래는 실행 계획과 예상 판정이다. 실행 성공 결과가 아니다.

| 시험 | 순서 | 판정 |
| --- | --- | --- |
| 일반 복귀 제어 | PIN 창 → pause/stop → 숨김 → start/resume/postResume → 새 요청 → 정상 PIN | .10과 수정본 모두 1회 입력으로 인증되어야 함 |
| 강제 요청 불일치 | 재개된 PIN 창 유지 → 세션 변경 → 새 콜백 연결 → 정상 PIN | .10의 성공 결과 소실을 정확한 단언 실패로 확인하고, 수정본은 새 요청으로 1회 인증 |
| 늦은 제거 콜백 | T1 제거 감지 → T2 재개/인증 → T1 콜백 전달; DummyActivity 후속 동작 포함 | 이미 처리한 T1 사건이 T2의 새 인증을 다시 무효화하지 않아야 함 |
| 현재 작업 제거 | T2 인증 → T2 제거 | 실제 제거에는 즉시 잠금, 이전 결과·예약 동작 거부 |
| 전경 중 무효화 | 화면은 재개·최상단인 상태에서 세션 종료 | 무한 창 생성 금지; 실제 전경 상태를 다시 맞춘 뒤 유효한 인증 창 1개 |
| 잠긴 화면 복구 | 이미 최상단인 보호 화면에 잠금 변경 전달; 포커스 알림 없음 | 내용은 숨겨진 채로 인증 UI가 표시되어야 함 |
| 검사 중단 | 검사 완료 전달을 지연 → pause → 결과 전달 → 정상 복귀 | 이전 요청은 인증하지 않음; 새 요청은 정상 인증 |
| 통화 통합 | 수신/발신 × 본인/상대 종료 × 전경 유지/백그라운드 복귀 | 통화 종료 후 채팅 또는 인증 UI; 숨김·입력 금지 상태만 남지 않음 |

강제 요청 불일치 시험이 실패하더라도, 그 결과만으로 실기기의 발생 원인이 증명되지는 않는다. 세션 API 직접 호출 시험 뒤에 실제 서비스·Activity 콜백을 거치는 통합 시험이 필요하다.

### 사용자 실기기 — 확정 절차 없음, 보조 시험만 가능

홈 복귀는 이미 재현되지 않았으므로 반복을 요구하지 않는다. 아래도 성공을 보장하는 재현법이 아니다.

1. 작업 제거 가설: 최근 앱에서 제거한 직후 바로 아이콘으로 열어 정상 PIN을 한 번 입력한다. 늦은 콜백이 없거나 서비스가 실행 중이 아니면 재현되지 않는다. 현상이 생겼다는 사실만으로 콜백 가설이 확정되는 것도 아니다.
2. 통화 구분: 화면을 켜고 스피커폰으로 통화한 뒤 종료한다. 수신/발신 및 본인/상대 종료를 구분하고, 검은 화면이 1초 종료 표시 이후에도 남는지 확인한다. 이것은 센서·일반 종료 표시와 지속 장애를 구분하는 시험이다.

가장 유용한 자료는 민감정보 없는 진단 APK의 상태 전이 기록이다. 해당 진단 기능은 아직 구현하지 않았다. 기록 후보: 일시적인 요청 ID, 세대·작업 번호, 사건 사유, background/locked 상태, Activity 생명주기/최상단 여부, 검증 결과 종류, 인증 수락 여부, 내용 표시 상태와 입력 금지 플래그. PIN 값·길이·키·검증자·대화·연락처는 기록하지 않는다. 사용자가 발생 시점의 기록만 내보내는 방식으로 제한한다.

## 6. 해결책과 적용 순서

1. **테스트를 먼저 신뢰 가능하게 만든다.** 정상 복귀 제어와 강제 불일치를 같은 환경에서 .10/수정본에 비교한다. 테마·SDK·MockK 준비 오류를 버그 재현으로 세지 않는다.
2. **인증 요청을 원자적으로 교체한다.** 요청 ID/세대/호스트/목적/콜백을 묶고, 오래된 창과 진행 중 검사를 취소한 뒤 새 요청을 만든다. 인자만 수정하거나 오래된 성공을 허용하지 않는다. 실제 앱 해제는 인증 완료 후 창을 닫고, 같은 태그로 새 설정 창을 여는 확인 흐름은 별도로 유지한다.
3. **작업 제거를 한 곳에서 판정한다.** 서비스 신호를 곧바로 전역 세션 종료로 바꾸지 않고 작업 추적기가 제거된 작업과 이미 처리한 사건을 확인하도록 한다. `rootIntent`는 고유 작업 ID가 아니므로 그것만으로 상관관계를 결정하지 않는다. 재시작·여러 작업·오래된 콜백·작업 목록 조회 실패도 시험한다. 화면이 보인다는 이유만으로 모든 제거를 무시하는 방식은 쓰지 않는다.
4. **세션과 실제 화면 상태를 맞춘 뒤 UI를 복구한다.** 잠김 + 실제로 활성인 보호 화면 + 표시 가능한 Fragment 상태라면 인증 UI 하나를 보장한다. background 상태나 저장된 Fragment 상태에서는 반복 예약하지 않는다. 실제 재개에서 다시 실행한다. 콜백 재시도 루프 안에서 무조건 `resume()`을 호출하지 않는다.
5. **통화 복귀를 별도로 통합 검증한다.** 종료 후 남은 화면의 내용·입력 플래그·인증 창을 확인한다. 단순히 내용을 강제로 보이게 하거나 잠금을 생략해서 검은 화면을 없애지 않는다.

최근 앱 제거 시 즉시 잠금, 기존 알림 미리보기 설정, 인증 없는 통화 거절·종료 허용, 응답과 앱 접근의 인증 요구, 위장 PIN의 독립 정책을 유지한다.

### 현재 수정안의 추가 결함

`f9b5ba7` 스케줄러는 Activity가 재개·최상단이면 비사용자 취소 후 다시 창을 예약한다. 하지만 세션이 `background=true`이면 새 창은 즉시 요청을 거부하고 다시 취소한다. 이 상태가 지속되면 생성→취소→예약을 반복할 수 있다. 작업 제거 처리와 세션 준비 조건을 함께 고쳐야 한다. PIN 창 갱신만 수정한 현재 안을 최종 해결책으로 배포하지 않는다.

## 7. 실제 검증 상태

- 기존 CI `39266420312`는 실패했다. PIN 완료 테스트 4개는 `ScreenLockPinStore.verify` 모킹 준비에서 `AbstractMethodError`가 나서 동작 단언에 도달하지 못했다. 다른 `StoryContextMenuTest` 초기화 오류도 있었고 관계는 미확정이다.
- PIN 테스트는 실제 저장소·HMAC 검증을 쓰고 Java KeyStore 경계만 대체하도록 보완했다. 일반 복귀 제어 시험도 추가했다. 이 변경의 Android 실행은 아직 하지 않았다.
- 현재 로컬에는 JDK/Android SDK가 없어 Android 테스트를 실행할 수 없다. Python 판정 스크립트 시험 성공은 Android 인증 성공을 뜻하지 않는다.
- 이 재검토에서는 APK 생성, PR 머지, 릴리스 게시 또는 운영 코드 추가 변경을 하지 않았다. 수정안의 검증 완료나 두 실기기 증상의 해결 완료도 주장하지 않는다.


## Implementation update after fingerprint comparison

The user reports that fingerprint authentication did not exhibit the symptom. PIN-specific request and cancellation differences are now the first validation target; shared task-removal hypotheses remain unconfirmed and unchanged. The working fix carries one captured generation through authentication and queued PIN creation, cancels/notifies on pause exactly once, captures the verification listener, tracks host request IDs, replaces invalidated open prompts, and guards scheduler retries on session readiness. The background retry-loop risk documented for f9b5ba7 has been addressed in code but awaits Android verification. The proposed focused suite now has fifteen cases. The comparison replaces both the PIN dialog and authentication dispatcher with their .10 versions, with the documented IO-dispatch seam only, before restoring both fixed files. No physical-device resolution is claimed. See SCREEN_LOCK_TRANSITIONS.md for current behavior and verification state.

# 예산 잔액 (budget-app)

예산별 잔여금액과 통장 잔액을 홈 화면 위젯으로 보여주는 안드로이드 앱.
은행·카드 앱의 입출금 알림(문자 포함)을 읽어 잔액과 지출을 자동 반영한다.
인터넷 권한이 없으며 모든 데이터는 기기 안에만 저장된다.

- 최소 Android 12 (API 31), Kotlin, 외부 라이브러리 없음
- 패키지: `com.mybudget.app`

## 빌드

필요한 것: JDK 17 이상, Android SDK (플랫폼 35, 빌드 도구 35.0.0)

Android Studio가 있으면 이 폴더(`budget-app`)를 열고 실행하면 된다.

명령줄(Linux)에서는:

```bash
ANDROID_HOME=/opt/android-sdk bash scripts/setup-android-sdk.sh
```

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 만들어진다.

## 에뮬레이터/기기에서 실행

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

설치 후 앱에서 "알림 접근 허용하기"를 눌러야 통장 연동이 동작한다.
직접 설치한 앱은 설정 → 애플리케이션 → 예산 잔액 → ⋮ → "제한된 설정 허용"이 먼저 필요할 수 있다.

## 구조

| 파일 | 역할 |
| --- | --- |
| `Parser.kt` | 알림 문구에서 금액·가맹점·잔액 추출 (단위 테스트: `ParserTest.kt`) |
| `Store.kt` | 예산·거래·잔액 저장, 자동 분류, 중복 알림 합치기 |
| `NotiListener.kt` | 알림 수신 서비스 |
| `BudgetWidget.kt` | 홈 화면 위젯 |
| `MainActivity.kt` | 메인 화면과 설정 |

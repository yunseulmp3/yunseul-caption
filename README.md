# 윤슬 자막 (Yunseul Caption)

갤럭시에서 영상을 보는 동안 **일본어 원문 + 한국어 번역** 자막을 화면 위에 띄워주는 안드로이드 앱.

## 구조
다른 앱 재생음 캡처(AudioPlaybackCapture) → 16kHz 모노 → Silero VAD로 문장 단위 분할 → whisper.cpp(일본어, 폰에서 오프라인 인식) → 구글 번역 → 떠 있는 자막창

## 설치
1. 폰에서 [최신 APK 받기](../../releases/latest/download/yunseul-caption.apk)
2. 설치 시 "출처를 알 수 없는 앱" 허용
3. 앱에서 권한 2개 허용 → 모델 받기 → 자막 시작

## 빌드
`main`에 푸시하면 GitHub Actions가 whisper.cpp를 받아 APK를 빌드하고 `latest` 릴리즈에 올림. 빌드 결과/로그는 `build-logs` 브랜치에도 기록됨.

## 제한
- 넷플릭스 등 소리 캡처를 막아둔 앱은 안 됨
- 서명 키는 저장소에 없음. Actions 캐시에 보관됨 (7일 넘게 빌드가 없으면 캐시가 지워져 새 키가 생기고, 그땐 폰에서 앱을 지우고 다시 설치해야 함)

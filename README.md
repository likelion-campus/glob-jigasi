# 프로젝트 이름 및 목적

- 프로젝트 이름 : Jigasi
- 목적: 라이브룸, 비디오콜 STT 서비스를 위한 중계서버
- 기획: [라이브룸 AI 강의 요약 자동화 기능](https://www.notion.so/AI-27344860a4f480c5b970d46a243441c1?pvs=21)
- Github: https://github.com/likelion-campus/glob-jigasi.git

# 레포 세팅

```jsx
git clone https://github.com/likelion-campus/glob-jigasi.git

//upstream: 업데이트 되고있는 jitsi 서비스의 최신 jigasi 코드로 업데이트 하기위한 레포
//git merge upstream master로 주기적으로 업데이트 진행

git remote add upstream https://github.com/jitsi/jigasi.git
```

# 수정사항

### **1. 참가자 정보 확장**

**📁 수정 파일: src/main/java/org/jitsi/jigasi/transcription/Participant.java**

**🔧 추가된 기능**:

- **stats_id 필드 추가**: XMPP StatsId extension에서 추출한 고유 참가자 ID
- **getChatMemberRole() 메서드**: ChatRoom 내 역할 정보 반환 (OWNER, MEMBER, MODERATOR 등)
- **isModerator() 메서드**: 모더레이터 권한 확인

```java
// 새로 추가된 필드와 메서드
private String statsId;
public String getStatsId() { return statsId; }
public void setStatsId(String statsId) { this.statsId = statsId; }
public String getChatMemberRole() { /* ChatRoomMemberRole을 문자열로 반환 */ }
public boolean isModerator() { /* OWNER 또는 MODERATOR 역할 확인 */ }
```

### **2. STT 서비스로 전송되는 JSON 데이터 확장**

**📁 수정 파일: src/main/java/org/jitsi/jigasi/transcription/VoskTranscriptionService.java**

**🔧 개선 내용**:

기존 JSON 구조에 참가자 상세 정보를 추가하여 STT 서비스에서 더 정확한 처리 가능

**이전 JSON**:

```json
{
  "config": {
    "sample_rate": 16000,
    "language": "en-US"
  }
}
```

**현재 JSON**:

```json
{
  "config": {
    "sample_rate": 16000,
    "debug_name": "roomName/participantName",
    "room_id": "roomName",
    "participant_id": "participantName",
    "language": "en-US",
    "is_moderator": true,
    "role": "OWNER",
    "stats_id": "unique_stats_id_value"
  }
}
```

### **3. XMPP Presence에서 stats_id 자동 추출**

**📁 수정 파일: src/main/java/org/jitsi/jigasi/transcription/Transcriber.java**

**🔧 구현 내용**:

참가자 정보 업데이트 시 XMPP Presence의 StatsId extension에서 자동으로 stats_id 추출

```java
// updateParticipant() 메서드에 추가된 로직
if (presence != null) {
    for (ExtensionElement ext : presence.getExtensions()) {
        if (ext instanceof org.jitsi.xmpp.extensions.jitsimeet.StatsId) {
            StatsId statsIdExt = (StatsId) ext;
            String actualStatsId = statsIdExt.getStatsId();
            if (actualStatsId != null && !actualStatsId.isEmpty()) {
                participant.setStatsId(actualStatsId);
            }
            break;
        }
    }
}
```

### **4. 자막 서비스 종료 로직 개선**

**📁 수정 파일: src/main/java/org/jitsi/jigasi/TranscriptionGatewaySession.java**

**🔧 개선 내용**:

기존에는 명시적인 자막 요청이 없으면 즉시 종료되었으나, **참가자가 있는 동안은 자막 서비스를 유지**하도록 변경

**핵심 로직**:

```java
private boolean isTranscriptionRequested() {
    boolean participantRequesting = transcriber.isAnyParticipantRequestingTranscription();
    boolean backendEnabled = isBackendTranscribingEnabled;
    boolean visitorsRequesting = this.numberOfVisitorsRequestingTranscription > 0;
    
    // 새로운 로직: 전사가 시작되면 참가자가 있는 동안 계속 유지
    boolean hasParticipants = calculateRealParticipants() > 0;
    boolean transcriptionStartedAndHasParticipants = transcriber.isTranscribing() && hasParticipants;
    
    return (participantRequesting || backendEnabled || visitorsRequesting) || 
           transcriptionStartedAndHasParticipants;
}
```

### **5. 지연 STT 연결 (Lazy Connection) 구현**

**📁 수정 파일: src/main/java/org/jitsi/jigasi/transcription/Participant.java**

**🔧 성능 최적화**:

기존에는 참가자 입장 시 즉시 STT 서비스와 연결했으나, **실제 음성 데이터 수신 시에만 연결**하도록 변경

**이전 방식**:

```java
void joined() {
    // 즉시 STT 연결 생성
    session = transcriber.getTranscriptionService().initStreamingSession(this);
}
```

**현재 방식**:

```java
void joined() {
    // 연결하지 않고 대기
    isCompleted = false;
}

void giveBuffer(Buffer buffer) {
    ensureStreamingSessionExists(); // 필요할 때만 연결
    // 음성 데이터 처리
}
```

**💡 효과** :

- 말하지 않는 참가자는 STT 연결 없음
- STT 서버 부하 대폭 감소 (10명 방에서 2명만 말하면 2개 연결만 생성)

### **6.  STT 연결 재시도 로직 (Retry with Exponential Backoff)**

### **📁 수정 파일**:

- src/main/java/org/jitsi/jigasi/transcription/Participant.java
- src/main/java/org/jitsi/jigasi/transcription/VoskTranscriptionService.java

**🔧 안정성 개선**:

STT 서버 장애 시 자동 재연결 및 중복 연결 방지 로직 구현

**주요 기능**

:

- **최대 3번 재시도** (설정 가능)
- **지수적 백오프**: 1초 → 2초 → 4초 대기
- **중복 연결 방지**: isConnecting 플래그로 동시 연결 시도 차단
- **스팸 방지**: 최소 1초 간격으로 재시도 제한

```java
// 재시도 설정
private static final int MAX_STT_RETRY_ATTEMPTS = 3;
private static final long STT_RETRY_BASE_DELAY_MS = 1000;
private volatile boolean isConnecting = false;
private volatile long lastConnectionAttempt = 0;
```

**연결 끊김 감지**:

```java
@OnWebSocketClose
public void onClose(int statusCode, String reason) {
    logger.warn("STT connection closed: " + statusCode + ", " + reason);
    this.session = null; // 재연결 트리거
}

@OnWebSocketError  
public void onError(Throwable cause) {
    logger.error("STT connection error: " + cause.getMessage());
    this.session = null; // 재연결 트리거
}
```

**💡 효과** :

- 말하지 않는 참가자는 STT 연결 없음
- STT 서버 부하 대폭 감소 (10명 방에서 2명만 말하면 2개 연결만 생성)

# 배포

```bash
🏗️ 빌드 및 배포
Docker 빌드 프로세스

📁 수정 파일:
Dockerfile
build_likelion_jigasi.sh

🔧 개선 내용:
로컬 Maven 빌드 후 Docker 빌드로 변경 (의존성 해결 문제 해결)
수정된 클래스 파일들을 기존 JAR에 패치하는 방식 채택

빌드:
./build_likelion_jigasi.sh
```

작성일자: 2025/09/22

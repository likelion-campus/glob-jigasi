# 기본 Jitsi 이미지 사용
FROM jitsi/jigasi:stable-10184

# 로컬에서 빌드된 클래스 파일들을 복사
COPY target/classes/org/jitsi/jigasi/transcription/ /tmp/org/jitsi/jigasi/transcription/
COPY target/classes/org/jitsi/jigasi/JvbConference*.class /tmp/org/jitsi/jigasi/
COPY target/classes/org/jitsi/jigasi/TranscriptionGateway*.class /tmp/org/jitsi/jigasi/
COPY target/classes/org/jitsi/jigasi/CallContext.class /tmp/org/jitsi/jigasi/

# 기존 JAR 파일에 수정된 클래스들을 덮어쓰기
RUN cd /tmp && \
    echo "=== Updating JAR file with modified classes ===" && \
    ls -la org/jitsi/jigasi/ && \
    echo "=== CallContext classes ===" && \
    ls -la org/jitsi/jigasi/CallContext*.class && \
    jar -uf /usr/share/jigasi/jigasi.jar \
        org/jitsi/jigasi/transcription/VoskTranscriptionService*.class \
        org/jitsi/jigasi/transcription/Transcriber*.class \
        org/jitsi/jigasi/transcription/Participant*.class \
        org/jitsi/jigasi/JvbConference*.class \
        org/jitsi/jigasi/TranscriptionGateway*.class \
        org/jitsi/jigasi/CallContext*.class && \
    echo "=== JAR update completed ===" && \
    rm -rf /tmp/org
#!/bin/bash
# 수정된 Jigasi 이미지 배포를 위한 AWS ECR 배포 스크립트
set -e  # 에러 발생 시 스크립트 중단

# brgg-dev 프로필 사용
export AWS_PROFILE=brgg-dev

# 명령행 인자 처리
REBUILD="false"
POSTFIX=""
if [ $# -eq 0 ]; then
    IMAGE_TAG="latest"
elif [ "$1" = "--rebuild" ] || [ "$1" = "-r" ]; then
    REBUILD="true"
    if [ $# -eq 1 ]; then
        IMAGE_TAG="latest"
    elif [ "$2" = "--postfix" ] || [ "$2" = "-p" ]; then
        if [ $# -eq 2 ]; then
            echo "Error: --postfix 옵션 뒤에 postfix 값을 지정해야 합니다."
            echo "Usage: $0 --rebuild --postfix <postfix>"
            exit 1
        fi
        POSTFIX="$3"
        IMAGE_TAG="latest-${POSTFIX}"
    else
        IMAGE_TAG="$2"
    fi
elif [ "$1" = "--postfix" ] || [ "$1" = "-p" ]; then
    if [ $# -eq 1 ]; then
        echo "Error: --postfix 옵션 뒤에 postfix 값을 지정해야 합니다."
        echo "Usage: $0 --postfix <postfix>"
        exit 1
    fi
    POSTFIX="$2"
    IMAGE_TAG="latest-${POSTFIX}"
else
    IMAGE_TAG="$1"
fi

# 설정
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
AWS_REGION="ap-northeast-2"
REPOSITORY_NAME="public.ecr.aws/d2o0f9y6/glob/likelion_jigasi"

echo "AWS Account ID: $AWS_ACCOUNT_ID"
echo "Building and pushing Jigasi image: $REPOSITORY_NAME:$IMAGE_TAG"
echo "Using tag: $IMAGE_TAG"
echo "Rebuild mode: $REBUILD"

# ECR 로그인
echo "ECR에 로그인 중..."
aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws

# 로컬에서 Maven 빌드 먼저 실행
echo "🔨 로컬에서 Maven 빌드 실행 중..."
mvn clean compile -DskipTests

# 빌드 성공 확인
if [ $? -ne 0 ]; then
    echo "❌ Maven 빌드 실패!"
    exit 1
fi
echo "✅ Maven 빌드 완료!"

# 이미지 빌드 및 푸시
echo "수정된 Jigasi 이미지 빌드 및 푸시 중..."

if [ "$REBUILD" = "true" ]; then
    echo "🔄 Rebuild 모드: 캐시 없이 새로 빌드합니다..."
    docker buildx build --platform linux/amd64 \
      --no-cache \
      -t "$REPOSITORY_NAME:$IMAGE_TAG" \
      -f ./Dockerfile . --push
else
    echo "⚡ 일반 모드: 캐시를 사용하여 빌드합니다..."
    docker buildx build --platform linux/amd64 \
      -t "$REPOSITORY_NAME:$IMAGE_TAG" \
      -f ./Dockerfile . --push
fi

echo "빌드 및 푸시 완료!"
echo "🎉 Modified Jigasi image available at: $REPOSITORY_NAME:$IMAGE_TAG"
echo ""
echo "📝 사용법:"
echo "   docker run $REPOSITORY_NAME:$IMAGE_TAG"
echo ""
echo "🔧 주요 변경사항:"
echo "   - VoskTranscriptionService에서 유저 정보 전달 (debug_name, room_id, participant_id, language)"
echo "   - 기존 jitsi/jigasi:stable-10184 기반으로 수정된 클래스만 교체"

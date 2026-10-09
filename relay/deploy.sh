#!/bin/sh
# 라즈베리파이 배포: 코드 전송 -> systemd 서비스 등록/재시작 -> 응답 확인
# 프로젝트 루트에 deploy.sh로 복사해서 아래 설정만 채운다.
set -e

# ===== 설정 =====
APP=earbridge-relay
FILES="server.js earbridge-relay.service"
CHECK="http://192.168.45.53:8170/"
PUBLIC_CHECK="https://pi.taild4dc8e.ts.net/earbridge/"
# ================

HOST=${PI_HOST:-aaaa@192.168.45.53}
cd "$(dirname "$0")"

echo "-> 전송"
COPYFILE_DISABLE=1 tar --no-xattrs -cf - $FILES | ssh -o BatchMode=yes "$HOST" "mkdir -p ~/$APP && cd ~/$APP && tar xf - 2>/dev/null"

echo "-> 서비스 등록/재시작"
ssh -o BatchMode=yes "$HOST" "sudo -n cp ~/$APP/$APP.service /etc/systemd/system/ && sudo -n systemctl daemon-reload && sudo -n systemctl enable $APP >/dev/null 2>&1; sudo -n systemctl restart $APP"

sleep 2
if ! ssh -o BatchMode=yes "$HOST" "systemctl is-active --quiet $APP"; then
  echo "!! 서비스가 안 떴어요. 로그:"
  ssh -o BatchMode=yes "$HOST" "journalctl -u $APP -n 30 --no-pager"
  exit 1
fi
echo "-> 서비스 active"

[ -n "$CHECK" ] && curl -s -o /dev/null -w "LAN    %{http_code}  $CHECK\n" --max-time 10 "$CHECK"
[ -n "$PUBLIC_CHECK" ] && curl -s -o /dev/null -w "공개   %{http_code}  $PUBLIC_CHECK\n" --max-time 15 "$PUBLIC_CHECK"
ssh -o BatchMode=yes "$HOST" "journalctl -u $APP -n 5 --no-pager --output=cat"

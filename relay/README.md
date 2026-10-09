# EarBridge 중계 서버

폰(`/send/<코드>`)이 보낸 WebSocket 바이너리를 같은 코드의 맥(`/listen/<코드>`)들에게 넘긴다. 의존성 없는 Node 단일 파일.

## 파이 배포

- 서비스 `earbridge-relay`, 포트 8170, 공개 주소 `https://pi.taild4dc8e.ts.net/earbridge` (Funnel 하위 경로)
- `./deploy.sh`로 재배포
- Funnel 경로 추가(한 번만): `ssh aaaa@192.168.45.53 'sudo tailscale funnel --bg --set-path /earbridge http://127.0.0.1:8170'`

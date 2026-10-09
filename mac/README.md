# EarBridge (맥)

안드로이드 폰 마이크 소리를 맥에서 듣는 앱. 폰 앱은 `../android`, 중계 서버는 `../relay`.

- 같은 와이파이: 폰이 Bonjour(`_earbridge._tcp`, 포트 7700)로 알리고 맥이 자동으로 찾아 TCP로 직접 붙는다.
- 다른 네트워크: 폰이 파이 중계(`wss://pi.taild4dc8e.ts.net/earbridge/send/<코드>`)로 올리고, 맥은 폰에 뜬 8자리 코드로 `/listen/<코드>`에 붙는다. 듣는 맥이 없으면 폰은 안 보낸다.
- 소리: 48kHz 모노 16비트 PCM. 접속 첫 9바이트 헤더 `EBR1` + 샘플레이트(LE) + 채널 수.

## 빌드

```bash
./build_app.sh             # EarBridge.app (유니버설, ad-hoc 서명)
./build_app.sh --install   # 응용 프로그램 폴더에 넣고 실행
```

처음 실행 때 "로컬 네트워크" 허용이 필요하다. 아이콘은 `icon/make_icon.py`로 다시 만든다(안드로이드 mipmap도 같이 생성).

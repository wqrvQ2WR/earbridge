"""EarBridge 아이콘: 핑크-보라-하늘 그라디언트 위에 흰 마이크 + 오른쪽으로 퍼지는 소리 물결."""
import os, sys
from PIL import Image, ImageDraw

SS = 4  # 슈퍼샘플링
STOPS = [(0xFF, 0x4D, 0x9D), (0x9B, 0x5C, 0xFF), (0x3D, 0xA9, 0xFF)]


def gradient(size):
    w = h = size
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / (w + h - 2)  # 왼쪽 위 -> 오른쪽 아래
            if t < 0.5:
                a, b, u = STOPS[0], STOPS[1], t / 0.5
            else:
                a, b, u = STOPS[1], STOPS[2], (t - 0.5) / 0.5
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
    return img


def glyph(size, scale=1.0):
    """투명 배경에 흰 글리프. scale=1이면 size 안을 꽉 채우는 크기."""
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = S * scale / 100  # 100단위 좌표계
    ox = S / 2 - 50 * u
    oy = S / 2 - 50 * u
    W = (255, 255, 255, 255)

    def P(x, y):
        return (ox + x * u, oy + y * u)

    lw = 6.5 * u
    # 마이크 머리 (캡슐) - 왼쪽으로 조금 치우침
    mx = 36
    d.rounded_rectangle([P(mx - 11, 14), P(mx + 11, 56)], radius=11 * u, fill=W)
    # 받침 U자
    d.arc([P(mx - 20, 30), P(mx + 20, 68)], start=0, end=180, fill=W, width=int(lw))
    for ex in (mx - 20 + lw / u / 2, mx + 20 - lw / u / 2):
        x, y = P(ex, 49)
        d.ellipse([x - lw / 2, y - lw / 2, x + lw / 2, y + lw / 2], fill=W)
    # 기둥, 받침대
    d.rectangle([P(mx - lw / u / 2, 64), P(mx + lw / u / 2, 80)], fill=W)
    d.rounded_rectangle([P(mx - 13, 78), P(mx + 13, 78 + lw / u)], radius=lw / 2, fill=W)
    # 소리 물결 3개
    for i, r in enumerate((14, 25, 36)):
        cx, cy = 52, 41
        alpha = (255, 220, 170)[i]
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ccx, ccy = cx + 6, cy
        ld.arc([P(ccx - r, ccy - r), P(ccx + r, ccy + r)], start=-48, end=48, fill=W, width=int(lw))
        # 둥근 끝
        import math
        rm = r - lw / u / 2
        for a in (-48, 48):
            ex = ccx + rm * math.cos(math.radians(a))
            ey = ccy + rm * math.sin(math.radians(a))
            x, y = P(ex, ey)
            ld.ellipse([x - lw / 2, y - lw / 2, x + lw / 2, y + lw / 2], fill=W)
        if alpha < 255:
            layer.putalpha(layer.getchannel("A").point(lambda v: v * alpha // 255))
        img.alpha_composite(layer)
    return img.resize((size, size), Image.LANCZOS)


def squircle_mask(size, inset, radius):
    S = size * SS
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle([inset * SS, inset * SS, S - inset * SS, S - inset * SS],
                                        radius=radius * SS, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def mac_icon(size=1024):
    # 맥 아이콘 규격: 1024 안에 824 몸체, 모서리 반경 ~185
    inset = size * 100 / 1024
    body = gradient(size).convert("RGBA")
    g = glyph(size, scale=0.62)
    body.alpha_composite(g)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(body, (0, 0), squircle_mask(size, inset, size * 185 / 1024))
    return out


def android(res_dir):
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, f in dens.items():
        d = os.path.join(res_dir, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        fg_size = int(108 * f)
        # 적응형 아이콘: 안전 영역(가운데 66dp)에 글리프
        glyph(fg_size, scale=0.58).save(os.path.join(d, "ic_launcher_foreground.png"))
        gradient(fg_size).save(os.path.join(d, "ic_launcher_background.png"))
        # 옛 런처용 동그란/네모 아이콘
        leg = int(48 * f)
        body = gradient(leg).convert("RGBA")
        body.alpha_composite(glyph(leg, scale=0.82))
        sq = Image.new("RGBA", (leg, leg), (0, 0, 0, 0))
        sq.paste(body, (0, 0), squircle_mask(leg, 0, leg * 0.22))
        sq.save(os.path.join(d, "ic_launcher.png"))
        rd = Image.new("RGBA", (leg, leg), (0, 0, 0, 0))
        rd.paste(body, (0, 0), squircle_mask(leg, 0, leg / 2))
        rd.save(os.path.join(d, "ic_launcher_round.png"))
    any_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
    os.makedirs(any_dir, exist_ok=True)
    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(any_dir, n), "w").write(xml)


if __name__ == "__main__":
    out_dir, res_dir = sys.argv[1], sys.argv[2]
    mac_icon(1024).save(os.path.join(out_dir, "icon_1024.png"))
    android(res_dir)
    print("ok")

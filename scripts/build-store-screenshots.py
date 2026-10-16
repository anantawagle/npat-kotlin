"""Compose branded listing pages around unmodified native Android captures.

Requires Python Playwright plus its Chromium browser. No live game data is used.
"""
from pathlib import Path
from html import escape
import base64
import json
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "release/store-assets/screenshots"
RAW = OUTPUT / "raw"
SCREENS = [
    ("01-play", "THE QUICK-THINKING CLASSIC", "One letter.<br>Think fast.", "Fill your sheet. Beat the clock.<br>Stop the bus.", "forest"),
    ("02-bluetooth", "PLAY NEARBY", "No internet?<br>Still game on.", "Host a Bluetooth table.<br>Bring your friends within range.", "cream"),
    ("03-room", "MAKE ROOM FOR YOUR RIVALS", "Your friends.<br>Your table.", "Share a room code, ready up,<br>and let the rounds begin.", "clay"),
    ("04-categories", "MAKE IT YOUR GAME", "Pick your<br>perfect mix.", "Choose up to six categories.<br>Keep everyone on their toes.", "cream"),
    ("05-review", "HAVE YOUR SAY", "Good answer?<br>You decide.", "Approve or disapprove.<br>Every eligible answer gets a review.", "forest"),
    ("06-results", "GOOD GAME. GO AGAIN?", "Play. Score.<br>Rematch.", "Three rounds of quick thinking.<br>One more game is always an option.", "clay"),
]
CSS = """
*{box-sizing:border-box}html,body{margin:0;width:1080px;height:1920px;overflow:hidden}
body{font-family:'Segoe UI',Arial,sans-serif;background:#f4efdf;color:#243e30;-webkit-font-smoothing:antialiased}
.forest{background:#173f32;color:#fff8e9;--line:rgba(255,248,233,.06);--tile:#f4ca74;--tileink:#173f32;--muted:#d2e2cc}
.cream{background:#f4efdf;color:#243e30;--line:rgba(39,99,75,.06);--tile:#27634b;--tileink:#f4efdf;--muted:#4d6854}
.clay{background:#b95b3c;color:#fff8e9;--line:rgba(255,248,233,.08);--tile:#f4ca74;--tileink:#442f22;--muted:#ffe1c8}
.grid{position:absolute;inset:0;background-image:linear-gradient(var(--line) 1px,transparent 1px),linear-gradient(90deg,var(--line) 1px,transparent 1px);background-size:64px 64px;pointer-events:none}
.eyebrow{position:absolute;top:58px;left:64px;font-size:23px;font-weight:650;letter-spacing:3px;color:var(--muted)}
.logo{position:absolute;right:64px;top:48px;display:flex;gap:7px}.logo span{width:40px;height:46px;border-radius:5px;display:grid;place-items:center;background:var(--tile);color:var(--tileink);font-size:28px;font-weight:850;box-shadow:0 4px 0 rgba(0,0,0,.12)}
h1{position:absolute;left:64px;top:122px;margin:0;font-size:86px;line-height:1.04;font-weight:800;letter-spacing:-4px;max-width:952px}
.subtitle{position:absolute;left:68px;top:326px;font-size:31px;line-height:1.35;color:var(--muted);margin:0;font-weight:400}
.device{position:absolute;top:460px;left:138px;width:804px;padding:12px;border-radius:52px;background:#202820;box-shadow:0 24px 64px rgba(10,25,18,.23),0 3px 0 rgba(255,255,255,.25) inset;border:1px solid rgba(255,255,255,.28)}
.device img{display:block;width:778px;height:auto;border-radius:38px;background:#f4efdf}
.folio{position:absolute;bottom:9px;left:64px;right:64px;display:flex;justify-content:space-between;font-size:17px;letter-spacing:1px;opacity:.75}
"""

def build():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    pages = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width":1080,"height":1920}, device_scale_factor=1)
        for index, (slug, eyebrow, title, subtitle, theme) in enumerate(SCREENS, 1):
            capture = RAW / f"{slug}.png"
            if not capture.is_file():
                raise SystemExit(f"Missing native capture: {capture}. See screenshots/README.md.")
            image = base64.b64encode(capture.read_bytes()).decode()
            html = f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><title>NPAT · {escape(slug)}</title><style>{CSS}</style></head>
<body class="{theme}"><div class="grid"></div><div class="eyebrow">{eyebrow}</div><div class="logo" aria-label="NPAT">{''.join(f'<span>{c}</span>' for c in 'NPAT')}</div>
<h1>{title}</h1><p class="subtitle">{subtitle}</p><div class="device"><img src="data:image/png;base64,{image}" alt="Actual NPAT Android app with sample game data"></div>
<div class="folio"><span>ACTUAL APP SCREEN · SAMPLE GAME</span><span>{index:02d} / 06</span></div></body></html>'''
            (OUTPUT / f"{slug}.html").write_text(html, encoding="utf-8")
            page.set_content(html)
            page.evaluate("document.fonts.ready")
            page.locator(".device img").evaluate("image => image.decode()")
            healthy = page.locator(".device img").evaluate("""image => {
                if (image.naturalWidth !== 1080 || image.naturalHeight !== 1920) return false;
                const canvas = document.createElement('canvas'); canvas.width=108; canvas.height=192;
                const context=canvas.getContext('2d'); context.drawImage(image,0,0,108,192);
                const data=context.getImageData(0,0,108,192).data;
                let sum=0; for(let i=0;i<data.length;i+=4) sum+=data[i]+data[i+1]+data[i+2];
                return sum/(108*192*3)>35;
            }""")
            assert healthy, f"Blank or incorrectly sized native capture: {capture}"
            geometry = page.locator(".device").bounding_box()
            assert geometry and geometry['y'] + geometry['height'] < 1895, geometry
            page.screenshot(path=str(OUTPUT / f"{slug}.jpg"), type="jpeg", quality=98)
            pages.append((slug, title.replace("<br>", " ")))
        gallery = '''<!doctype html><html lang="en"><meta charset="utf-8"><title>NPAT store screenshots</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{margin:0;padding:40px;background:#e8e8df;color:#243028;font:16px/1.5 system-ui}h1{margin:0 0 8px}p{margin:0 0 28px}.screens{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}figure{margin:0}img{display:block;width:100%;border-radius:12px;box-shadow:0 8px 24px #0002}figcaption{padding:12px 0;font-size:15px}a{color:inherit}@media(max-width:900px){.screens{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:540px){.screens{grid-template-columns:1fr}}
</style><h1>NPAT · Store screenshot set</h1><p>Six 1080 × 1920 JPEGs, captured from the real Android UI with local sample data. Click a screenshot for the full-size file.</p><div class="screens">'''
        gallery += ''.join(f'<figure><a href="{slug}.jpg"><img src="{slug}.jpg" alt="{escape(title)}"></a><figcaption>{escape(title)}</figcaption></figure>' for slug, title in pages)
        gallery += '</div></html>'
        (OUTPUT / "index.html").write_text(gallery, encoding="utf-8")
        page.set_viewport_size({"width":1740,"height":2300})
        page.goto((OUTPUT / "index.html").as_uri())
        page.locator("img").evaluate_all("images => Promise.all(images.map(image => image.decode()))")
        page.screenshot(path=str(OUTPUT / "contact-sheet.jpg"), type="jpeg", quality=90, full_page=True)
        browser.close()
    (OUTPUT / "manifest.json").write_text(json.dumps({"width":1080,"height":1920,"format":"JPEG","screenshots":[{"file":slug+'.jpg',"title":title,"nativeCapture":'raw/'+slug+'.png'} for slug,title in pages]}, indent=2), encoding="utf-8")
    print("Created six upload-ready screenshots and a preview gallery.")

if __name__ == '__main__':
    build()

"""Build the static policy page from the same text bundled in the Android app."""
from pathlib import Path
from html import escape
import re

ROOT = Path(__file__).resolve().parents[1]
policy = (ROOT / "app/src/main/res/raw/privacy_policy.txt").read_text(encoding="utf-8")
sections = []
intro = []
current = None
for paragraph in policy.strip().split("\n\n"):
    lines = paragraph.splitlines()
    if lines[0].isupper():
        title = lines[0].capitalize()
        current = [title, []]
        sections.append(current)
        if len(lines) > 1:
            current[1].append("\n".join(lines[1:]))
    elif current is not None:
        current[1].append(paragraph)
    elif not paragraph.startswith("NPAT Privacy Policy"):
        intro.append(paragraph)

def markup(text):
    safe = escape(text)
    safe = safe.replace("https://policies.google.com/privacy", '<a href="https://policies.google.com/privacy">Google Privacy Policy</a>')
    return safe.replace("cutlerylabs@gmail.com", '<a href="mailto:cutlerylabs@gmail.com">cutlerylabs@gmail.com</a>')

def section_id(title):
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")

nav = "".join(f'<a href="#{section_id(title)}">{escape(title)}</a>' for title, _ in sections)
body = "".join(f'<section id="{section_id(title)}"><h2>{escape(title)}</h2>' + "".join(f'<p>{markup(p)}</p>' for p in paragraphs) + '</section>' for title, paragraphs in sections)
html = '''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>NPAT Privacy Policy</title><meta name="description" content="How NPAT handles information in online and offline Bluetooth games, permissions, retention, and deletion.">
<meta name="theme-color" content="#27634b"><link rel="icon" href="/favicon.svg" type="image/svg+xml">
<style>
:root{color-scheme:light;--paper:#f7f4ea;--ink:#243028;--green:#27634b;--line:#dcded0}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:24px}body{margin:0;background:var(--paper);color:var(--ink);font:17px/1.75 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}a{color:var(--green);text-underline-offset:4px}a:focus-visible{outline:3px solid var(--green);outline-offset:5px}.skip{position:absolute;left:16px;top:-100px;background:white;padding:8px}.skip:focus{top:10px}header,main,footer{max-width:1000px;margin:auto;padding:32px 24px}.brand{font-size:25px;font-weight:850;letter-spacing:.18em;text-decoration:none}.tag{font-size:13px;letter-spacing:.1em;text-transform:uppercase;color:var(--green)}h1{font-size:clamp(36px,7vw,62px);line-height:1.1;letter-spacing:-.045em;margin:20px 0}h2{font-size:23px;line-height:1.35;margin:0 0 16px}p{margin:0 0 18px}.lead{max-width:650px;font-size:19px}.date{font-size:14px;color:#59645c}.layout{display:grid;grid-template-columns:220px minmax(0,1fr);gap:48px;margin-top:40px}nav{align-self:start;position:sticky;top:24px;font-size:14px}nav a{display:block;padding:7px 0;text-decoration:none}nav a:hover{text-decoration:underline}section{border-top:1px solid var(--line);padding:28px 0 12px}footer{font-size:13px;border-top:1px solid var(--line);margin-top:24px}article{min-width:0;overflow-wrap:anywhere}.note{border-left:3px solid var(--green);padding:12px 18px;background:#edf1e8;margin:24px 0;font-size:15px}@media(max-width:700px){.layout{grid-template-columns:1fr;gap:24px}nav{position:static;border-top:1px solid var(--line);padding-top:16px}header,main,footer{padding:24px 20px}nav a{padding:5px 0}}@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
</style></head><body><a class="skip" href="#policy">Skip to policy</a>
<header><a class="brand" href="/" aria-label="NPAT privacy policy home">NPAT</a><div class="tag">Name. Place. Animal. Thing.</div></header>
<main id="policy"><p class="tag">Your information</p><h1>Privacy policy</h1><p class="date">Effective date: 16 October 2026</p>
<p class="lead">NPAT is a multiplayer word game with online and nearby Bluetooth modes. This policy describes the Android app. You do not create an account to play.</p>
<p class="note">Online games use Firebase to sync your table. Nearby Bluetooth games exchange game information directly between participating phones.</p>
<div class="layout"><nav aria-label="Policy contents">NAV</nav><article>BODY</article></div></main>
<footer>NPAT · Privacy information for online and offline play</footer></body></html>'''.replace("NAV", nav).replace("BODY", body)
output = ROOT / "privacy-site"
output.mkdir(exist_ok=True)
(output / "index.html").write_text(html, encoding="utf-8")
(output / "privacy-policy.html").write_text(html, encoding="utf-8")
print("Created privacy-site/index.html")

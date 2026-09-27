"""Reproduz o que o app faz quando a CDN do Backloggd pede a verificação em JavaScript: um navegador
(Chromium, o mesmo motor do WebView) abre o site, passa pela verificação, e os pedidos seguem fora
dele com o cookie e o User-Agent do navegador (como o OkHttp faz no app)."""
import json
import time
import urllib.request

from playwright.sync_api import sync_playwright

BASE = "https://backloggd.com/"
URLS = [
    "https://backloggd.com/autocomplete.json?query=chrono%20trigger",
    "https://backloggd.com/games/chrono-trigger/",
]


def fetch(url, headers):
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read()[:120]
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:120]


ua_plain = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
print("sem verificação:", [fetch(u, {"User-Agent": ua_plain})[0] for u in URLS])

import sys
headed = "--headed" in sys.argv
with sync_playwright() as p:
    # Como um WebView: janela de verdade (xvfb no Actions) e sem as marcas de automação.
    browser = p.chromium.launch(headless=not headed, args=["--disable-blink-features=AutomationControlled"])
    context = browser.new_context(
        user_agent="Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36",
        viewport={"width": 412, "height": 915}, is_mobile=True, has_touch=True, locale="pt-BR",
    )
    context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
    page = context.new_page()
    start = time.time()
    page.goto(BASE, wait_until="domcontentloaded")
    while time.time() - start < 40:
        title = page.title()
        if title and "Establishing a secure connection" not in title and "Just a moment" not in title:
            break
        time.sleep(0.4)
    elapsed = time.time() - start
    ua = page.evaluate("navigator.userAgent")
    cookies = context.cookies(BASE)
    print(f"título depois de {elapsed:.1f}s: {page.title()!r}; cookies: {[c['name'] for c in cookies]}")
    cookie = "; ".join(f"{c['name']}={c['value']}" for c in cookies)
    # O cookie não vale fora do navegador (a CDN o prende à conexão dele): os pedidos saem de dentro
    # da página com fetch(), como o app faz no WebView.
    inside = True
    for u in URLS:
        r = page.evaluate("""async (u) => { const r = await fetch(u, {credentials: 'include'});
            return [r.status, (await r.text()).slice(0, 160)]; }""", u)
        print("fetch() dentro da página:", r[0], u, repr(r[1][:120]))
        inside = inside and r[0] == 200 and "Establishing a secure connection" not in r[1]
    print("RESULTADO fetch na página:", "passou" if inside else "falhou")
    browser.close()

ok = True
for u in URLS:
    code, body = fetch(u, {"User-Agent": ua, "Cookie": cookie, "Accept-Language": "en-US,en;q=0.9"})
    print("com o cookie do navegador:", code, u, body)
    ok = ok and code == 200
print("RESULTADO:", "headed" if headed else "headless", "passou" if ok else "falhou")

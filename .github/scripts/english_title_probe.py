"""Compara maneiras de achar o nome em inglês de um jogo cujo título veio romanizado do japonês
("Hana to Taiyou to Ame to" -> "Flower, Sun, and Rain"). Só imprime; não reprova o build."""
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

UA = {"User-Agent": "Retrovika/0.2 (Android; https://github.com/SamwellOt/Retrovika)"}

# (título como os sites de ROM escrevem, nome em inglês esperado ou None se só existe em japonês)
CASES = [
    ("Hana to Taiyou to Ame to", "Flower, Sun, and Rain"),
    ("Seiken Densetsu 3", "Trials of Mana"),
    ("Bokujou Monogatari", "Harvest Moon"),
    ("Akumajou Dracula", "Castlevania"),
    ("Rockman X", "Mega Man X"),
    ("Hoshi no Kirby", "Kirby's Dream Land"),
    ("Zelda no Densetsu - Kamigami no Triforce", "The Legend of Zelda: A Link to the Past"),
    ("Biohazard 2", "Resident Evil 2"),
    ("Ganbare Goemon - Yuki Hime Kyuushutsu Emaki", "The Legend of the Mystical Ninja"),
    ("Kaeru no Tame ni Kane wa Naru", None),
    ("Tokimeki Memorial", None),
    ("Doraemon", None),
    ("Tobal 2", None),
    ("Sutte Hakkun", None),
    ("Mother 2 - Gyiyg no Gyakushuu", "EarthBound"),
    ("Kiki Kaikai - Nazo no Kuro Manto", "Pocky & Rocky"),
    ("Densetsu no Stafy", "The Legendary Starfy"),
    ("Chrono Trigger", None),
    ("Seiken Densetsu 2", "Secret of Mana"),
    ("Final Fantasy V", None),
    ("Shin Megami Tensei", None),
    ("Kirby no Kirakira Kids", "Kirby's Star Stacker"),
    ("Pocket Monsters Aka", "Pokémon Red and Blue"),
    ("Kaitou Saint Tail", None),
]


def get(url):
    time.sleep(0.4)  # a API da Wikimedia devolve 429 com pedidos em rajada
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
        return json.loads(r.read())


def key(s):
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c)).replace("&", " and ")
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def hepburn(s):
    """Romaji de teclado para Hepburn com mácron: "Taiyou" -> "Taiyō"."""
    for a, b in [("ou", "ō"), ("oo", "ō"), ("uu", "ū"), ("Ou", "Ō"), ("Uu", "Ū")]:
        s = s.replace(a, b)
    return s


def variants(t):
    out = [t, hepburn(t), t.replace(" - ", ": "), hepburn(t.replace(" - ", ": "))]
    return list(dict.fromkeys(out))


def wp(params):
    base = {"action": "query", "format": "json", "formatversion": "2"}
    return get("https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({**base, **params}))


def w_phrase(t):
    """Atual: busca a frase exata e fica com o primeiro artigo de jogo."""
    for q in dict.fromkeys([key(t), key(hepburn(t)).replace("ō", "o")]):
        r = wp({"generator": "search", "gsrsearch": f'"{q}"', "gsrlimit": "5", "prop": "description"})
        pages = sorted(r.get("query", {}).get("pages", []), key=lambda p: p.get("index", 99))
        for p in pages:
            if p.get("description", "").lower().endswith("game"):
                return p["title"]
    return None


def w_redirect(t):
    """Título exato ou redirecionamento (variações Hepburn) que leva a um artigo de jogo."""
    r = wp({"titles": "|".join(variants(t)), "redirects": "1", "prop": "description"})
    for p in r.get("query", {}).get("pages", []):
        if "missing" not in p and p.get("description", "").lower().endswith("game"):
            return p["title"]
    return None


def w_nearmatch(t):
    """Busca de título aproximado da Wikipedia (sem caixa e acentos, inclui redirecionamentos)."""
    for q in variants(t):
        r = wp({"list": "search", "srsearch": q, "srwhat": "nearmatch", "srlimit": "3"})
        hits = r.get("query", {}).get("search", [])
        if hits:
            title = hits[0]["title"]
            d = wp({"titles": title, "redirects": "1", "prop": "description"})["query"]["pages"][0]
            if d.get("description", "").lower().endswith("game"):
                return d["title"]
    return None


def wd_search(t):
    """Wikidata: rótulos e apelidos em todas as línguas, só itens de jogo (P31=Q7889)."""
    for q in variants(t):
        r = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
            "action": "query", "list": "search", "srsearch": f"{q} haswbstatement:P31=Q7889",
            "srlimit": "3", "format": "json"}))
        hits = r.get("query", {}).get("search", [])
        if hits:
            qid = hits[0]["title"]
            e = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
                "action": "wbgetentities", "ids": qid, "props": "labels|aliases|claims", "languages": "en|ja",
                "format": "json"}))["entities"][qid]
            label = e.get("labels", {}).get("en", {}).get("value")
            igdb = [c["mainsnak"]["datavalue"]["value"] for c in e.get("claims", {}).get("P5794", []) if "datavalue" in c["mainsnak"]]
            aliases = [a["value"] for a in e.get("aliases", {}).get("en", [])][:6]
            return f"{label} ({qid}, igdb={igdb[:1]}, aliases={aliases})"
    return None


def wd_entities(t):
    """Wikidata wbsearchentities em inglês (rótulo e apelidos)."""
    for q in variants(t):
        r = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
            "action": "wbsearchentities", "search": q, "language": "en", "type": "item", "limit": "5",
            "format": "json"}))
        for h in r.get("search", []):
            if "game" in h.get("description", "").lower():
                return f"{h.get('label')} ({h['id']}, casou por {h.get('match', {}).get('type')}: {h.get('match', {}).get('text')})"
    return None


def wd_strict(t):
    """Proposta: wbsearchentities, só casamento inteiro (rótulo ou apelido) e item de jogo, não série."""
    want = romaji(t)
    for q in variants(t):
        r = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
            "action": "wbsearchentities", "search": q, "language": "en", "type": "item", "limit": "7",
            "format": "json"}))
        for h in r.get("search", []):
            d = h.get("description", "").lower()
            if "game" not in d or "series" in d or "franchise" in d:
                continue
            if romaji(h.get("match", {}).get("text", "")) == want:
                e = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
                    "action": "wbgetentities", "ids": h["id"], "props": "claims", "format": "json"}))["entities"][h["id"]]
                igdb = [c["mainsnak"]["datavalue"]["value"] for c in e.get("claims", {}).get("P5794", []) if "datavalue" in c["mainsnak"]]
                return f"{h.get('label')} ({h['id']}, {h.get('description')}" + (f", igdb={igdb[0]})" if igdb else ")")
    return None


def romaji(s):
    k = key(s)
    for a, b in [("ou", "o"), ("oo", "o"), ("uu", "u"), ("aa", "a")]:
        k = k.replace(a, b)
    return k


def w_snippet(t):
    """Busca por frase com o trecho em volta do nome, para ver o contexto."""
    out = []
    for q in dict.fromkeys([key(t), romaji(t)]):
        r = wp({"list": "search", "srsearch": f'"{q}"', "srlimit": "3", "srprop": "snippet"})
        for h in r.get("query", {}).get("search", []):
            snip = re.sub(r"<[^>]+>", "", h["snippet"]).replace("\n", " ")
            out.append(f"{h['title']}: …{snip[:170]}…")
        if out:
            break
    return " || ".join(out) or None


import html as _html

CUE = re.compile(r"(japan|japanese|hepburn|known as|titled|released as)", re.I)
AFTER = re.compile(r"^\s*(?:[,.;:)(\[—–]|$|(?:is|was|in|for)\b)")


def phrase_with_context(t):
    """Busca por frase, mas só aceita quando o trecho mostra o nome inteiro logo depois de um
    "known in Japan as", "Japanese title", "Hepburn:" etc."""
    for q in dict.fromkeys([key(t), romaji(t)]):
        r = wp({"list": "search", "srsearch": f'"{q}"', "srlimit": "5", "srprop": "snippet"})
        hits = r.get("query", {}).get("search", [])
        if not hits:
            continue
        titles = [h["title"] for h in hits]
        desc = {p["title"]: p.get("description", "") for p in wp({"titles": "|".join(titles), "prop": "description"})["query"]["pages"]}
        for h in hits:
            if not desc.get(h["title"], "").lower().endswith("game"):
                continue
            snip = h["snippet"]
            for m in re.finditer(r'(?:<span class="searchmatch">[^<]*</span>[\s\-:–—]*)+', snip):
                before = _html.unescape(re.sub(r"<[^>]+>", "", snip[:m.start()]))[-60:]
                after = _html.unescape(re.sub(r"<[^>]+>", "", snip[m.end():]))
                if CUE.search(before) and AFTER.match(after):
                    return h["title"]
    return None


def proposal(t):
    """Proposta completa: Wikidata estrito -> Wikipedia redirecionamento -> frase com contexto."""
    return wd_strict(t) or w_redirect(t) or phrase_with_context(t)


def kotlin(t):
    """O que o app faz (WikiClient.otherTitle): um resultado de mesmo nome sem slug do IGDB não serve."""
    for fn in (wd_strict, w_redirect, phrase_with_context):
        got = fn(t)
        if got and (key(got.split(" (Q")[0]) != key(t) or "igdb" in got):
            return got
    return None


STRATEGIES = [("app", kotlin), ("frase-ctx", phrase_with_context)]

score = {name: [0, 0, 0] for name, _ in STRATEGIES}  # certo, errado, nada
for title, expected in CASES:
    print(f"\n== {title}  (esperado: {expected})")
    for name, fn in STRATEGIES:
        start = time.time()
        try:
            got = fn(title)
        except Exception as e:  # noqa: BLE001
            got = f"ERRO {e}"
        ms = int((time.time() - start) * 1000)
        found = got if isinstance(got, str) and not got.startswith("ERRO") else None
        name_found = found.split(" (Q")[0] if found else None
        if expected is None:
            ok = found is None or key(name_found) == key(title)
        else:
            ok = found is not None and key(name_found) == key(expected)
        score[name][0 if ok else (1 if found else 2)] += 1
        print(f"  {name:13} {ms:5} ms  {'OK ' if ok else '-- '} {got}")

print("\nFLOWER, SUN, AND RAIN — como o artigo mostra o nome japonês:")
for q in ['"hana to taiyo to ame to"']:
    r = wp({"list": "search", "srsearch": q, "srlimit": "5", "srprop": "snippet"})
    for h in r.get("query", {}).get("search", []):
        print(f"  trecho [{h['title']}]: {h['snippet'][:400]!r}")
text = get("https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({
    "action": "parse", "page": "Flower, Sun, and Rain", "prop": "wikitext", "format": "json", "formatversion": "2"}))["parse"]["wikitext"]
for line in text.splitlines():
    if "taiy" in line.lower() or "hana to" in line.lower():
        print(f"  wikitexto: {line[:400]!r}")
e = get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q3074435&props=labels|aliases&format=json")["entities"]["Q3074435"]
print("  wikidata rótulos:", {k: v["value"] for k, v in e.get("labels", {}).items() if k in ("en", "ja", "ja-latn", "mul")})
print("  wikidata apelidos:", {k: [a["value"] for a in v] for k, v in e.get("aliases", {}).items()})

print("\nPLACAR (certo / errado / nada):")
for name, (a, b, c) in score.items():
    print(f"  {name:13} {a:2} / {b:2} / {c:2}")

# Backloggd: a busca do próprio site acha pelo nome japonês (nomes alternativos do IGDB)?
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sys.exit(0)
with sync_playwright() as p:
    browser = p.chromium.launch(headless="--headed" not in sys.argv, args=["--disable-blink-features=AutomationControlled"])
    context = browser.new_context(
        user_agent="Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36",
        viewport={"width": 412, "height": 915}, is_mobile=True, has_touch=True, locale="pt-BR",
    )
    context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
    page = context.new_page()
    page.goto("https://backloggd.com/", wait_until="domcontentloaded")
    start = time.time()
    while time.time() - start < 40 and "Establishing a secure connection" in (page.title() or "Establishing a secure connection"):
        time.sleep(0.4)
    fetch = """async (u) => { const r = await fetch(u, {credentials: 'include'}); return [r.status, await r.text()]; }"""
    print("\nBackloggd (dentro do navegador):")
    for title, expected in CASES:
        q = urllib.parse.quote(title)
        status, body = page.evaluate(fetch, f"https://backloggd.com/autocomplete.json?query={q}")
        try:
            auto = [s["data"]["title"] for s in json.loads(body)["suggestions"]][:4]
        except Exception:  # noqa: BLE001
            auto = f"{status} {body[:60]!r}"
        status, body = page.evaluate(fetch, f"https://backloggd.com/search/games/{q}/")
        titles = re.findall(r'class="game-text-centered"[^>]*>\s*([^<]+?)\s*<', body)[:4] or \
            re.findall(r'<h3[^>]*>\s*([^<]+?)\s*</h3>', body)[:4]
        print(f"  {title[:34]:34} autocomplete={auto} | busca({status})={titles}")
    browser.close()

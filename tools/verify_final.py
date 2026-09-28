#!/usr/bin/env python3
"""
VERIFICACION FINAL - replica la logica EXACTA que ahora tiene
PelisStreamResolver.java (parche 2026-09-28):

  1. Deteccion de series: /tv/, type=tv, /embed/tv  o  season+episode en la query
  2. vs_src.php con &season=S&episode=E cuando es tv
  3. api: CONFIG.api si existe; si no, streamBase + &season=&episode= + &stream_urls
  4. Token cacheado por host (generate.php da 429 en rafaga; JWT exp-iat=14400s)
  5. Reintento: si el master responde "invalid token"/"no token"/403 -> refresca
     token y prueba el siguiente espejo (hasta 3 intentos)

Ejecutar:  python3 verify_final.py
"""
import requests, re, json, base64, subprocess, sys, time
from urllib.parse import urljoin, urlparse

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
NODE = "/tmp/dec_final.mjs"
NODE_CODE = """
const encB64 = process.argv[2], wasmUrl = process.argv[3];
const b = await fetch(wasmUrl, {headers:{Referer:'https://cloudorchestranova.com/'}}).then(r=>r.arrayBuffer()).then(x=>Buffer.from(x));
const ex = (await WebAssembly.instantiate(await WebAssembly.compile(b), {})).exports;
const enc = Buffer.from(encB64,'base64');
const ptr = ex.alloc(enc.length);
new Uint8Array(ex.memory.buffer, ptr, enc.length).set(enc);
console.log(new TextDecoder().decode(new Uint8Array(ex.memory.buffer, ptr+12, ex.decrypt(ptr, enc.length))));
"""

# cache de tokens por host (igual que TOKEN_CACHE en Java)
TOKEN_CACHE = {}
TOKEN_EXP = {}


def decrypt(enc_b64, wasm_url):
    open(NODE, "w").write(NODE_CODE)
    r = subprocess.run(["node", NODE, enc_b64, wasm_url], capture_output=True, text=True, timeout=40)
    if r.returncode != 0:
        raise RuntimeError("node/wasm: " + r.stderr[:150])
    return [u.strip() for u in r.stdout.strip().split("\n") if u.strip()]


def query_param(url, key):
    q = urlparse(url).query
    for p in q.split("&"):
        if "=" in p:
            k, v = p.split("=", 1)
            if k == key:
                return v
    return None


def token_exp_ms(token):
    try:
        payload = token.split(".")[1].replace("=", "")
        payload += "=" * (-len(payload) % 4)
        d = json.loads(base64.urlsafe_b64decode(payload))
        return max(60_000, int(d["exp"]) * 1000 - int(time.time() * 1000))
    except Exception:
        return 30 * 60 * 1000


def get_token(session, host, force_refresh=False):
    """Igual que getToken() en Java: cache por host con exp del JWT."""
    now = int(time.time() * 1000)
    if not force_refresh and host in TOKEN_CACHE and now < TOKEN_EXP.get(host, 0) - 60_000:
        return TOKEN_CACHE[host], True
    # igual que el Java: ante 429 espera 2s y reintenta una vez
    t = None
    for i in range(2):
        resp = session.get(f"https://{host}/generate.php",
                           headers={"Referer": "https://cloudorchestranova.com/"}, timeout=20)
        t = resp.text.strip().replace('"', '')
        if resp.status_code == 429 or len(t) < 50 or "<" in t:
            if i == 0:
                time.sleep(2)
                continue
            raise IOError(f"token inválido ({len(t)}B, HTTP {resp.status_code}): {t[:60]}")
        break
    TOKEN_CACHE[host] = t
    TOKEN_EXP[host] = now + token_exp_ms(t)
    return t, False


def apply_token(url, tok):
    return url.replace("__TOKEN__", tok) if "__TOKEN__" in url else url + ("&" if "?" in url else "?") + "token=" + tok


def resolve(vsembed_url, label, log=print):
    t0 = time.time()
    s = requests.Session()
    s.headers.update({"User-Agent": UA})
    steps = {}

    imdb = query_param(vsembed_url, "imdb") or re.search(r"(tt\d+)", vsembed_url).group(1)
    ds = query_param(vsembed_url, "ds_lang") or "es"
    season = query_param(vsembed_url, "season") or query_param(vsembed_url, "se")
    episode = query_param(vsembed_url, "episode") or query_param(vsembed_url, "ep")
    path = urlparse(vsembed_url).path
    is_tv = ("/tv/" in vsembed_url or "type=tv" in vsembed_url or path.rstrip("/").endswith("/tv")
             or bool(season and episode))
    vtype = "tv" if is_tv else "movie"

    vs_src = f"https://vsembed.ru/vs_src.php?type={vtype}&id={imdb}&ds_lang={ds}"
    if is_tv and season and episode:
        vs_src += f"&season={season}&episode={episode}"
    t = time.time(); r = s.get(vs_src, headers={"Referer": vsembed_url}, timeout=20); r.raise_for_status()
    cloud = r.json()["src"]; steps["vs_src"] = int((time.time() - t) * 1000)

    t = time.time(); html = s.get(cloud, headers={"Referer": vsembed_url}, timeout=20).text
    cfg = re.search(r'window\.CFG\s*=\s*(\{.+?\});', html, re.S)
    if not cfg:
        raise RuntimeError("CFG no encontrado")
    player_rel = re.search(r'"playerUrl"\s*:\s*"([^"]+)"', cfg.group(1)).group(1)
    player = urljoin(cloud, player_rel.replace("\\u0026", "&").replace("\\/", "/"))
    steps["cloud"] = int((time.time() - t) * 1000)

    t = time.time(); ph = s.get(player, headers={"Referer": cloud}, timeout=20).text
    m_ = re.search(r'window\.CONFIG\s*=\s*(\{.+?\});', ph, re.S)
    conf = m_.group(1)
    api = re.search(r'"api"\s*:\s*"([^"]+)"', conf)
    if api:
        api_url = api.group(1).replace("\\u0026", "&")
    else:
        sb = re.search(r'"streamBase"\s*:\s*"([^"]+)"', conf)
        if not sb:
            raise RuntimeError("CONFIG sin api ni streamBase")
        season = season or re.search(r'"season"\s*:\s*(\d+)', conf).group(1)
        episode = episode or re.search(r'"episode"\s*:\s*(\d+)', conf).group(1)
        api_url = sb.group(1).replace("\\u0026", "&") + f"&season={season}&episode={episode}&stream_urls"
    steps["player"] = int((time.time() - t) * 1000)

    t = time.time(); aj = s.get(api_url, headers={"Referer": "https://cloudorchestranova.com/"}, timeout=20).json()
    data = aj.get("data") or {}
    enc, wasm_url = data.get("stream_urls"), (aj.get("vs") or {}).get("wasm_url")
    if not enc or not wasm_url:
        raise RuntimeError("api sin stream_urls/wasm")
    steps["api"] = int((time.time() - t) * 1000)

    t = time.time(); raw_urls = decrypt(enc, wasm_url)
    if not raw_urls:
        raise RuntimeError("WASM decrypt vacío")
    steps["wasm"] = int((time.time() - t) * 1000)

    # ---- token cacheado + reintentos (igual que el Java) ----
    tokenized = master = None
    last_err = None
    import time as _t
    for attempt in range(3):
        if attempt > 0:
            _t.sleep(1.2)
        for i, raw in enumerate(raw_urls):
            host = urlparse(raw).netloc
            try:
                tok, cached = get_token(s, host, force_refresh=(attempt > 0))
                tz = apply_token(raw, tok)
                resp = s.get(tz, headers={"Referer": "https://cloudorchestranova.com/"}, timeout=20)
                body = resp.text
                if "#EXTM3U" not in body or "no token" in body or "invalid token" in body or resp.status_code != 200:
                    last_err = f"master inválido (espejo {i}, HTTP {resp.status_code})"
                    TOKEN_CACHE.pop(host, None)
                    continue
                master, tokenized = body, tz
                steps["token+master"] = int((time.time() - t) * 1000)
                break
            except Exception as e:
                last_err = str(e)[:80]
                TOKEN_CACHE.pop(host, None)
        if tokenized:
            break
    if not tokenized:
        raise RuntimeError(last_err or "ningún espejo válido")

    total = int((time.time() - t0) * 1000)
    variantes = master.count("#EXT-X-STREAM-INF")
    log(f"  {label}")
    log(f"    tipo={vtype}" + (f" S{season}E{episode}" if is_tv else "") + f" | {data.get('title')}")
    log(f"    pasos(ms)={steps} TOTAL={total}ms")
    log(f"    master {len(master)}B con {variantes} variantes HLS")
    log(f"    m3u8: {tokenized[:95]}...")
    assert variantes >= 1, "master sin variantes"
    return {"label": label, "type": vtype, "title": data.get("title"), "variants": variantes,
            "total_ms": total, "steps": steps, "ok": True}


TESTS = [
    ("The Mexican (movie)", "https://vsembed.ru/embed/movie?imdb=tt0236493&ds_lang=es"),
    ("#Alive (movie)", "https://vsembed.ru/embed/movie?imdb=tt10620868&ds_lang=es"),
    ("The Matrix (movie)", "https://vsembed.ru/embed/movie?imdb=tt0133093&ds_lang=es"),
    ("Siren S1E1 (serie)", "https://vsembed.ru/embed/tv?imdb=tt5615700&season=1&episode=1&ds_lang=es"),
    ("Clarkson S1E2 (serie)", "https://vsembed.ru/embed/tv?imdb=tt10541088&season=1&episode=2&ds_lang=es"),
    ("Breaking Bad S1E1 (serie)", "https://vsembed.ru/embed/tv?imdb=tt0903747&season=1&episode=1&ds_lang=es"),
]

if __name__ == "__main__":
    print("=" * 72)
    print("VERIFICACION FINAL - logica de PelisStreamResolver.java (parche 2026-09-28)")
    print("=" * 72)
    ok = fail = 0
    results = []
    for label, url in TESTS:
        try:
            r = resolve(url, label)
            results.append(r)
            ok += 1
            print("    ✅ OK")
        except Exception as e:
            results.append({"label": label, "ok": False, "err": str(e)[:150]})
            fail += 1
            print(f"    ❌ FALLO: {e}")
    print("=" * 72)
    print(f"RESULTADO: {ok} OK / {fail} FALLO")
    print(f"Tokens cacheados al final: {list(TOKEN_CACHE.keys())}")
    json.dump(results, open("/tmp/verify_final.json", "w"), indent=2, ensure_ascii=False)
    print("Detalle en /tmp/verify_final.json")

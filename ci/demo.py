#!/usr/bin/env python3
"""Demonstração gravada: o app real lê 5 fotos de etiquetas, monta o carrinho, finaliza e abre o histórico."""
import json, os, re, subprocess, sys, time

PKG = "br.com.carrinhocerto"
OUT = "demo_out"
os.makedirs(OUT, exist_ok=True)
# (arquivo, quantidade, nome esperado, preço esperado em centavos)
LABELS = [
    ("foto_etiqueta_1.jpg", 6, "Leite UHT Integral Litro", 179),
    ("foto_etiqueta_2.jpg", 1, "Arroz Branco Tipo 1 5kg", 2290),
    ("foto_etiqueta_3.jpg", 2, "Feijão Carioca Tipo 1 1kg", 749),
    ("foto_etiqueta_4.jpg", 1, "Café Torrado e Moído 500g", 1698),
    ("foto_etiqueta_5.jpg", 2, "Açúcar Refinado 1kg", 439),
]

def sh(*a, check=False):
    r = subprocess.run(["adb", "shell", *a], capture_output=True, text=True)
    if check and r.returncode: raise RuntimeError(r.stderr)
    return r.stdout

def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout

def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)

def dump():
    for _ in range(6):
        sh("rm", "-f", "/sdcard/ui.xml")
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        x = sh("cat", "/sdcard/ui.xml")
        if "<hierarchy" in x: return x
        time.sleep(1)
    return ""

def node(xml, rid=None, text=None):
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        if rid and f'resource-id="{rid}"' not in n: continue
        if text and f'text="{text}"' not in n: continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        t = re.search(r' text="([^"]*)"', n)
        return {"x": (int(b[1]) + int(b[3])) // 2, "y": (int(b[2]) + int(b[4])) // 2, "text": t[1] if t else ""}
    return None

def dismiss_anr(xml=None):
    """Fecha diálogos "isn't responding" do sistema (comuns em emulador recém-ligado)."""
    xml = xml if xml is not None else dump()
    if "responding" in xml:
        n = node(xml, text="Wait") or node(xml, text="Close app")
        if n:
            sh("input", "tap", str(n["x"]), str(n["y"]))
            time.sleep(1.5)
            return True
    return False

def tap_id(rid, wait=15, text=None):
    end = time.time() + wait
    while time.time() < end:
        x = dump()
        if dismiss_anr(x): continue
        n = node(x, rid, text)
        if n:
            sh("input", "tap", str(n["x"]), str(n["y"]))
            return n
        time.sleep(0.7)
    raise RuntimeError("não achei " + str(rid))

def top():
    out = sh("dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=.*?\{[^}]*? ([\w.]+/[\w.]+)", out) or re.search(r"mResumedActivity: .*? ([\w.]+/[\w.]+)", out)
    return m.group(1) if m else ""

def wait_for(act, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        if act in top(): return True
        dismiss_anr()
        time.sleep(0.5)
    return False

# ---------------------------------------------------------------- preparo
apk = [f for f in os.listdir("out") if f.endswith(".apk")][0]
adb("install", "-r", "-g", f"out/{apk}")
sh("pm", "clear", PKG)
sh("pm", "grant", PKG, "android.permission.CAMERA")
sh("settings", "put", "system", "show_touches", "1")
sh("cmd", "statusbar", "collapse")
sh("mkdir", "-p", "/sdcard/Pictures/etiquetas")
# o launcher do emulador costuma travar logo após o boot; não é usado na demo
sh("pm", "disable-user", "--user", "0", "com.google.android.apps.nexuslauncher")
sh("am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS")

def stage(f):
    """Deixa na galeria só a foto da etiqueta da vez (o seletor de fotos mostra a mais recente primeiro)."""
    sh("content", "delete", "--uri", "content://media/external/images/media", "--where", "_display_name LIKE 'foto_etiqueta%'")
    sh("rm", "-f", "/sdcard/Pictures/etiquetas/*")
    adb("push", f"ci/demo/{f}", f"/sdcard/Pictures/etiquetas/{f}")
    sh("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file:///sdcard/Pictures/etiquetas/{f}")
    for _ in range(20):
        q = sh("content", "query", "--uri", "content://media/external/images/media", "--projection", "_display_name")
        if f in q: return True
        sh("content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary")
        time.sleep(1.5)
    raise RuntimeError("foto não indexada: " + f)

def pick_first_photo(timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        x = dump()
        if dismiss_anr(x): continue
        for m in re.finditer(r"<node [^>]*>", x):
            n = m.group(0)
            if ("icon_thumbnail" in n or "content-desc=\"Photo" in n) and "bounds" in n:
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                sh("input", "tap", str((int(b[1]) + int(b[3])) // 2), str((int(b[2]) + int(b[4])) // 2))
                return True
        time.sleep(1)
    with open(f"{OUT}/picker_dump.xml", "w") as fo: fo.write(x)
    shot("erro_picker")
    raise RuntimeError("seletor de fotos sem miniatura")

adb("logcat", "-c")
sh("wm", "dismiss-keyguard")
# deixa o sistema assentar e fecha avisos de "não está respondendo" antes de gravar
sh("am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity")
calm = 0
for _ in range(40):
    if dismiss_anr():
        calm = 0
    else:
        calm += 1
        if calm >= 4: break
    time.sleep(2)
sh("am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity")
time.sleep(2)

# ---------------------------------------------------------------- gravação
import threading
stop_rec = threading.Event()
segments = []
current = {"p": None}

def recorder():
    # screenrecord grava no máximo 3 min por arquivo: encadeia vários segmentos
    k = 0
    while not stop_rec.is_set():
        k += 1
        name = f"/sdcard/demo_{k:02d}.mp4"
        segments.append(name)
        current["p"] = subprocess.Popen(["adb", "shell", "screenrecord", "--bit-rate", "12000000", "--time-limit", "170", name])
        current["p"].wait()

rec_thread = threading.Thread(target=recorder, daemon=True)
rec_thread.start()
results = []

import atexit
@atexit.register
def _salvar():
    shot("99_final")
    stop_rec.set()
    sh("pkill", "-INT", "screenrecord")
    try: rec_thread.join(timeout=30)
    except Exception: pass
    time.sleep(3)
    for seg in segments:
        adb("pull", seg, f"{OUT}/{os.path.basename(seg)}")
    with open(f"{OUT}/ocr_log.txt", "w") as fo:
        fo.write(subprocess.run(["adb", "logcat", "-d", "-v", "brief", "CarrinhoOCR:V", "AndroidRuntime:E", "MlKit*:V", "*:S"], capture_output=True, text=True).stdout)
    with open(f"{OUT}/logcat_full.txt", "w") as fo:
        fo.write(subprocess.run(["adb", "logcat", "-d", "-v", "time"], capture_output=True, text=True).stdout)
    with open(f"{OUT}/resultados.json", "w") as fo:
        json.dump(results, fo, ensure_ascii=False, indent=2)
    print(open(f"{OUT}/ocr_log.txt").read())
time.sleep(2.5)
shot("00_vazio")

tap_id(f"{PKG}:id/storeBar")
time.sleep(1.2)
sh("input", "text", "Supermercado%sCentral")
time.sleep(0.8)
tap_id("android:id/button1")
time.sleep(1.5)

for i, (f, qty, exp_name, exp_cents) in enumerate(LABELS, 1):
    stage(f)
    tap_id(f"{PKG}:id/btnScan")
    wait_for(".ui.ScanActivity", 15)
    time.sleep(2.0)
    tap_id(f"{PKG}:id/btnGallery")
    time.sleep(2.0)
    pick_first_photo()
    t0 = time.time()
    ok = wait_for(".ui.ItemActivity", 170)
    elapsed = round(time.time() - t0, 1)
    time.sleep(1.6)
    x = dump()
    name = (node(x, f"{PKG}:id/name") or {}).get("text", "")
    price = (node(x, f"{PKG}:id/price") or {}).get("text", "")
    banner = (node(x, f"{PKG}:id/bannerText") or {}).get("text", "")
    shot(f"{i:02d}_lido")
    results.append({"foto": f, "nome_lido": name, "preco_lido": price, "aviso": banner, "segundos": elapsed,
                    "nome_esperado": exp_name, "preco_esperado": f"{exp_cents // 100},{exp_cents % 100:02d}"})
    print(results[-1], flush=True)
    for _ in range(qty - 1):
        tap_id(f"{PKG}:id/plus")
        time.sleep(0.45)
    time.sleep(0.9)
    tap_id(f"{PKG}:id/btnSave")
    wait_for(".ui.MainActivity", 15)
    time.sleep(2.0)

shot("06_carrinho")
time.sleep(2.5)
tap_id(f"{PKG}:id/btnFinish")
time.sleep(2.2)
tap_id("android:id/button1")
time.sleep(2.5)
tap_id(f"{PKG}:id/menu_history")
time.sleep(3.5)
shot("07_historico")
x = dump()
n = node(x, f"{PKG}:id/store")
if n: sh("input", "tap", str(n["x"]), str(n["y"]))
time.sleep(3.5)
shot("08_compra")
time.sleep(1)

print("demo concluída")

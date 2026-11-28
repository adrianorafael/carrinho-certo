#!/usr/bin/env python3
"""
Teste ponta a ponta no emulador:
 1) migração: instala a 1.0.15, registra uma compra, instala a versão nova por cima e confere o histórico;
 2) lista: cria lista, usa na compra, lê etiqueta tocando no item (vínculo automático) e lê outra pelo botão principal;
 3) nota: finaliza, abre "Conferir nota", lê o cupom simulado por foto e verifica o resultado.
Gera capturas em e2e_out/. Falha (exit 1) se algo essencial não aparecer ou se houver crash.
"""
import glob, json, os, re, subprocess, sys, time

PKG = "br.com.carrinhocerto"
OUT = "e2e_out"
os.makedirs(OUT, exist_ok=True)
checks = []

def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout

def adb(*a):
    r = subprocess.run(["adb", *a], capture_output=True, text=True)
    return r.stdout + r.stderr

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

def nodes(xml):
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        t = re.search(r' text="([^"]*)"', n)
        r = re.search(r' resource-id="([^"]*)"', n)
        d = re.search(r' content-desc="([^"]*)"', n)
        if b:
            yield {"x": (int(b[1]) + int(b[3])) // 2, "y": (int(b[2]) + int(b[4])) // 2,
                   "text": t[1] if t else "", "id": r[1] if r else "", "desc": d[1] if d else ""}

def find(xml, rid=None, text=None, contains=None):
    for n in nodes(xml):
        if rid and n["id"] != rid: continue
        if text and n["text"] != text: continue
        if contains and contains.lower() not in (n["text"] + " " + n["desc"]).lower(): continue
        return n
    return None

def dismiss_anr(x=None):
    x = x if x is not None else dump()
    if "responding" in x:
        n = find(x, text="Wait") or find(x, text="Close app")
        if n:
            sh("input", "tap", str(n["x"]), str(n["y"])); time.sleep(1.5); return True
    return False

def tap(rid=None, text=None, contains=None, wait=20):
    end = time.time() + wait
    while time.time() < end:
        x = dump()
        if dismiss_anr(x): continue
        n = find(x, rid, text, contains)
        if n:
            sh("input", "tap", str(n["x"]), str(n["y"]))
            return n
        time.sleep(0.8)
    shot("erro_" + re.sub(r"\W", "_", str(rid or text or contains))[-30:])
    raise RuntimeError(f"não achei {rid or text or contains}")

def wait_text(contains, wait=200):
    end = time.time() + wait
    while time.time() < end:
        x = dump()
        if dismiss_anr(x): continue
        if contains.lower() in x.lower(): return x
        time.sleep(1.5)
    return None

def check(name, ok, detail=""):
    checks.append({"teste": name, "ok": bool(ok), "detalhe": detail})
    print(("OK   " if ok else "FALHA ") + name + (" — " + detail if detail else ""), flush=True)

def typ(text):
    sh("input", "text", text.replace(" ", "%s"))
    time.sleep(0.6)

def stage_photo(path):
    name = os.path.basename(path)
    sh("content", "delete", "--uri", "content://media/external/images/media", "--where", "_display_name LIKE 'e2e_%'")
    dst = f"/sdcard/Pictures/e2e_{name}"
    adb("push", path, dst)
    sh("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{dst}")
    for _ in range(20):
        if f"e2e_{name}" in sh("content", "query", "--uri", "content://media/external/images/media", "--projection", "_display_name"):
            return
        sh("content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary")
        time.sleep(1.5)
    raise RuntimeError("foto não indexada")

def pick_first_photo(multi=False):
    end = time.time() + 30
    while time.time() < end:
        x = dump()
        if dismiss_anr(x): continue
        for n in nodes(x):
            if "icon_thumbnail" in n["id"] or n["desc"].startswith("Photo"):
                sh("input", "tap", str(n["x"]), str(n["y"]))
                time.sleep(1.5)
                if multi:
                    tap(contains="Add", wait=10) if find(dump(), contains="Add") else tap(contains="Done", wait=10)
                return
        time.sleep(1)
    shot("erro_picker")
    raise RuntimeError("seletor de fotos sem miniatura")

def top():
    out = sh("dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=.*?\{[^}]*? ([\w.]+/[\w.]+)", out)
    return m.group(1) if m else ""

def wait_activity(act, timeout=200):
    end = time.time() + timeout
    while time.time() < end:
        if act in top(): return True
        dismiss_anr()
        time.sleep(1)
    return False

def crashed():
    log = subprocess.run(["adb", "logcat", "-d", "-b", "crash"], capture_output=True, text=True).stdout
    return PKG in log and "FATAL" in log, log

# ------------------------------------------------------------------ preparo
adb("root"); time.sleep(4); adb("wait-for-device")
sh("pm", "disable-user", "--user", "0", "com.google.android.apps.nexuslauncher")
sh("settings", "put", "system", "show_touches", "1")
sh("wm", "dismiss-keyguard")
adb("logcat", "-b", "all", "-c")

try:
    # ------------------------------------------------------------ 1) migração 1.0.15 -> nova
    old = glob.glob("old/*.apk")
    new = glob.glob("out/*.apk")[0]
    if old:
        adb("install", "-r", "-g", old[0])
        sh("am", "start", "-W", "-n", f"{PKG}/.ui.ItemActivity")
        time.sleep(3)
        dismiss_anr()
        typ("Produto antigo")
        tap(f"{PKG}:id/price"); typ("9,99")
        sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(0.8)
        tap(f"{PKG}:id/btnSave")
        time.sleep(2)
        sh("am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity"); time.sleep(2)
        tap(f"{PKG}:id/btnFinish"); time.sleep(1.5)
        typ("Mercado Antigo")
        tap("android:id/button1"); time.sleep(2)
        shot("01_versao_antiga")
        r = adb("install", "-r", "-g", new)
        check("instala versão nova por cima da 1.0.15", "Success" in r, r.strip()[-160:])
        if "Success" not in r: raise RuntimeError("atualização falhou")
        sh("am", "start", "-W", "-n", f"{PKG}/.ui.HistoryActivity"); time.sleep(3)
        x = wait_text("Mercado Antigo", 20)
        check("histórico da versão antiga continua após atualizar", x is not None)
        shot("02_historico_apos_atualizar")
    else:
        adb("install", "-r", "-g", new)
        check("versão antiga disponível para teste de migração", False, "sem old/*.apk")
    sh("pm", "grant", PKG, "android.permission.CAMERA")

    # ------------------------------------------------------------ 2) lista de compras
    sh("am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity"); time.sleep(2)
    tap(f"{PKG}:id/menu_lists"); time.sleep(1.5)
    tap(f"{PKG}:id/fab"); time.sleep(1.2)
    typ("Compra teste")
    tap("android:id/button1"); time.sleep(2)
    for name, q in [("Leite", "6"), ("Arroz 5kg", "1"), ("Detergente", "1"), ("Cafe", "1")]:
        tap(f"{PKG}:id/newName"); typ(name)
        n = tap(f"{PKG}:id/newQty"); sh("input", "keyevent", "KEYCODE_MOVE_END"); sh("input", "keyevent", "KEYCODE_DEL"); typ(q)
        tap(f"{PKG}:id/btnAdd"); time.sleep(1)
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(0.8)
    tap(f"{PKG}:id/btnUse"); time.sleep(2)
    shot("03_lista_em_uso")
    x = dump()
    check("lista com 4 itens pendentes", "4&#10;pendentes" in x)

    # item "Leite": tocar no item -> câmera -> foto da etiqueta -> vínculo automático
    stage_photo("ci/demo/foto_etiqueta_1.jpg")
    tap(contains="Leite"); time.sleep(3)
    tap(f"{PKG}:id/btnGallery"); time.sleep(2)
    pick_first_photo()
    ok = wait_activity(".ui.ItemActivity", 220)
    time.sleep(2)
    x = dump()
    link = (find(x, f"{PKG}:id/linkText") or {}).get("text", "")
    qty = (find(x, f"{PKG}:id/qty") or {}).get("text", "")
    price = (find(x, f"{PKG}:id/price") or {}).get("text", "")
    shot("04_item_da_lista")
    check("etiqueta lida a partir do item da lista", ok and price == "1,79", f"preço {price}")
    check("vinculado ao item 'Leite' com quantidade da lista", "Leite" in link and qty == "6", f"{link} / qtd {qty}")
    tap(f"{PKG}:id/btnSave"); time.sleep(2)

    # arroz pelo botão principal: o app deve sugerir/vincular o item da lista
    sh("am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity"); time.sleep(2)
    stage_photo("ci/demo/foto_etiqueta_2.jpg")
    tap(f"{PKG}:id/btnScan"); time.sleep(3)
    tap(f"{PKG}:id/btnGallery"); time.sleep(2)
    pick_first_photo()
    ok = wait_activity(".ui.ItemActivity", 220)
    time.sleep(2)
    x = dump()
    link = (find(x, f"{PKG}:id/linkText") or {}).get("text", "")
    shot("05_sugestao_lista")
    check("escaneando sem escolher item, o app reconhece o 'Arroz 5kg' da lista", "Arroz" in link, link)
    if "É o" in link:
        tap(f"{PKG}:id/linkYes"); time.sleep(1)
    tap(f"{PKG}:id/btnSave"); time.sleep(2)

    sh("am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity"); time.sleep(2)
    tap(f"{PKG}:id/listBar"); time.sleep(2.5)
    x = dump()
    shot("06_lista_andamento")
    check("lista mostra 2 no carrinho", "2&#10;no carrinho" in x or "2\nno carrinho" in x)
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)

    # ------------------------------------------------------------ 3) finalizar e conferir nota
    tap(f"{PKG}:id/btnFinish"); time.sleep(1.5)
    tap("android:id/button1"); time.sleep(1.5)
    tap(contains="Conferir nota", wait=8); time.sleep(2)
    ok = wait_activity(".ui.ReceiptActivity", 20)
    check("abre a conferência pelo aviso de compra finalizada", ok)
    stage_photo("ci/e2e/cupom_simulado.jpg")
    tap(f"{PKG}:id/btnGallery"); time.sleep(2.5)
    pick_first_photo(multi=True)
    x = wait_text("Revisar leitura (", 260)
    shot("07_nota_lida")
    m = re.search(r"Revisar leitura \((\d+) ite", x or "")
    check("cupom lido: 3 itens", m is not None and m.group(1) == "3", m.group(0) if m else "nada lido")
    tap(f"{PKG}:id/btnReview"); time.sleep(2)
    shot("08_revisao")
    tap(f"{PKG}:id/footerMain"); time.sleep(2.5)
    x = dump()
    shot("09_resultado")
    texts = [n["text"] for n in nodes(x) if n["text"]]
    with open(f"{OUT}/resultado_textos.json", "w") as fo: json.dump(texts, fo, ensure_ascii=False, indent=1)
    joined = " | ".join(texts)
    check("arroz cobrado a mais (R$ 1,00)", "Cobrado a mais" in joined and "+R$ 1,00" in joined, "")
    check("leite com preço correto", "Preço correto" in joined)
    check("detergente: na nota e não no app", "Não registrado no app" in joined)
    check("resumo: pagou R$ 1,00 a mais", "R$ 1,00 a mais" in joined)
    tap(f"{PKG}:id/footerMain"); time.sleep(2)
    tap(f"{PKG}:id/menu_history"); time.sleep(3)
    x = wait_text("nota conferida", 15) or dump()
    shot("10_historico_conferida")
    check("histórico mostra 'nota conferida'", "nota conferida" in x)
except Exception as e:
    check("fluxo completo sem travar", False, str(e))
finally:
    bad, log = crashed()
    check("sem crash do app", not bad, "")
    with open(f"{OUT}/crash.txt", "w") as fo: fo.write(log)
    with open(f"{OUT}/checks.json", "w") as fo: json.dump(checks, fo, ensure_ascii=False, indent=1)

fails = [c for c in checks if not c["ok"]]
print(f"\n{len(checks) - len(fails)}/{len(checks)} verificações OK")
sys.exit(1 if fails else 0)

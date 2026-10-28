#!/usr/bin/env python3
"""One-off helper used to move the app's Russian UI text into resources.

    apply.py MAPPING.py   — MAPPING defines FILE = "AnimTab.kt" and ENTRIES = [(line, ru, key, en[, args])]

Each literal "ru" on that line becomes s(R.string.key[, args]); values/strings.xml gets en, values-ru/ gets ru.
In ru/en text, args are written as %1$s, %2$s… (the literal's ${...} parts are replaced by them, in order).
Identical keys must carry identical text. Fails loudly if a literal isn't found where expected.
"""
import os, re, sys, runpy
from xml.sax.saxutils import escape

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main")
m = runpy.run_path(sys.argv[1])
path = os.path.join(ROOT, "java", "dev", "novacain", "ntclocks", m["FILE"])
lines = open(path, encoding="utf-8").read().split("\n")

def res_text(t):
    t = escape(t).replace("'", "\\'").replace('"', '\\"')
    if t[:1] in "@?":
        t = "\\" + t
    return t

def load(p):
    if not os.path.exists(p):
        return {}
    return dict(re.findall(r'<string name="([^"]+)"[^>]*>(.*?)</string>', open(p, encoding="utf-8").read(), re.S))

en_p = os.path.join(ROOT, "res", "values", "strings.xml")
ru_p = os.path.join(ROOT, "res", "values-ru", "strings.xml")
en, ru = load(en_p), load(ru_p)
new_en, new_ru = {}, {}

for e in m["ENTRIES"]:
    line, src, key, eng = e[:4]
    args = e[4] if len(e) > 4 else []
    i = line - 1
    lit = '"' + src + '"'
    if lit not in lines[i]:
        sys.exit(f"{m['FILE']}:{line}: {lit!r} not found in: {lines[i].strip()}")
    call = f"s(R.string.{key}" + "".join(", " + a for a in args) + ")"
    lines[i] = lines[i].replace(lit, call, 1)
    ru_text = src
    for n in range(len(args)):
        ru_text = re.sub(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_.]*", f"%{n+1}$s", ru_text, count=1)
    ru_text = ru_text.replace("\\n", "\n").replace('\\"', '"')
    eng = eng.replace("\\n", "\n")
    if args:
        # formatted strings: a literal % must be %%
        ru_text = re.sub(r"%(?!\d\$s)", "%%", ru_text)
        eng = re.sub(r"%(?!\d\$s)", "%%", eng)
    for d, k, v in ((new_en, key, eng), (new_ru, key, ru_text)):
        if k in d and d[k] != v:
            sys.exit(f"key {k} reused with different text: {d[k]!r} vs {v!r}")
        d[k] = v

open(path, "w", encoding="utf-8").write("\n".join(lines))

def append(p, items, existing):
    s = open(p, encoding="utf-8").read() if os.path.exists(p) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    add = ""
    for k, v in items.items():
        if k in existing:
            continue
        fmt = ' formatted="true"' if re.search(r"%\d\$s", v) else (' formatted="false"' if "%" in v else "")
        add += f'    <string name="{k}"{fmt}>{res_text(v).replace(chr(10), chr(92) + "n")}</string>\n'
    s = s.replace("</resources>", add + "</resources>")
    open(p, "w", encoding="utf-8").write(s)

append(en_p, new_en, en)
append(ru_p, new_ru, ru)
print(f"{m['FILE']}: {len(m['ENTRIES'])} literals, {len(new_en)} keys")

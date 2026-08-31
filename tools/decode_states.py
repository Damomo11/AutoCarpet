import re

files = [
    r"C:\Users\29452\Desktop\Lotus-26.1\src\main\java\com\xiaohe66\mc\meteor\lotus\ag_0.java",
]

pat = re.compile(r'public static final af_0 (\w+) = new af_0\(b_0\.a\("((?:[^"\\]|\\u[0-9a-fA-F]{4}|\\.)*)", (\d+)\)\)')
BASIC = {'n': '\n', 'r': '\r', 'f': '\f', 't': '\t', 'b': '\b', '0': '\0', '\\': '\\', '"': '"', "'": "'"}


def java_unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'u':
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            if n in BASIC:
                out.append(BASIC[n])
                i += 2
                continue
        out.append(c)
        i += 1
    return ''.join(out)


for f in files:
    src = open(f, encoding='utf-8').read()
    for m in pat.finditer(src):
        name, raw, seed = m.group(1), m.group(2), int(m.group(3))
        s = java_unescape(raw)
        dec = ''.join(chr(ord(c) ^ seed) for c in s)
        print(f"{name} = {dec!r}")

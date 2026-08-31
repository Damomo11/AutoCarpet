import re, sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()
lines = src.split("\n")
for i, line in enumerate(lines, 1):
    for m in re.finditer(r'b_0\.a\("((?:[^"\\]|\\u[0-9a-fA-F]{4}|\\.)*)",\s*(\d+)\)', line):
        raw, seed = m.group(1), int(m.group(2))
        s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda mm: chr(int(mm.group(1), 16)), raw)
        out = "".join(chr(ord(c) ^ seed) for c in s)
        print(f"{i}: {out}")

#!/usr/bin/env python3
"""Decompile an Android app's own classes to Java-like source, using androguard.

This is how PROTOCOL.md was produced from the vendor's PowerLift Pro app.
The APK is not included in this repository; supply your own copy.

    python3 -m venv .venv && .venv/bin/pip install androguard
    .venv/bin/python tools/decompile_apk.py powerliftpro-3.0.1.apk decompiled/

Output is for private study only. Do not redistribute it.
"""
import argparse
import os
import sys
import zipfile

from loguru import logger

logger.remove()  # androguard logs every class at debug level

from androguard.core.analysis.analysis import Analysis
from androguard.core.dex import DEX
from androguard.decompiler.decompiler import DecompilerDAD


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", help="path to the .apk file")
    parser.add_argument("out", help="directory to write .java files into")
    parser.add_argument(
        "--package",
        default="com.powerlift",
        help="only decompile classes in this package (default: %(default)s)",
    )
    args = parser.parse_args()

    prefix = "L" + args.package.replace(".", "/") + "/"
    os.makedirs(args.out, exist_ok=True)

    analysis = Analysis()
    dexes = []
    with zipfile.ZipFile(args.apk) as apk:
        for name in sorted(apk.namelist()):
            if name.startswith("classes") and name.endswith(".dex"):
                dex = DEX(apk.read(name))
                dexes.append(dex)
                analysis.add(dex)

    written = 0
    for dex in dexes:
        decompiler = DecompilerDAD(dex, analysis)
        for cls in dex.get_classes():
            name = cls.get_name()
            # Skip generated resource-id classes (R, R$id, ...).
            if not name.startswith(prefix) or "/R$" in name or name.endswith("/R;"):
                continue
            try:
                source = decompiler.get_source_class(cls)
            except Exception as exc:  # one bad class should not stop the rest
                source = f"// decompile failed: {exc!r}\n"
            path = os.path.join(args.out, name[1:-1].replace("/", ".") + ".java")
            with open(path, "w") as f:
                f.write(source)
            written += 1

    print(f"wrote {written} classes to {args.out}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())

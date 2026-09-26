#!/usr/bin/env python3
"""車両移動経費請求書（RouteSearch）の配布用ZIPを作る。

使い方:  python3 tools/build_release.py [出力フォルダ]   （省略時は dist/）

index.html・api/receipt.php・CHANGELOG.md のバージョン表記がすべて一致しているか確認してから
RouteSearch_v<バージョン>.zip を作成する。
APIキーを書いた api/config.php と、サーバーごとに設定済みのルートの .htaccess / .htpasswd は含めない。
"""
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
FILES = ['index.html', 'api/receipt.php', 'api/config.sample.php', 'api/.htaccess']
# Windowsのメモ帳でも文字化けしないよう、BOM付きUTF-8・CRLFのテキストとして同梱する
DOCS = {'README.txt': 'SETUP.md', 'CHANGELOG.txt': 'CHANGELOG.md'}
VERSION_MARKS = [
    ('index.html', r"const APP_VERSION = '([\d.]+)'"),
    ('index.html', r'class="app-ver">Ver ([\d.]+)<'),
    ('api/receipt.php', r"const APP_VERSION = '([\d.]+)'"),
    ('CHANGELOG.md', r'^## Ver ([\d.]+)'),
]


def main():
    found = {}
    for path, pattern in VERSION_MARKS:
        m = re.search(pattern, (ROOT / path).read_text(encoding='utf-8'), re.MULTILINE)
        found[f'{path}  {pattern}'] = m.group(1) if m else None
    versions = set(found.values())
    if len(versions) != 1 or None in versions:
        print('バージョン表記が一致していません:')
        for where, ver in found.items():
            print(f'  {ver}\t{where}')
        sys.exit(1)
    version = versions.pop()

    out_dir = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'dist'
    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f'RouteSearch_v{version}.zip'
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in FILES:
            z.write(ROOT / name, name)
        for name, src in DOCS.items():
            text = (ROOT / src).read_text(encoding='utf-8').replace('\r\n', '\n').replace('\n', '\r\n')
            z.writestr(name, '﻿' + text)
    print(zip_path)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""指定した住所の近くの「コインパーキング（時間貸し）」を Google Maps API で検索するスクリプト。

住所（カレンダー予定の「場所」欄の値）を受け取り、
  1. Geocoding API      で住所を緯度経度に変換
  2. Places API (New)   で近隣の駐車場をテキスト検索（既定クエリ「コインパーキング」）
  3. Distance Matrix API で住所から各駐車場までの距離・所要時間を計算
した結果を JSON で標準出力に返す。

目的は「その場所へ一時的に行き、近くに短時間停める」こと。したがって
月極・駐輪場（自転車）・車庫・専用など、一時利用に向かない候補は既定で除外する。

APIキーは環境変数 GOOGLE_MAPS_API_KEY から読み込む。

必要な Google Maps Platform API（キーで有効化が必要）:
  - Geocoding API
  - Places API (New)
  - Distance Matrix API

HTTP はこの環境のプロキシ設定をそのまま尊重するため curl 経由で呼び出す
（追加の Python 依存パッケージが不要で、どの環境でも動くようにするため）。

使い方の例:
  GOOGLE_MAPS_API_KEY=xxx python3 find_parking.py \
      --address "東京都千代田区丸の内1-9-1" --radius 500 --max 5 --mode walking
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.parse


def curl(url, method="GET", headers=None, data=None):
    """curl で HTTP リクエストを送り、レスポンスを JSON として返す。"""
    cmd = ["curl", "-sS", "-m", "30", "-X", method, url]
    for k, v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    if data is not None:
        cmd += ["-d", data]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"curl failed: {proc.stderr.strip()}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        raise RuntimeError(f"unexpected response: {proc.stdout[:300]}")


def geocode(address, key):
    q = urllib.parse.urlencode({"address": address, "language": "ja", "key": key})
    res = curl(f"https://maps.googleapis.com/maps/api/geocode/json?{q}")
    if res.get("status") != "OK":
        raise RuntimeError(
            f"Geocoding failed: {res.get('status')} {res.get('error_message', '')}".strip()
        )
    top = res["results"][0]
    loc = top["geometry"]["location"]
    return loc["lat"], loc["lng"], top["formatted_address"]


# 一時利用（コインパーキング）に向かない候補を名前から除外するためのキーワード。
# 月極（契約制）・駐輪場（自転車）・車庫・トレーラー等は短時間の来訪用途では使えない。
EXCLUDE_KEYWORDS = ("月極", "月ぎめ", "駐輪", "自転車", "車庫", "トレーラー", "バイク駐車")


def search_parking(query, lat, lng, radius, key):
    """テキスト検索で近隣のコインパーキングを探す。

    includedType の "parking" では月極も混ざるため、テキストクエリ（既定
    「コインパーキング」）で時間貸しに寄せて検索する。候補は多めに取り、
    呼び出し側で距離フィルタ・除外キーワード・件数調整を行う。
    """
    body = json.dumps(
        {
            "textQuery": query,
            "maxResultCount": 20,
            "languageCode": "ja",
            "locationBias": {
                "circle": {
                    "center": {"latitude": lat, "longitude": lng},
                    "radius": radius,
                }
            },
        }
    )
    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": key,
        "X-Goog-FieldMask": "places.displayName,places.formattedAddress,places.location",
    }
    res = curl(
        "https://places.googleapis.com/v1/places:searchText",
        method="POST",
        headers=headers,
        data=body,
    )
    if "error" in res:
        raise RuntimeError(f"Places search failed: {res['error'].get('message', res['error'])}")
    return res.get("places", [])


def is_excluded(name):
    return any(kw in (name or "") for kw in EXCLUDE_KEYWORDS)


def distances(origin_lat, origin_lng, places, mode, key):
    dests = "|".join(
        f"{p['location']['latitude']},{p['location']['longitude']}" for p in places
    )
    q = urllib.parse.urlencode(
        {
            "origins": f"{origin_lat},{origin_lng}",
            "destinations": dests,
            "mode": mode,
            "language": "ja",
            "key": key,
        }
    )
    res = curl(f"https://maps.googleapis.com/maps/api/distancematrix/json?{q}")
    if res.get("status") != "OK":
        raise RuntimeError(
            f"Distance Matrix failed: {res.get('status')} {res.get('error_message', '')}".strip()
        )
    return res["rows"][0]["elements"]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--address", required=True, help="基準となる住所（予定の場所欄の値）")
    ap.add_argument("--radius", type=int, default=600, help="検索半径（メートル、既定 600）")
    ap.add_argument("--max", type=int, default=5, help="取得する駐車場の最大件数（既定 5）")
    ap.add_argument(
        "--query",
        default="コインパーキング",
        help="検索クエリ（既定「コインパーキング」＝時間貸しを狙う）",
    )
    ap.add_argument(
        "--mode",
        default="walking",
        choices=["walking", "driving"],
        help="距離・所要時間の移動手段（既定 walking＝駐車場から目的地まで歩く前提）",
    )
    ap.add_argument(
        "--include-all",
        action="store_true",
        help="月極・駐輪場などの除外をせず、全候補を対象にする",
    )
    args = ap.parse_args()

    key = os.environ.get("GOOGLE_MAPS_API_KEY")
    if not key:
        print(
            json.dumps(
                {"error": "GOOGLE_MAPS_API_KEY is not set. references/setup.md を参照してください。"},
                ensure_ascii=False,
            )
        )
        sys.exit(1)

    try:
        lat, lng, formatted = geocode(args.address, key)
        candidates = search_parking(args.query, lat, lng, args.radius, key)

        # 一時利用に向かない候補（月極・駐輪場など）を名前で除外する。
        excluded = []
        if not args.include_all:
            kept = []
            for p in candidates:
                name = p.get("displayName", {}).get("text")
                if is_excluded(name):
                    excluded.append(name)
                else:
                    kept.append(p)
            candidates = kept

        if not candidates:
            print(
                json.dumps(
                    {
                        "base_address": formatted,
                        "query": args.query,
                        "parking": [],
                        "note": "候補が見つかりませんでした。--radius を広げるか --query を変えて再試行してください。",
                        "excluded_by_filter": excluded,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return

        elems = distances(lat, lng, candidates, args.mode, key)
        results = []
        for p, e in zip(candidates, elems):
            item = {
                "name": p.get("displayName", {}).get("text"),
                "address": p.get("formattedAddress"),
            }
            if e.get("status") == "OK":
                item["distance"] = e["distance"]["text"]
                item["duration"] = e["duration"]["text"]
                item["_distance_meters"] = e["distance"]["value"]
            else:
                item["distance"] = None
                item["duration"] = None
                item["_distance_meters"] = 10 ** 9
            results.append(item)

        # 半径内のものだけに絞り、近い順に並べて上位 max 件を返す。
        results = [r for r in results if r["_distance_meters"] <= args.radius]
        results.sort(key=lambda x: x.get("_distance_meters", 10 ** 9))
        results = results[: args.max]
        for item in results:
            item.pop("_distance_meters", None)

        out = {
            "base_address": formatted,
            "query": args.query,
            "mode": args.mode,
            "parking": results,
        }
        if not results:
            out["note"] = "半径内に該当なし。--radius を広げて再試行してください。"
        print(json.dumps(out, ensure_ascii=False, indent=2))
    except Exception as ex:  # noqa: BLE001 - surface any failure as JSON for the caller
        print(json.dumps({"error": str(ex)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()

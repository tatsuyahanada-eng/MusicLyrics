#!/usr/bin/env python3
"""指定した住所の近くの駐車場を Google Maps API で検索するスクリプト。

住所（カレンダー予定の「場所」欄の値）を受け取り、
  1. Geocoding API      で住所を緯度経度に変換
  2. Places API (New)   で近隣の駐車場を検索（名前・住所）
  3. Distance Matrix API で住所から各駐車場までの距離・所要時間を計算
した結果を JSON で標準出力に返す。

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


def search_parking(lat, lng, radius, max_results, key):
    body = json.dumps(
        {
            "includedPrimaryTypes": ["parking"],
            "maxResultCount": max_results,
            "rankPreference": "DISTANCE",
            "languageCode": "ja",
            "locationRestriction": {
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
        "https://places.googleapis.com/v1/places:searchNearby",
        method="POST",
        headers=headers,
        data=body,
    )
    if "error" in res:
        raise RuntimeError(f"Places search failed: {res['error'].get('message', res['error'])}")
    return res.get("places", [])


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
    ap.add_argument("--radius", type=int, default=500, help="検索半径（メートル、既定 500）")
    ap.add_argument("--max", type=int, default=5, help="取得する駐車場の最大件数（既定 5）")
    ap.add_argument(
        "--mode",
        default="walking",
        choices=["walking", "driving"],
        help="距離・所要時間の移動手段（既定 walking＝駐車場から目的地まで歩く前提）",
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
        places = search_parking(lat, lng, args.radius, args.max, key)
        if not places:
            print(
                json.dumps(
                    {
                        "base_address": formatted,
                        "parking": [],
                        "note": "駐車場が見つかりませんでした。--radius を広げて再試行してください。",
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return
        elems = distances(lat, lng, places, args.mode, key)
        results = []
        for p, e in zip(places, elems):
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
        results.sort(key=lambda x: x.get("_distance_meters", 10 ** 9))
        for item in results:
            item.pop("_distance_meters", None)
        print(
            json.dumps(
                {"base_address": formatted, "mode": args.mode, "parking": results},
                ensure_ascii=False,
                indent=2,
            )
        )
    except Exception as ex:  # noqa: BLE001 - surface any failure as JSON for the caller
        print(json.dumps({"error": str(ex)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()

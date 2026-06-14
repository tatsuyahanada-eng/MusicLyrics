# 大相撲ガイド データ更新ガイド

このサイトの表示内容は、すべて **`data.js`** の中のデータを書き換えるだけで更新できます。
HTML・CSS・プログラム（`app.js`）を触る必要はありません。

更新の流れ:

1. `data.js` を編集する（下記の各セクション参照）
2. `SITE_META.lastUpdated` の日付を更新する
3. 変更したファイルをレンタルサーバに再アップロードする
4. ブラウザで表示を確認する（更新が反映されない場合はスーパーリロード: Ctrl+F5）

> 💡 編集には「メモ帳」より、VS Code などのテキストエディタを推奨します（カンマ抜けなどの構文ミスに気づきやすいため）。

---

## 0. 最終更新日（必ず更新）

```js
const SITE_META = {
  lastUpdated: '2026-06-14',   // ← ここを更新日に書き換える
  dataAsOf: '令和8年（2026年）五月場所（夏場所）後',  // 収録時点の説明
  note: '…',
};
```

---

## 1. 番付を更新する（場所ごと）

各力士の `rank`（階級）と `side`（東/西）を書き換えます。

- `rank` に使える値: `横綱` `大関` `関脇` `小結` `前頭1`〜`前頭17`
- `side` に使える値: `東` `西`

```js
{
  id: 'onosato',
  name: '大の里',
  rank: '横綱',   // ← 階級
  side: '西',     // ← 東 / 西
  …
}
```

番付ページ（トップ）は、この `rank` と `side` を読んで自動でレイアウトします。
大関が3人いる場合などは、東/西の同じ枠に自動で積み重ねて表示されます。

---

## 2. 力士を追加・編集する

`RIKISHI` 配列に力士オブジェクトを追加します。`id` は他と重複しない半角英数字にしてください。

```js
{
  id: 'newrikishi',            // 一意のID（半角英数字）
  name: '新力士',               // 四股名（上）
  nameKana: 'しんりきし',
  nameRomaji: 'Shinrikishi Taro',
  realName: '山田太郎',
  rank: '前頭16',
  side: '東',
  stableId: 'isegahama',        // 所属部屋のID（STABLESのidと一致させる）
  birthplace: '東京都',
  birthplaceCountry: '日本',    // 日本以外は国名（番付などにバッジ表示）
  birthdate: '2000-01-01',
  height: 185,
  weight: 150,
  debut: '2018年3月',
  debutRank: '前相撲',
  yusho: 0,                     // 幕内最高優勝回数
  sansho: { shukunsho: 0, kantosho: 0, ginosho: 0 }, // 殊勲/敢闘/技能
  kinboshi: 0,                  // 金星
  favoriteKimarite: ['押し出し', '寄り切り'],
  profile: '紹介文。'
},
```

### 四股名の「下の名前（名乗り）」とフリガナを表示する

下の名前は `data.js` 末尾の `SHIKONA_GIVEN`、その読み（フリガナ）は `SHIKONA_GIVEN_KANA` に、
それぞれ `id: '値'` の形で追加します。上の四股名のフリガナは各力士の `nameKana` が使われます。

```js
const SHIKONA_GIVEN = {
  hoshoryu: '智勝',
  newrikishi: '太郎',     // ← 下の名前（漢字）
  …
};

const SHIKONA_GIVEN_KANA = {
  hoshoryu: 'ともかつ',
  newrikishi: 'たろう',   // ← 下の名前の読み
  …
};
```

---

## 3. 部屋を追加・編集する

`STABLES` 配列を編集します。

```js
{
  id: 'newstable',
  name: '○○部屋',
  nameRomaji: 'XX-beya',
  ichimon: '伊勢ヶ濱一門',
  location: '東京都○○区',
  master: '○○親方（元○○・○○）',
  established: '20XX年',
  description: '部屋の紹介文。'
},
```

`id` を力士の `stableId` と一致させると、力士⇔部屋が相互リンクされます。

---

## 4. 場所結果を追加する

新しい本場所が終わったら、`TOURNAMENTS` 配列の **先頭** に追加します（新しい順に並びます）。

```js
{
  id: '2026-07',
  name: '令和8年 七月場所（名古屋場所）',
  venue: 'IGアリーナ（愛知）',
  period: '2026年7月12日〜26日',
  yushoMakuuchi: { rikishiId: 'onosato', record: '14勝1敗', note: '優勝メモ' },
  summary: '場所の概要。'
},
```

- `yushoMakuuchi.rikishiId` は `RIKISHI` の `id` と一致させてください（自動でリンクされます）。

---

## 5. 巡業スケジュールを更新する

`JUNGYO` 配列を編集します。各巡業の `stops`（開催地一覧）を更新します。

```js
{
  id: '2026-summer',
  name: '令和8年 夏巡業',
  season: '夏',
  period: '2026年7月下旬〜8月',
  note: '説明文。',
  tentative: true,   // 暫定なら true（「予定」バッジが付く）。確定したら false に
  stops: [
    { date: '2026-08-01', venue: '○○体育館', pref: '静岡県', event: '' },
    { date: '2026-08-02', venue: '△△アリーナ', pref: '長野県', event: '握手会' },
  ],
},
```

- `date` は `YYYY-MM-DD` 形式（曜日は自動で計算・表示されます）。
- `event` は任意（イベント名など）。空文字 `''` でOK。
- 公式日程が出たら `venue`（会場）を「未定」から実際の会場名に書き換え、`tentative` を `false` にします。

公式の巡業日程は日本相撲協会の公式サイトで公表されます。

---

## よくあるトラブル

| 症状 | 原因と対処 |
|---|---|
| ページが真っ白／読み込み中のまま | `data.js` の構文ミス（カンマ・括弧の閉じ忘れ）。ブラウザの「デベロッパーツール > Console」にエラーが出ます。 |
| 追加した力士が番付に出ない | `rank` か `side` の表記ミス。`前頭3`（全角数字でなく半角）など、既存データと同じ書式か確認。 |
| 力士⇔部屋がリンクしない | 力士の `stableId` と部屋の `id` が一致していない。 |
| 変更が反映されない | ブラウザのキャッシュ。Ctrl+F5（Mac は Cmd+Shift+R）で再読み込み。 |

/* ============================================================
   ライブラリポータル — sample-data.js
   preview.html（デザイン確認用の静的プレビュー）専用のサンプルデータ。
   本番画面（index.php）はデータベースから取得するため読み込みません。
   ============================================================ */
window.LP_SAMPLE = [
  {
    id: 'APP-001',
    name: '交通費精算ツール',
    category: 'アプリ',
    owner: '管理部',
    createdAt: '2024-04-18',
    downloadUrl: 'https://share.welsys.example.co.jp/apps/expense-tool/v2.3.1/setup.zip',
    description: '訪問先の住所から交通費を自動計算し、月次の精算書（Excel / CSV）を出力する Windows 向けツール。関連会社共通で利用。',
    history: [
      {
        date: '2025-08-21', time: '14:30', author: '花田 達也', kind: '機能追加', version: 'v2.3.1',
        summary: 'CSV出力に「部署コード」列を追加し、経理システムへの取込を自動化',
        target: 'CSV出力機能 / 月次精算書出力',
        files: [
          'src/export/csvExporter.js : buildRow() に deptCode を追加',
          'db/schema/expense.sql : dept_code カラムを追加（NOT NULL, 既定値 000）',
          'src/ui/SettingsDialog.js : 部署コード入力欄を追加'
        ],
        ticket: 'WLS-1042'
      },
      {
        date: '2025-06-03', time: '10:05', author: '佐藤 美咲', kind: '不具合修正', version: 'v2.3.0',
        summary: '月をまたぐ出張で日付が前月に集計されてしまう不具合を修正',
        target: '月次集計処理',
        files: [
          'src/calc/monthlyAggregator.js : 集計基準日を出発日→帰着日に変更',
          'test/monthlyAggregator.test.js : 月跨ぎケースのテストを追加'
        ],
        ticket: 'WLS-0987'
      },
      {
        date: '2024-04-18', time: '09:00', author: '花田 達也', kind: '初版公開', version: 'v1.0.0',
        summary: '初版を公開（交通費入力・精算書出力の基本機能）',
        target: '全機能',
        files: ['初回リリース一式'],
        ticket: 'WLS-0501'
      }
    ]
  },
  {
    id: 'APP-002',
    name: '駐車場検索アシスタント',
    category: 'アプリ',
    owner: '情報システム室',
    createdAt: '2025-02-10',
    downloadUrl: 'https://share.welsys.example.co.jp/apps/parking-finder/v1.4.0/parking-finder.zip',
    description: 'カレンダーの予定の住所から近隣のコインパーキングを検索し、予定の説明欄へ自動追記するアシスタント。訪問業務の事前準備を短縮する目的で導入。',
    history: [
      {
        date: '2025-08-28', time: '17:12', author: '田中 亮', kind: '改善', version: 'v1.4.0',
        summary: '検索対象を時間貸し（コインパーキング）のみに限定し、月極を除外',
        target: '駐車場検索ロジック',
        files: [
          'skills/parking_finder/search.py : filter_coin_parking() を追加',
          'skills/parking_finder/SKILL.md : 検索条件の説明を更新'
        ],
        ticket: 'WLS-1101'
      },
      {
        date: '2025-07-15', time: '11:40', author: '田中 亮', kind: '機能追加', version: 'v1.3.0',
        summary: '処理完了後に「JCOM交通費精算」予定を 18:00〜19:00 で自動作成',
        target: 'カレンダー連携 / 予定作成',
        files: [
          'skills/parking_finder/calendar.py : create_expense_event() を追加',
          'skills/parking_finder/config.yaml : 既定カレンダーを「その他業務」に設定'
        ],
        ticket: 'WLS-1077'
      }
    ]
  },
  {
    id: 'PRG-101',
    name: '受注データ取込バッチ',
    category: 'プログラム',
    owner: '情報システム室',
    createdAt: '2023-11-06',
    downloadUrl: 'https://share.welsys.example.co.jp/programs/order-import/v3.1.2/order-import.tar.gz',
    description: '取引先から受領した受注CSVを基幹システムへ取り込む夜間バッチ。文字コード変換・重複チェック・エラーログ出力を行う。',
    history: [
      {
        date: '2025-08-05', time: '22:45', author: '鈴木 健一', kind: '不具合修正', version: 'v3.1.2',
        summary: 'Shift_JIS の機種依存文字が混在した場合に取込が中断する不具合を修正',
        target: '文字コード変換処理',
        files: [
          'batch/import/encoding.py : cp932 フォールバックを追加',
          'batch/import/errorLogger.py : 変換失敗行を警告ログへ出力'
        ],
        ticket: 'WLS-1055'
      },
      {
        date: '2025-03-19', time: '20:10', author: '鈴木 健一', kind: '改善', version: 'v3.1.0',
        summary: '重複チェックをインデックス化し、処理時間を約 40% 短縮',
        target: '重複チェック処理',
        files: [
          'batch/import/duplicateChecker.py : 事前ハッシュ化に変更',
          'db/index/order_idx.sql : order_no + partner_cd の複合インデックスを追加'
        ],
        ticket: 'WLS-0995'
      }
    ]
  },
  {
    id: 'DOC-201',
    name: '共有サーバー運用手順書',
    category: '資料',
    owner: '管理部',
    createdAt: '2024-01-22',
    downloadUrl: 'https://share.welsys.example.co.jp/docs/server-operation/v4/server-operation_v4.pdf',
    description: '関連会社共通の共有サーバーについて、フォルダ構成・権限申請・バックアップ手順をまとめた運用手順書（PDF）。',
    history: [
      {
        date: '2025-07-30', time: '13:20', author: '井上 由紀', kind: '資料改訂', version: 'v4.0',
        summary: 'バックアップ世代を 3 世代 → 7 世代へ変更した内容を反映',
        target: '第5章 バックアップ運用',
        files: [
          'server-operation_v4.pdf : 第5章 P.18-21 を改訂',
          'backup/rotate.sh : 保持世代の設定値を 7 に変更（プログラム側の対応）'
        ],
        ticket: 'WLS-1030'
      },
      {
        date: '2024-09-12', time: '16:00', author: '井上 由紀', kind: '資料改訂', version: 'v3.0',
        summary: '権限申請フローを電子申請に変更した内容を反映',
        target: '第3章 権限申請',
        files: ['server-operation_v3.pdf : 第3章 全面改訂'],
        ticket: 'WLS-0902'
      }
    ]
  },
  {
    id: 'DOC-202',
    name: '安否確認システム 利用マニュアル',
    category: 'マニュアル',
    owner: '総務部',
    createdAt: '2025-05-09',
    downloadUrl: 'https://share.welsys.example.co.jp/docs/safety-check/v1.2/safety-check-manual_v1.2.pdf',
    description: '災害時の安否確認システムについて、社員向けの登録手順と回答方法を説明したマニュアル。関連会社各社へ配布。',
    history: [
      {
        date: '2025-08-18', time: '09:45', author: '佐藤 美咲', kind: '資料改訂', version: 'v1.2',
        summary: 'スマートフォンアプリ版の画面刷新にあわせて画面キャプチャを差し替え',
        target: '第2章 スマートフォンからの回答',
        files: ['safety-check-manual_v1.2.pdf : 第2章 P.6-11 のキャプチャを差し替え'],
        ticket: 'WLS-1088'
      }
    ]
  },
  {
    id: 'APP-003',
    name: '勤怠打刻ダッシュボード',
    category: 'アプリ',
    owner: '人事部',
    createdAt: '2025-01-15',
    downloadUrl: 'https://share.welsys.example.co.jp/apps/attendance-dashboard/v0.9.3/dashboard.zip',
    description: '各社の勤怠打刻データを集約し、未打刻・残業超過をアラート表示する社内Webダッシュボード（試験公開中）。',
    history: [
      {
        date: '2025-08-26', time: '19:05', author: '田中 亮', kind: '機能追加', version: 'v0.9.3',
        summary: '残業 45 時間超の対象者をアラート一覧に表示する機能を追加',
        target: 'アラート判定 / ダッシュボード表示',
        files: [
          'web/src/alert/overtimeRule.ts : 45時間しきい値の判定を追加',
          'web/src/pages/Dashboard.tsx : アラートカードを追加'
        ],
        ticket: 'WLS-1096'
      },
      {
        date: '2025-06-27', time: '15:30', author: '鈴木 健一', kind: '不具合修正', version: 'v0.9.1',
        summary: '深夜勤務の打刻が翌日扱いとなり未打刻と判定される不具合を修正',
        target: '未打刻判定処理',
        files: ['web/src/calc/shiftResolver.ts : 勤務日の判定を勤務開始基準へ変更'],
        ticket: 'WLS-1024'
      }
    ]
  }
];

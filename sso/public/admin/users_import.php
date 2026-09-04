<?php
/**
 * CSV によるユーザーの一括登録。
 *
 *   username,display_name,department,email,password,status,is_admin,apps
 *
 *   - 1行目がヘッダーでも、そのまま列順のデータでも受け付ける
 *   - 既存のログインIDは「更新」として扱う（password 列が空なら変更しない）
 *   - apps 列には閲覧を許可するアプリ識別子を | 区切りで書く。* で全アプリ
 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin  = Auth::requireAdmin();
$apps   = Apps::all();
$appByKey = [];
foreach ($apps as $app) {
    $appByKey[(string) $app['app_key']] = $app;
}

const IMPORT_COLUMNS = ['username', 'display_name', 'department', 'email', 'password', 'status', 'is_admin', 'apps'];

/**
 * CSV テキストを解析し、1行ずつの取り込み計画を作る。
 * @return list<array<string,mixed>>
 */
function parse_import(string $csv, array $appByKey): array
{
    $rows   = [];
    $seen   = [];
    $handle = fopen('php://temp', 'r+');
    fwrite($handle, $csv);
    rewind($handle);

    $lineNo = 0;
    while (($cells = fgetcsv($handle, 0, ',', '"', '')) !== false) {
        $lineNo++;
        if ($cells === [null] || $cells === []) {
            continue;
        }
        $cells = array_map(static fn ($v) => trim((string) $v), $cells);
        if (implode('', $cells) === '') {
            continue;
        }
        // ヘッダー行は読み飛ばす
        if ($lineNo === 1 && strtolower($cells[0]) === 'username') {
            continue;
        }

        $row = [];
        foreach (IMPORT_COLUMNS as $i => $name) {
            $row[$name] = $cells[$i] ?? '';
        }

        $errors   = [];
        $existing = $row['username'] === '' ? null : Users::findByUsername($row['username']);
        $mode     = $existing === null ? 'create' : 'update';

        $check = Users::validate(
            [
                'username' => $row['username'],
                'email'    => $row['email'],
                'password' => $row['password'],
            ],
            $existing === null ? null : (int) $existing['id'],
            $existing === null            // 新規はパスワード必須、更新は任意
        );
        foreach ($check as $message) {
            $errors[] = $message;
        }

        // 同じCSV内でのログインID重複も弾く
        if ($row['username'] !== '') {
            $key = mb_strtolower($row['username']);
            if (isset($seen[$key])) {
                $errors[] = "同じCSV内の {$seen[$key]} 行目とログインIDが重複しています。";
            } else {
                $seen[$key] = $lineNo;
            }
        }

        $allowKeys = [];
        if ($row['apps'] === '*') {
            $allowKeys = array_keys($appByKey);
        } elseif ($row['apps'] !== '') {
            foreach (preg_split('/[|;]/', $row['apps']) as $key) {
                $key = trim((string) $key);
                if ($key === '') {
                    continue;
                }
                if (!isset($appByKey[$key])) {
                    $errors[] = "アプリ識別子「{$key}」は登録されていません。";
                    continue;
                }
                $allowKeys[] = $key;
            }
        }

        $rows[] = [
            'line'      => $lineNo,
            'mode'      => $mode,
            'data'      => $row,
            'allowKeys' => $allowKeys,
            'errors'    => $errors,
            'user_id'   => $existing === null ? null : (int) $existing['id'],
        ];
    }
    fclose($handle);
    return $rows;
}

$csv     = '';
$parsed  = [];
$stage   = 'input';   // input → preview → done

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    Csrf::check();
    $csv = (string) ($_POST['csv'] ?? '');

    if (isset($_FILES['csv_file']) && is_uploaded_file((string) ($_FILES['csv_file']['tmp_name'] ?? ''))) {
        $uploaded = (string) file_get_contents((string) $_FILES['csv_file']['tmp_name']);
        // Excel から出した Shift_JIS の CSV も受け付ける
        if (!mb_check_encoding($uploaded, 'UTF-8')) {
            $uploaded = (string) mb_convert_encoding($uploaded, 'UTF-8', 'SJIS-win, UTF-8');
        }
        $csv = $uploaded;
    }
    $csv    = preg_replace("/\xEF\xBB\xBF/", '', $csv) ?? $csv;   // BOM を除去
    $parsed = parse_import($csv, $appByKey);

    $hasError = false;
    foreach ($parsed as $row) {
        if ($row['errors'] !== []) {
            $hasError = true;
            break;
        }
    }

    if (post('action') === 'execute' && !$hasError && $parsed !== []) {
        $created = 0;
        $updated = 0;
        Db::transaction(static function () use ($parsed, $appByKey, $admin, &$created, &$updated): void {
            foreach ($parsed as $row) {
                $data  = $row['data'];
                $input = [
                    'username'             => $data['username'],
                    'email'                => $data['email'],
                    'display_name'         => $data['display_name'],
                    'department'           => $data['department'],
                    'status'               => $data['status'] === 'suspended' ? 'suspended' : 'active',
                    'is_admin'             => in_array(strtolower($data['is_admin']), ['1', 'yes', 'true', 'y'], true),
                    'must_change_password' => true,
                    'password'             => $data['password'],
                ];

                if ($row['mode'] === 'create') {
                    $userId = Users::create($input, (int) $admin['id']);
                    $created++;
                } else {
                    $userId = (int) $row['user_id'];
                    Users::update($userId, $input, (int) $admin['id']);
                    if ($data['password'] !== '') {
                        Users::setPassword($userId, $data['password'], (int) $admin['id'], true);
                    }
                    $updated++;
                }

                // apps 列が空の行は、権限に触れない（既存設定を壊さない）
                if ($data['apps'] !== '') {
                    $effects = [];
                    foreach ($appByKey as $key => $app) {
                        $effects[(int) $app['id']] = in_array($key, $row['allowKeys'], true) ? 'allow' : '';
                    }
                    Permissions::replaceForUser($userId, $effects, (int) $admin['id']);
                }
            }
        });
        Audit::log('user.import', (int) $admin['id'], null, null, ['created' => $created, 'updated' => $updated]);
        flash('success', "一括登録が完了しました。新規 {$created} 件 / 更新 {$updated} 件。");
        redirect(Config::baseUrl('admin/users.php'));
    }

    $stage = 'preview';
}

View::head('CSV一括登録', $admin, 'users');
?>
<main class="container">
  <?php View::pageTitle('ユーザーの一括登録（CSV）', 'まとめて登録・更新し、そのままアプリの閲覧許可も設定できます。'); ?>

  <div class="card">
    <h2 class="card__title">書式</h2>
    <p class="card__note">
      1行1ユーザー。列の順番は次のとおりです（ヘッダー行はあってもなくても構いません）。
    </p>
    <textarea readonly rows="5">username,display_name,department,email,password,status,is_admin,apps
t.hanada,花田 達也,情報システム,t.hanada@example.com,Initial#2026pw,active,1,*
y.suzuki,鈴木 陽子,営業,y.suzuki@example.com,Initial#2026pw,active,0,lyrics|kintai</textarea>
    <ul class="muted">
      <li><strong>status</strong>：<code>active</code> または <code>suspended</code>（空欄は active）</li>
      <li><strong>is_admin</strong>：<code>1</code> でこの管理画面を使える管理者になります</li>
      <li><strong>apps</strong>：閲覧を許可するアプリ識別子を <code>|</code> 区切りで。<code>*</code> で全アプリ。
          <strong>空欄なら権限は変更しません</strong></li>
      <li>既に存在するログインIDは「更新」になります。password 列が空なら現在のパスワードのままです</li>
      <li>登録したユーザーには、初回ログイン時にパスワード変更を求めます</li>
    </ul>
    <?php if ($apps !== []): ?>
      <p class="muted">
        現在登録されているアプリ識別子：
        <?php foreach ($apps as $i => $app): ?><?= $i ? ' / ' : '' ?><code><?= h($app['app_key']) ?></code><?php endforeach; ?>
      </p>
    <?php endif; ?>
  </div>

  <form method="post" enctype="multipart/form-data">
    <?= Csrf::field() ?>
    <div class="card">
      <h2 class="card__title">CSV の入力</h2>
      <div class="field">
        <label class="field__label" for="csv">貼り付け</label>
        <textarea id="csv" name="csv" rows="10" placeholder="username,display_name,..."><?= h($csv) ?></textarea>
      </div>
      <div class="field">
        <label class="field__label" for="csv_file">またはファイルを選択（.csv）</label>
        <input id="csv_file" name="csv_file" type="file" accept=".csv,text/csv">
        <div class="field__hint">UTF-8 / Shift_JIS のどちらでも読み込めます。</div>
      </div>
      <button class="btn" type="submit" name="action" value="preview">内容を確認する</button>
      <a class="btn btn--ghost" href="<?= h(Config::baseUrl('admin/users.php')) ?>">一覧へ戻る</a>
    </div>

    <?php if ($stage === 'preview'): ?>
      <?php
        $errorCount = 0;
        foreach ($parsed as $row) {
            if ($row['errors'] !== []) {
                $errorCount++;
            }
        }
      ?>
      <div class="card">
        <h2 class="card__title">確認</h2>
        <?php if ($parsed === []): ?>
          <p class="notice notice--warn">読み取れる行がありませんでした。</p>
        <?php elseif ($errorCount > 0): ?>
          <p class="notice notice--warn">
            <?= $errorCount ?> 行にエラーがあります。修正してから、もう一度確認してください。
          </p>
        <?php else: ?>
          <p class="notice"><?= count($parsed) ?> 行を取り込みます。内容を確認して「実行する」を押してください。</p>
        <?php endif; ?>

        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr><th>行</th><th>処理</th><th>ログインID</th><th>氏名</th><th>所属</th>
                  <th>状態</th><th>管理者</th><th class="wrap">許可アプリ</th><th class="wrap">エラー</th></tr>
            </thead>
            <tbody>
            <?php foreach ($parsed as $row): ?>
              <tr>
                <td><?= (int) $row['line'] ?></td>
                <td><?= $row['mode'] === 'create'
                         ? '<span class="badge badge--allow">新規</span>'
                         : '<span class="badge badge--default">更新</span>' ?></td>
                <td><?= h($row['data']['username']) ?></td>
                <td class="wrap"><?= h($row['data']['display_name']) ?></td>
                <td class="wrap"><?= h($row['data']['department']) ?></td>
                <td><?= $row['data']['status'] === 'suspended' ? '停止中' : '有効' ?></td>
                <td><?= in_array(strtolower($row['data']['is_admin']), ['1','yes','true','y'], true) ? '○' : '' ?></td>
                <td class="wrap">
                  <?= $row['data']['apps'] === ''
                        ? '<span class="muted">変更しない</span>'
                        : h(implode(', ', $row['allowKeys'])) ?>
                </td>
                <td class="wrap"><span style="color:#a63232"><?= h(implode(' / ', $row['errors'])) ?></span></td>
              </tr>
            <?php endforeach; ?>
            </tbody>
          </table>
        </div>

        <?php if ($parsed !== [] && $errorCount === 0): ?>
          <p style="margin-top:16px">
            <button class="btn" type="submit" name="action" value="execute"
                    onclick="return confirm('<?= count($parsed) ?> 行を取り込みます。よろしいですか？');">
              実行する
            </button>
          </p>
        <?php endif; ?>
      </div>
    <?php endif; ?>
  </form>
</main>
<?php View::foot();

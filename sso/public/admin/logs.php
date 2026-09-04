<?php
/** 監査ログの閲覧。 */
declare(strict_types=1);
require __DIR__ . '/../../lib/bootstrap.php';

$admin = Auth::requireAdmin();
$limit = max(20, min(500, (int) query('limit', '100')));
$logs  = Audit::recent($limit);

$labels = [
    'login.success'             => 'ログイン成功',
    'login.failed'              => 'ログイン失敗',
    'login.locked'              => 'ロック中のログイン試行',
    'login.suspended'           => '停止中アカウントのログイン試行',
    'logout'                    => 'ログアウト',
    'logout.by_app'             => 'アプリからのログアウト',
    'sso.ticket_issued'         => 'SSOチケット発行',
    'sso.validated'             => 'SSOチケット引換',
    'sso.denied'                => 'SSO 閲覧拒否',
    'sso.bad_signature'         => '署名不一致（要確認）',
    'user.create'               => 'ユーザー追加',
    'user.update'               => 'ユーザー更新',
    'user.delete'               => 'ユーザー削除',
    'user.unlock'               => 'ロック解除',
    'user.import'               => 'CSV一括登録',
    'user.password_reset'       => 'パスワード再設定（管理者）',
    'user.password_change_self' => 'パスワード変更（本人）',
    'permission.set'            => '閲覧許可の変更',
    'app.create'                => 'アプリ登録',
    'app.update'                => 'アプリ更新',
    'app.delete'                => 'アプリ削除',
    'app.secret_rotate'         => '共有秘密鍵の再生成',
    'app.link_user'             => 'アプリ内ユーザーIDの対応付け',
];

View::head('監査ログ', $admin, 'logs');
?>
<main class="container">
  <?php View::pageTitle('監査ログ', '誰が・誰に対して・何をしたかの記録です。'); ?>

  <div class="card">
    <form method="get" class="row">
      <div class="field" style="flex:0 1 180px;margin:0">
        <label class="field__label" for="limit">表示件数</label>
        <select id="limit" name="limit">
          <?php foreach ([100, 200, 500] as $n): ?>
            <option value="<?= $n ?>" <?= $limit === $n ? 'selected' : '' ?>><?= $n ?> 件</option>
          <?php endforeach; ?>
        </select>
      </div>
      <button class="btn btn--ghost" type="submit">更新</button>
    </form>
  </div>

  <div class="card">
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr><th>日時</th><th>操作</th><th>操作者</th><th>対象</th><th>アプリ</th><th class="wrap">詳細</th><th>IP</th></tr>
        </thead>
        <tbody>
        <?php if ($logs === []): ?>
          <tr><td colspan="7" class="muted">記録はまだありません。</td></tr>
        <?php endif; ?>
        <?php foreach ($logs as $log): ?>
          <tr>
            <td><?= h($log['created_at']) ?></td>
            <td><?= h($labels[$log['action']] ?? $log['action']) ?></td>
            <td><?= h($log['actor_name'] ?: '—') ?></td>
            <td><?= h($log['target_name'] ?: '—') ?></td>
            <td><?= h($log['app_name'] ?: '—') ?></td>
            <td class="wrap muted"><?= h((string) ($log['detail'] ?? '')) ?></td>
            <td class="muted"><?= h($log['ip']) ?></td>
          </tr>
        <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  </div>
</main>
<?php View::foot();

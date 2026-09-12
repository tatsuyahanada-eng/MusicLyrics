<?php
declare(strict_types=1);
require_once __DIR__ . '/includes/auth.php';
lp_session_start();
lp_logout();
header('Location: login.php');

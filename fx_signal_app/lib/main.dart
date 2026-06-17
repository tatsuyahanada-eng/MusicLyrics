import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'background/notifier.dart';
import 'background/worker.dart';
import 'presentation/dashboard/dashboard_screen.dart';
import 'presentation/history/history_screen.dart';
import 'presentation/settings/settings_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // UIの起動を最優先。先にアプリを表示する。
  runApp(const ProviderScope(child: FxApp()));
  // 通知・バックグラウンド監視の初期化は後回し＆失敗しても起動を妨げない。
  _initServices();
}

Future<void> _initServices() async {
  try {
    await LocalNotifier.init();
  } catch (_) {/* 通知初期化失敗でもアプリは動かす */}
  try {
    await BackgroundScheduler.init();
  } catch (_) {/* バックグラウンド初期化失敗でもアプリは動かす */}
}

class FxApp extends StatelessWidget {
  const FxApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FXシグナル',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF3A5BD9),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF7F8FC),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF3A5BD9),
          foregroundColor: Colors.white,
          elevation: 0,
          centerTitle: false,
          titleTextStyle: TextStyle(
            color: Colors.white,
            fontSize: 20,
            fontWeight: FontWeight.bold,
          ),
        ),
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: Colors.white,
          indicatorColor: const Color(0xFF3A5BD9).withValues(alpha: 0.15),
        ),
      ),
      home: const HomeShell(),
    );
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  static const _pages = [
    DashboardScreen(),
    HistoryScreen(),
    SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _pages[_index],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(
              icon: Icon(Icons.dashboard_outlined), label: 'ダッシュボード'),
          NavigationDestination(icon: Icon(Icons.history), label: '履歴'),
          NavigationDestination(
              icon: Icon(Icons.settings_outlined), label: '設定'),
        ],
      ),
    );
  }
}

import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/theme/app_theme.dart';
import 'services/vpn_service.dart';
import 'services/connectivity_service.dart';
import 'ui/bloc/vpn/vpn_cubit.dart';
import 'ui/bloc/config/config_cubit.dart';
import 'ui/screens/dashboard_screen.dart';
import 'ui/widgets/notifications/notification_handler.dart';

/// Enterprise VPN Application Entry Point
/// Production-ready VPN client with Samsung One UI 6 design language
void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize secure storage
  const secureStorage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  // Initialize shared preferences
  final sharedPreferences = await SharedPreferences.getInstance();

  // Set preferred orientations for consistent experience
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // Configure system UI overlay style
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.dark,
      statusBarBrightness: Brightness.light,
      systemNavigationBarColor: Color(0xFFF7F7F7),
      systemNavigationBarIconBrightness: Brightness.dark,
    ),
  );

  // Enable edge-to-edge mode
  SystemChrome.setEnabledSystemUIMode(
    SystemUiMode.edgeToEdge,
    overlays: [SystemUiOverlay.top, SystemUiOverlay.bottom],
  );

  runApp(
    MultiProvider(
      providers: [
        Provider<FlutterSecureStorage>.value(value: secureStorage),
        Provider<SharedPreferences>.value(value: sharedPreferences),
        Provider<VpnService>(
          create: (context) => VpnService(
            secureStorage: secureStorage,
            preferences: sharedPreferences,
          ),
          dispose: (_, service) => service.dispose(),
        ),
        Provider<ConnectivityService>(
          create: (context) => ConnectivityService(),
          dispose: (_, service) => service.dispose(),
        ),
      ],
      child: MultiBlocProvider(
        providers: [
          BlocProvider<VpnCubit>(
            create: (context) => VpnCubit(
              vpnService: context.read<VpnService>(),
              connectivityService: context.read<ConnectivityService>(),
            )..initialize(),
          ),
          BlocProvider<ConfigCubit>(
            create: (context) => ConfigCubit(
              secureStorage: secureStorage,
              preferences: sharedPreferences,
            )..loadConfiguration(),
          ),
        ],
        child: const EnterpriseVpnApp(),
      ),
    ),
  );
}

/// Main Application Widget
class EnterpriseVpnApp extends StatefulWidget {
  const EnterpriseVpnApp({super.key});

  @override
  State<EnterpriseVpnApp> createState() => _EnterpriseVpnAppState();
}

class _EnterpriseVpnAppState extends State<EnterpriseVpnApp>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);

    // Handle app lifecycle changes for VPN service
    switch (state) {
      case AppLifecycleState.resumed:
        context.read<VpnCubit>().onAppResumed();
        break;
      case AppLifecycleState.paused:
        context.read<VpnCubit>().onAppPaused();
        break;
      case AppLifecycleState.detached:
        context.read<VpnCubit>().onAppDetached();
        break;
      case AppLifecycleState.inactive:
      case AppLifecycleState.hidden:
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Enterprise VPN',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.system,
      home: const DashboardScreen(),
      builder: (context, child) {
        // Global error handling wrapper
        return MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.noScaling,
          ),
          child: NotificationHandler(
            child: child ?? const SizedBox.shrink(),
          ),
        );
      },
      scrollBehavior: const _AppScrollBehavior(),
    );
  }
}

/// Custom scroll behavior for smooth 120Hz animations
class _AppScrollBehavior extends MaterialScrollBehavior {
  const _AppScrollBehavior();

  @override
  Set<PointerDeviceKind> get dragDevices => {
        PointerDeviceKind.touch,
        PointerDeviceKind.mouse,
        PointerDeviceKind.trackpad,
        PointerDeviceKind.stylus,
        PointerDeviceKind.invertedStylus,
      };

  @override
  Widget buildScrollbar(
    BuildContext context,
    Widget child,
    ScrollableDetails details,
  ) {
    return child;
  }

  @override
  Widget buildOverscrollIndicator(
    BuildContext context,
    Widget child,
    ScrollableDetails details,
  ) {
    return child;
  }
}

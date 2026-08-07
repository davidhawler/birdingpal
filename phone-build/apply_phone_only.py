#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import shutil
import sys


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match for {label}; found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    repo = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    android = repo / "android"
    kit = pathlib.Path(__file__).resolve().parent

    gradle_path = android / "app" / "build.gradle.kts"
    text = gradle_path.read_text()
    if 'val enableBle = readConfigValue("ENABLE_BLE", "false")' not in text:
        text = replace_once(
            text,
            'val birdnetAnalyzerUrl = readConfigValue("BIRDNET_ANALYZER_URL", "")\n',
            'val birdnetAnalyzerUrl = readConfigValue("BIRDNET_ANALYZER_URL", "")\n'
            'val enableBle = readConfigValue("ENABLE_BLE", "false").toBooleanStrictOrNull() ?: false\n',
            "ENABLE_BLE config",
        )
        text = replace_once(
            text,
            '        buildConfigField("String", "BIRDNET_ANALYZER_URL", quoteForBuildConfig(birdnetAnalyzerUrl))\n',
            '        buildConfigField("String", "BIRDNET_ANALYZER_URL", quoteForBuildConfig(birdnetAnalyzerUrl))\n'
            '        buildConfigField("boolean", "ENABLE_BLE", enableBle.toString())\n',
            "ENABLE_BLE BuildConfig",
        )
        gradle_path.write_text(text)

    controller_path = android / "app" / "src" / "main" / "java" / "com" / "openaiexperiments" / "birdingbuddy" / "nativeapp" / "net" / "ExperimentControllers.kt"
    text = controller_path.read_text()
    if 'getSharedPreferences("birdingpal_settings"' not in text:
        text = replace_once(
            text,
            '    private val apiKey: String\n        get() = BuildConfig.OPENAI_API_KEY.trim()\n',
            '    private val apiKey: String\n'
            '        get() {\n'
            '            val stored =\n'
            '                context.getSharedPreferences("birdingpal_settings", Context.MODE_PRIVATE)\n'
            '                    .getString("openai_api_key", "")\n'
            '                    .orEmpty()\n'
            '                    .trim()\n'
            '            return stored.ifBlank { BuildConfig.OPENAI_API_KEY.trim() }\n'
            '        }\n',
            "runtime API key",
        )
        text = replace_once(
            text,
            '            setStatus("OPENAI_API_KEY is missing. Rebuild app with OPENAI_API_KEY set.")\n            addLog("Missing OPENAI_API_KEY in BuildConfig.")\n',
            '            setStatus("Open Settings and add an OpenAI API key.")\n'
            '            addLog("OpenAI API key is not configured.")\n',
            "missing API key message",
        )
        text = replace_once(
            text,
            '                .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")\n',
            '                .header("Authorization", "Bearer $apiKey")\n',
            "HTTP API key",
        )
        text = replace_once(
            text,
            '    protected fun birdnetAnalyzerUrl(path: String): String {\n'
            '        val baseRaw = birdnetAnalyzerUrlProvider().trim()\n'
            '        val base =\n'
            '            if (baseRaw.isBlank()) {\n'
            '                BuildConfig.BIRDNET_ANALYZER_URL.trim()\n'
            '            } else {\n'
            '                baseRaw\n'
            '            }\n',
            '    protected fun birdnetAnalyzerUrl(path: String): String {\n'
            '        val storedBase =\n'
            '            context.getSharedPreferences("birdingpal_settings", Context.MODE_PRIVATE)\n'
            '                .getString("birdnet_analyzer_url", "")\n'
            '                .orEmpty()\n'
            '                .trim()\n'
            '        val baseRaw = birdnetAnalyzerUrlProvider().trim().ifBlank { storedBase }\n'
            '        val base =\n'
            '            if (baseRaw.isBlank()) {\n'
            '                BuildConfig.BIRDNET_ANALYZER_URL.trim()\n'
            '            } else {\n'
            '                baseRaw\n'
            '            }\n',
            "runtime BirdNET URL",
        )
        controller_path.write_text(text)

    service_path = android / "app" / "src" / "main" / "java" / "com" / "openaiexperiments" / "birdingbuddy" / "nativeapp" / "background" / "BirdBuddySessionService.kt"
    text = service_path.read_text()
    if 'BuildConfig.ENABLE_BLE' not in text:
        text = replace_once(
            text,
            '                    bleManager.retryNow()\n',
            '                    if (BuildConfig.ENABLE_BLE) {\n'
            '                        bleManager.retryNow()\n'
            '                    } else {\n'
            '                        BirdBuddySessionStore.appendLog("Bluetooth is disabled in phone-only mode.")\n'
            '                    }\n',
            "BLE retry",
        )
        text = replace_once(
            text,
            '        controller.connect()\n        bleManager.start()\n',
            '        controller.connect()\n'
            '        if (BuildConfig.ENABLE_BLE) {\n'
            '            bleManager.start()\n'
            '        } else {\n'
            '            BirdBuddySessionStore.setBleState(false, "Phone-only mode")\n'
            '        }\n',
            "BLE start",
        )
        text = replace_once(
            text,
            '        bleManager.stop()\n        controller.dispose()\n',
            '        if (BuildConfig.ENABLE_BLE) {\n'
            '            bleManager.stop()\n'
            '        }\n'
            '        controller.dispose()\n',
            "BLE stop",
        )
        service_path.write_text(text)

    main_path = android / "app" / "src" / "main" / "java" / "com" / "openaiexperiments" / "birdingbuddy" / "MainActivity.kt"
    text = main_path.read_text()
    text = text.replace('import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyActions\n', '')
    text = text.replace('import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionService\n', '')
    text = text.replace(
        'import com.openaiexperiments.birdingbuddy.nativeapp.ui.OaiVoiceNativeApp',
        'import com.openaiexperiments.birdingbuddy.nativeapp.ui.PhoneOnlyApp',
    )
    text = text.replace('        BirdBuddySessionService.enqueueAction(this, BirdBuddyActions.ACTION_ARM)\n\n', '')
    text = text.replace('            OaiVoiceNativeApp()', '            PhoneOnlyApp()')
    main_path.write_text(text)

    ui_dir = android / "app" / "src" / "main" / "java" / "com" / "openaiexperiments" / "birdingbuddy" / "nativeapp" / "ui"
    ui_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(kit / "PhoneOnlyApp.kt", ui_dir / "PhoneOnlyApp.kt")

    manifest_path = android / "app" / "src" / "main" / "AndroidManifest.xml"
    text = manifest_path.read_text()
    for block in [
        '    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />\n',
        '    <uses-permission\n        android:name="android.permission.BLUETOOTH_SCAN"\n        android:usesPermissionFlags="neverForLocation" />\n',
        '    <uses-permission android:name="android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND" />\n',
        '    <uses-permission\n        android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" />\n',
        '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n',
        '\n    <uses-feature\n        android:name="android.hardware.bluetooth_le"\n        android:required="false" />\n',
        '\n        <receiver\n            android:name=".nativeapp.background.BirdBuddyBootReceiver"\n            android:enabled="true"\n            android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.BOOT_COMPLETED" />\n            </intent-filter>\n        </receiver>\n',
    ]:
        text = text.replace(block, '')
    text = text.replace('android:allowBackup="true"', 'android:allowBackup="false"')
    text = text.replace(
        'android:foregroundServiceType="dataSync"\n            android:stopWithTask="false"',
        'android:foregroundServiceType="dataSync|microphone"\n            android:stopWithTask="true"',
    )
    manifest_path.write_text(text)

    print("BirdingPal phone-only overlay applied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

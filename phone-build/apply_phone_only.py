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

    contract_path = android / "app" / "src" / "main" / "java" / "com" / "openaiexperiments" / "birdingbuddy" / "nativeapp" / "background" / "BirdBuddySessionContract.kt"
    text = contract_path.read_text()
    if "candidateConfidencePercent" not in text:
        text = replace_once(
            text,
            '    val bleLastPacketHex: String = "",\n    val lastTriggerSource: BirdBuddyTriggerSource? = null,\n',
            '    val bleLastPacketHex: String = "",\n'
            '    val candidateName: String? = null,\n'
            '    val candidateConfidencePercent: Int? = null,\n'
            '    val candidateSource: String? = null,\n'
            '    val lastTriggerSource: BirdBuddyTriggerSource? = null,\n',
            "identification state fields",
        )
        contract_path.write_text(text)

    store_path = android / "app" / "src" / "main" / "java" / "com" / "openaiexperiments" / "birdingbuddy" / "nativeapp" / "background" / "BirdBuddySessionStore.kt"
    text = store_path.read_text()
    if "fun setIdentification(" not in text:
        text = replace_once(
            text,
            '    fun appendLog(message: String) {\n',
            '    fun setIdentification(\n'
            '        name: String?,\n'
            '        confidencePercent: Int? = null,\n'
            '        source: String? = null\n'
            '    ) {\n'
            '        _state.update {\n'
            '            it.copy(\n'
            '                candidateName = name,\n'
            '                candidateConfidencePercent = confidencePercent,\n'
            '                candidateSource = source\n'
            '            )\n'
            '        }\n'
            '    }\n\n'
            '    fun appendLog(message: String) {\n',
            "identification state setter",
        )
        store_path.write_text(text)

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

    if "BirdBuddySessionStore" not in text:
        text = replace_once(
            text,
            'import com.openaiexperiments.birdingbuddy.BuildConfig\n',
            'import com.openaiexperiments.birdingbuddy.BuildConfig\n'
            'import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionStore\n',
            "session store import",
        )

    if "lastBirdCallCandidate" not in text:
        text = replace_once(
            text,
            '    private var captureMode: CaptureMode? = null\n',
            '    private var captureMode: CaptureMode? = null\n'
            '    private var lastBirdCallCandidate: BirdCandidate? = null\n',
            "last bird-call candidate",
        )

        text = replace_once(
            text,
            '                                    "You are a bird encyclopedia. Respond with a JSON object containing: name, commonName, family, habitat, size, diet, notes. Keep each field brief (1-2 sentences max)."\n',
            '                                    "You are a bird encyclopedia. Respond with a JSON object containing: name, commonName, scientificName, family, habitat, size, diet, notes. Keep each field brief (1-2 sentences max)."\n',
            "scientific name enrichment",
        )

        text = replace_once(
            text,
            '        val entry =\n'
            '            JSONObject()\n'
            '                .put("name", name)\n'
            '                .put("addedAt", System.currentTimeMillis())\n'
            '                .put("info", info)\n\n'
            '        entries.put(entry)\n',
            '        val coords = getLastKnownCoordinates(context)\n'
            '        val matchedConfidence =\n'
            '            lastBirdCallCandidate\n'
            '                ?.takeIf { it.name.equals(name, ignoreCase = true) }\n'
            '                ?.percent\n\n'
            '        val entry =\n'
            '            JSONObject()\n'
            '                .put("name", name)\n'
            '                .put("addedAt", System.currentTimeMillis())\n'
            '                .put("info", info)\n'
            '                .put("lat", coords?.lat ?: JSONObject.NULL)\n'
            '                .put("lon", coords?.lon ?: JSONObject.NULL)\n\n'
            '        if (matchedConfidence != null) {\n'
            '            entry.put("confidence", matchedConfidence)\n'
            '        }\n\n'
            '        entries.put(entry)\n',
            "sighting metadata",
        )

        text = replace_once(
            text,
            '        val highConfidenceBird = findHighConfidenceBird(result)\n\n'
            '        val inputText =\n',
            '        val highConfidenceBird = findHighConfidenceBird(result)\n'
            '        lastBirdCallCandidate = highConfidenceBird\n'
            '        BirdBuddySessionStore.setIdentification(\n'
            '            name = highConfidenceBird?.name,\n'
            '            confidencePercent = highConfidenceBird?.percent,\n'
            '            source = if (highConfidenceBird != null) "Bird call" else null\n'
            '        )\n\n'
            '        val inputText =\n',
            "identification result state",
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

    drawable_dir = android / "app" / "src" / "main" / "res" / "drawable"
    drawable_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(kit / "ic_birdingpal.xml", drawable_dir / "ic_birdingpal.xml")

    strings_path = android / "app" / "src" / "main" / "res" / "values" / "strings.xml"
    strings = strings_path.read_text().replace('>Birding Buddy<', '>BirdingPal<')
    strings_path.write_text(strings)

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
    text = text.replace('android:icon="@android:drawable/sym_def_app_icon"', 'android:icon="@drawable/ic_birdingpal"')
    text = text.replace('android:roundIcon="@android:drawable/sym_def_app_icon"', 'android:roundIcon="@drawable/ic_birdingpal"')
    text = text.replace(
        'android:foregroundServiceType="dataSync"\n            android:stopWithTask="false"',
        'android:foregroundServiceType="dataSync|microphone"\n            android:stopWithTask="true"',
    )
    manifest_path.write_text(text)

    print("BirdingPal phone-only overlay applied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

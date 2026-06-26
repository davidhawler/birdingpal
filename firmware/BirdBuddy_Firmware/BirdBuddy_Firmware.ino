/*
  Bird Buddy firmware example

  Target board:
    Adafruit QT Py ESP32-S3

  Arduino IDE settings:
    Board: Adafruit QT Py ESP32-S3
    USB CDC On Boot: Enabled
    USB Mode: Hardware CDC and JTAG

  Hardware:
    BT401 UART:
      QT Py TX / GPIO5  -> BT401 PB1 / UART-RX0
      QT Py RX / GPIO16 -> BT401 PB0 / UART-TX0
      GND               -> BT401 GND

    Inputs:
      2 FSRs connected using voltage dividers with 5.1k resistors
      FSR 1 on A3  / GPIO8
      FSR 2 on SCL / GPIO6

  This example is preconfigured for the Bird Buddy 2 hardware layout, but
  uses the public-facing Bird Buddy device names and dashboard hostname.
*/

#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <string.h>

#define BIRD_BUDDY_2

// ---------- Pins ----------

static const int BT401_RX_PIN = RX;   // QT Py receives from BT401 PB0 / UART-TX0
static const int BT401_TX_PIN = TX;   // QT Py sends to BT401 PB1 / UART-RX0

static const int FSR_A_PIN = A3;      // GPIO8 on QT Py ESP32-S3
static const int SECOND_INPUT_PIN = SCL; // GPIO6 on QT Py ESP32-S3, active-low FSR
static const int STATUS_PIXEL_PIN = SCK; // GPIO36 on QT Py ESP32-S3
static const int STATUS_PIXEL_COUNT = 1;

// ---------- Timing / thresholds ----------

static const uint32_t USB_BAUD = 115200;
static const uint32_t BT401_BAUD = 115200;
static const uint16_t HTTP_PORT = 80;

static const uint32_t SERIAL_STARTUP_WAIT_MS = 3000;
static const uint32_t BT401_SETUP_DELAY_MS = 250;
static const uint32_t INPUT_SAMPLE_MS = 20;
static const uint32_t FSR_REPEAT_MS = 500;
static const uint32_t SWITCH_DEBOUNCE_MS = 40;
static const uint32_t SWITCH_INDICATOR_MS = 2000;
static const uint32_t STATUS_PRINT_MS = 1000;
static const uint32_t INPUT_DEBUG_PRINT_MS = 200;
static const uint32_t BT401_RESPONSE_TIMEOUT_MS = 350;
static const char *PREF_NAMESPACE = "birdbuddy";
static const char *PREF_FSR_PRESS_KEY = "fsr_press";
static const char *PREF_FSR_RELEASE_KEY = "fsr_release";
static const char *PREF_SECOND_FSR_PRESS_KEY = "scl_press";
static const char *PREF_SECOND_FSR_RELEASE_KEY = "scl_release";

// FSRs are active-low. Start conservative and tune after watching values.
int fsrPressThreshold = 2000;
int fsrReleaseThreshold = 3500;
int secondFsrPressThreshold = 2000;
int secondFsrReleaseThreshold = 3500;

// ---------- Payloads ----------

static const size_t MAX_PAYLOAD_BYTES = 16;

uint8_t fsrPayload[MAX_PAYLOAD_BYTES] = {0x00, 0x45};
size_t fsrPayloadLength = 2;
uint8_t switchPayload[MAX_PAYLOAD_BYTES] = {0x00, 0x43};
size_t switchPayloadLength = 2;

// ---------- Device / web identity ----------

static const char *BLE_NAME = "BIRD_BUDDY_BLE";
static const char *AUDIO_NAME = "BIRD_BUDDY_AUDIO";
static const char *MDNS_NAME = "bird-buddy";
static const char *WEB_TITLE = "Bird Buddy Field Control";
static const char *WEB_SUBTITLE = "local test surface · BT401 bridge · input telemetry · pale yellow debug field";
static const char *SECOND_INPUT_STATE_LABEL = "SCL FSR state";
static const char *SECOND_INPUT_RAW_LABEL = "SCL FSR value";
static const char *AP_SSID = "BirdBuddy-Setup";

static const char *WIFI_SSID = "your-ssid-here";
static const char *WIFI_PASSWORD = "your-password-here";
static const char *AP_PASSWORD = "your-fallback-ap-password-here";

WebServer server(HTTP_PORT);
Preferences preferences;

// ---------- Runtime state ----------

Adafruit_NeoPixel statusPixel(STATUS_PIXEL_COUNT, STATUS_PIXEL_PIN, NEO_GRB + NEO_KHZ800);

struct FsrInput {
  const char *name;
  int pin;
  int value;
  bool pressed;
  uint32_t lastRepeatMs;
};

struct DigitalButton {
  const char *name;
  int pin;
  bool stablePressed;
  bool rawPressedLast;
  uint32_t lastRawChangeMs;
};

FsrInput fsrA = {"FSR_A3", FSR_A_PIN, 0, false, 0};
FsrInput secondFsr = {"FSR_SCL", SECOND_INPUT_PIN, 0, false, 0};

uint32_t lastInputSampleMs = 0;
uint32_t lastStatusPrintMs = 0;
uint32_t switchIndicatorUntilMs = 0;
bool bt401Configured = false;
bool fsrActive = false;
bool wifiConnected = false;
bool mdnsStarted = false;
uint32_t bootMs = 0;
uint8_t pixelR = 0;
uint8_t pixelG = 0;
uint8_t pixelB = 0;
String lastEvent = "booting";
String lastPayloadReason = "";
String lastPayloadHex = "";
String lastBt401Command = "";
String lastBt401Response = "";

#ifdef DEBUG_INPUTS
bool inputDebugEnabled = true;
#endif

// ---------- Utility ----------

void printHexByte(uint8_t value) {
  Serial.print(F("0x"));
  if (value < 0x10) {
    Serial.print(F("0"));
  }
  Serial.print(value, HEX);
}

void printPayload(const uint8_t *payload, size_t length) {
  for (size_t i = 0; i < length; i++) {
    if (i > 0) {
      Serial.print(F(" "));
    }
    printHexByte(payload[i]);
  }
}

String payloadToHexString(const uint8_t *payload, size_t length) {
  String hex = "";
  for (size_t i = 0; i < length; i++) {
    if (i > 0) {
      hex += " ";
    }
    if (payload[i] < 0x10) {
      hex += "0";
    }
    hex += String(payload[i], HEX);
  }
  hex.toUpperCase();
  return hex;
}

int hexNibble(char c) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  }
  if (c >= 'a' && c <= 'f') {
    return c - 'a' + 10;
  }
  if (c >= 'A' && c <= 'F') {
    return c - 'A' + 10;
  }
  return -1;
}

bool parseHexPayload(String text, uint8_t *payload, size_t &length) {
  uint8_t parsed[MAX_PAYLOAD_BYTES];
  size_t parsedLength = 0;
  int highNibble = -1;

  text.trim();

  for (size_t i = 0; i < text.length(); i++) {
    char c = text.charAt(i);

    if (c == 'x' || c == 'X') {
      if (i > 0 && text.charAt(i - 1) == '0' && highNibble == 0) {
        highNibble = -1;
        continue;
      }
      return false;
    }

    int nibble = hexNibble(c);
    if (nibble >= 0) {
      if (highNibble < 0) {
        highNibble = nibble;
      } else {
        if (parsedLength >= MAX_PAYLOAD_BYTES) {
          return false;
        }
        parsed[parsedLength++] = (uint8_t)((highNibble << 4) | nibble);
        highNibble = -1;
      }
      continue;
    }

    if (c == ' ' || c == ',' || c == ':' || c == '-' || c == '\t' || c == '\n' || c == '\r') {
      if (highNibble >= 0) {
        return false;
      }
      continue;
    }

    return false;
  }

  if (highNibble >= 0 || parsedLength == 0) {
    return false;
  }

  for (size_t i = 0; i < parsedLength; i++) {
    payload[i] = parsed[i];
  }
  length = parsedLength;
  return true;
}

void sendBt401Bytes(const char *reason, const uint8_t *payload, size_t length) {
  Serial1.write(payload, length);
  lastPayloadReason = reason;
  lastPayloadHex = payloadToHexString(payload, length);
  lastEvent = String("payload ") + reason + " " + lastPayloadHex;

  Serial.print(F("BT401 payload ["));
  Serial.print(reason);
  Serial.print(F("] <- "));
  printPayload(payload, length);
  Serial.println();
}

void sendBt401Command(const char *command) {
  Serial.print(F("BT401 AT <- "));
  Serial.println(command);
  Serial1.print(command);
  Serial1.print("\r\n");
}

String sendBt401CommandWithResponse(String command, uint32_t timeoutMs = BT401_RESPONSE_TIMEOUT_MS) {
  command.trim();
  if (command.length() == 0) {
    return "";
  }

  lastBt401Command = command;

  while (Serial1.available() > 0) {
    Serial1.read();
  }

  Serial.print(F("BT401 AT bridge <- "));
  Serial.println(command);
  Serial1.print(command);
  Serial1.print("\r\n");
  Serial1.flush();

  String response = "";
  uint32_t start = millis();
  while (millis() - start < timeoutMs) {
    while (Serial1.available() > 0) {
      response += (char)Serial1.read();
    }
    delay(2);
  }

  response.trim();
  lastBt401Response = response;
  lastEvent = String("AT bridge ") + command;

  Serial.print(F("BT401 AT bridge -> "));
  Serial.println(response.length() ? response : "[no response]");

  return response;
}

String jsonBool(bool value) {
  return value ? "true" : "false";
}

String jsonEscape(String s) {
  s.replace("\\", "\\\\");
  s.replace("\"", "\\\"");
  s.replace("\n", "\\n");
  s.replace("\r", "\\r");
  return s;
}

void pumpBt401ToUsb() {
  while (Serial1.available() > 0) {
    int c = Serial1.read();
    if (c >= 0) {
      Serial.write((uint8_t)c);
    }
  }
}

// ---------- BT401 ----------

void configureBt401AudioBleMode() {
  Serial.println();
  Serial.println(F("Configuring BT401 for audio + BLE pass-through..."));

  sendBt401Command("AT+CT05");          // UART baud 115200
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+B501");          // Enable EDR / Classic Bluetooth
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+B301");          // Enable Bluetooth audio
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+B201");          // Enable Bluetooth call
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+B401");          // Enable BLE
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+CM01");          // Bluetooth playback mode
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+CP00");          // Boot into Bluetooth mode
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  Serial.print(F("BT401 AT <- AT+BM"));
  Serial.println(BLE_NAME);
  Serial1.print("AT+BM");
  Serial1.print(BLE_NAME);
  Serial1.print("\r\n");
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  Serial.print(F("BT401 AT <- AT+BD"));
  Serial.println(AUDIO_NAME);
  Serial1.print("AT+BD");
  Serial1.print(AUDIO_NAME);
  Serial1.print("\r\n");
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+U0B001");        // BLE service UUID
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+U1FFF1");        // BLE write UUID
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+U2B101");        // BLE notify UUID
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+U3FFF3");        // BLE read UUID
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  sendBt401Command("AT+C201");          // Verbose auto-return while debugging
  delay(BT401_SETUP_DELAY_MS);
  pumpBt401ToUsb();

  bt401Configured = true;
  Serial.println(F("BT401 AT <- AT+CZ"));
  Serial.println(F("Resetting BT401 so Bluetooth names are advertised."));
  Serial1.print("AT+CZ\r\n");
  delay(1000);
  pumpBt401ToUsb();
  Serial.println(F("BT401 setup sequence sent. Power-cycle/reset BT401 if name/UUID changes do not appear immediately."));
  Serial.println();
}

void queryBt401Status() {
  Serial.println();
  Serial.println(F("Querying BT401 status..."));

  const char *queries[] = {
    "AT+QM", "AT+QT", "AT+T3", "AT+T4", "AT+T5", "AT+TS", "AT+TL",
    "AT+TM", "AT+TD", "AT+T6", "AT+T7", "AT+T8", "AT+T9"
  };

  for (size_t i = 0; i < sizeof(queries) / sizeof(queries[0]); i++) {
    sendBt401Command(queries[i]);
    delay(150);
    pumpBt401ToUsb();
  }

  Serial.println();
}

// ---------- Inputs ----------

void updateFsr(FsrInput &fsr, uint32_t now) {
  fsr.value = analogRead(fsr.pin);

  if (!fsr.pressed && fsr.value <= fsrPressThreshold) {
    fsr.pressed = true;
    fsr.lastRepeatMs = now;

    Serial.print(fsr.name);
    Serial.print(F(" pressed value="));
    Serial.println(fsr.value);
    sendBt401Bytes(fsr.name, fsrPayload, fsrPayloadLength);
  } else if (fsr.pressed && fsr.value >= fsrReleaseThreshold) {
    fsr.pressed = false;

    Serial.print(fsr.name);
    Serial.print(F(" released value="));
    Serial.println(fsr.value);
    lastEvent = String(fsr.name) + " released";
  }

  if (fsr.pressed && now - fsr.lastRepeatMs >= FSR_REPEAT_MS) {
    fsr.lastRepeatMs = now;
    Serial.print(fsr.name);
    Serial.print(F(" held value="));
    Serial.println(fsr.value);
    sendBt401Bytes(fsr.name, fsrPayload, fsrPayloadLength);
  }
}

void updateSecondFsr(FsrInput &fsr, uint32_t now) {
  fsr.value = analogRead(fsr.pin);

  if (!fsr.pressed && fsr.value <= secondFsrPressThreshold) {
    fsr.pressed = true;
    switchIndicatorUntilMs = now + SWITCH_INDICATOR_MS;

    Serial.print(fsr.name);
    Serial.print(F(" pressed value="));
    Serial.println(fsr.value);
    lastEvent = String(fsr.name) + " pressed";
    sendBt401Bytes(fsr.name, switchPayload, switchPayloadLength);
  } else if (fsr.pressed && fsr.value >= secondFsrReleaseThreshold) {
    fsr.pressed = false;
    switchIndicatorUntilMs = now + SWITCH_INDICATOR_MS;

    Serial.print(fsr.name);
    Serial.print(F(" released value="));
    Serial.println(fsr.value);
    lastEvent = String(fsr.name) + " released";
  }
}

void updateInputs() {
  uint32_t now = millis();
  if (now - lastInputSampleMs < INPUT_SAMPLE_MS) {
    return;
  }
  lastInputSampleMs = now;

  updateFsr(fsrA, now);
  updateSecondFsr(secondFsr, now);

  fsrActive = fsrA.pressed;
}

// ---------- Status pixel ----------

void setStatusPixel(uint8_t r, uint8_t g, uint8_t b) {
  pixelR = r;
  pixelG = g;
  pixelB = b;
  statusPixel.setPixelColor(0, statusPixel.Color(r, g, b));
  statusPixel.show();
}

void updateStatusPixel() {
  if (fsrActive) {
    setStatusPixel(0, 0, 255);
  } else if ((int32_t)(millis() - switchIndicatorUntilMs) < 0) {
    setStatusPixel(160, 0, 255);
  } else {
    setStatusPixel(0, 255, 0);
  }
}

// ---------- Debug/status ----------

void printPinout() {
  Serial.println(F("Bird Buddy pinout:"));
  Serial.print(F("  BT401 RX pin: GPIO"));
  Serial.println(BT401_RX_PIN);
  Serial.print(F("  BT401 TX pin: GPIO"));
  Serial.println(BT401_TX_PIN);
  Serial.print(F("  FSR A3 pin: GPIO"));
  Serial.println(FSR_A_PIN);
  Serial.print(F("  Switch-payload FSR SCL pin: GPIO"));
  Serial.println(SECOND_INPUT_PIN);
  Serial.print(F("  NeoPixel SCK pin: GPIO"));
  Serial.println(STATUS_PIXEL_PIN);
  Serial.println();
}

void printRuntimeStatus() {
  uint32_t now = millis();
  if (now - lastStatusPrintMs < STATUS_PRINT_MS) {
    return;
  }
  lastStatusPrintMs = now;

  Serial.print(F("inputs fsrA="));
  Serial.print(fsrA.value);
  Serial.print(fsrA.pressed ? F("(pressed)") : F("(idle)"));
  Serial.print(F(" switchTrigger="));
  Serial.print(secondFsr.pressed ? F("pressed") : F("idle"));
  Serial.print(F(" value="));
  Serial.print(secondFsr.value);
  Serial.print(F(" wifi="));
  Serial.print(wifiConnected ? F("connected") : F("ap/fallback"));
  Serial.print(F(" mdns="));
  Serial.print(MDNS_NAME);
  Serial.print(F(".local"));
  Serial.print(F(" url=http://"));
  Serial.println(wifiConnected ? WiFi.localIP().toString() : WiFi.softAPIP().toString());
}

void printWebUrl() {
  IPAddress ip = wifiConnected ? WiFi.localIP() : WiFi.softAPIP();

  Serial.println();
  Serial.println(F("=========================================="));
  Serial.println(F("Bird Buddy web debug"));
  Serial.print(F("URL:  http://"));
  Serial.println(ip);
  Serial.print(F("mDNS: http://"));
  Serial.print(MDNS_NAME);
  Serial.println(F(".local"));
  if (!wifiConnected) {
    Serial.print(F("AP SSID: "));
    Serial.println(AP_SSID);
    Serial.print(F("AP PASS: "));
    Serial.println(AP_PASSWORD);
  }
  Serial.println(F("=========================================="));
  Serial.println();
}

const __FlashStringHelper *wifiStatusName(wl_status_t status) {
  switch (status) {
    case WL_IDLE_STATUS:
      return F("WL_IDLE_STATUS");
    case WL_NO_SSID_AVAIL:
      return F("WL_NO_SSID_AVAIL");
    case WL_SCAN_COMPLETED:
      return F("WL_SCAN_COMPLETED");
    case WL_CONNECTED:
      return F("WL_CONNECTED");
    case WL_CONNECT_FAILED:
      return F("WL_CONNECT_FAILED");
    case WL_CONNECTION_LOST:
      return F("WL_CONNECTION_LOST");
    case WL_DISCONNECTED:
      return F("WL_DISCONNECTED");
    default:
      return F("UNKNOWN");
  }
}

void onWiFiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    Serial.print(F("Wi-Fi event: STA disconnected, reason "));
    Serial.println(info.wifi_sta_disconnected.reason);
  } else if (event == ARDUINO_EVENT_WIFI_STA_CONNECTED) {
    Serial.println(F("Wi-Fi event: STA connected"));
  } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
    Serial.print(F("Wi-Fi event: got IP "));
    Serial.println(WiFi.localIP());
  }
}

void printWiFiDiagnostics() {
  wl_status_t status = WiFi.status();
  Serial.print(F("Wi-Fi status: "));
  Serial.print(wifiStatusName(status));
  Serial.print(F(" ("));
  Serial.print((int)status);
  Serial.println(F(")"));
  Serial.print(F("Wi-Fi MAC: "));
  Serial.println(WiFi.macAddress());

  if (status == WL_CONNECTED) {
    Serial.print(F("Wi-Fi RSSI: "));
    Serial.println(WiFi.RSSI());
    Serial.print(F("Wi-Fi BSSID: "));
    Serial.println(WiFi.BSSIDstr());
  }
}

void scanForTargetWiFi() {
  Serial.print(F("Scanning for Wi-Fi SSID: "));
  Serial.println(WIFI_SSID);

  WiFi.scanDelete();
  delay(200);

  int count = WiFi.scanNetworks(false, true);
  if (count < 0) {
    Serial.print(F("Wi-Fi scan failed: "));
    Serial.println(count);
    return;
  }

  bool found = false;
  Serial.print(F("Wi-Fi networks found: "));
  Serial.println(count);

  for (int i = 0; i < count; i++) {
    if (WiFi.SSID(i) == WIFI_SSID) {
      found = true;
      Serial.print(F("  target found: RSSI "));
      Serial.print(WiFi.RSSI(i));
      Serial.print(F(" dBm, channel "));
      Serial.print(WiFi.channel(i));
      Serial.print(F(", encryption "));
      Serial.println((int)WiFi.encryptionType(i));
    }
  }

  if (!found) {
    Serial.println(F("  target SSID not found in scan"));
  }

  WiFi.scanDelete();
}

void normalizeThresholds() {
  fsrPressThreshold = constrain(fsrPressThreshold, 0, 4095);
  fsrReleaseThreshold = constrain(fsrReleaseThreshold, 0, 4095);
  secondFsrPressThreshold = constrain(secondFsrPressThreshold, 0, 4095);
  secondFsrReleaseThreshold = constrain(secondFsrReleaseThreshold, 0, 4095);

  if (fsrPressThreshold > fsrReleaseThreshold) {
    int tmp = fsrPressThreshold;
    fsrPressThreshold = fsrReleaseThreshold;
    fsrReleaseThreshold = tmp;
  }
  if (secondFsrPressThreshold > secondFsrReleaseThreshold) {
    int tmp = secondFsrPressThreshold;
    secondFsrPressThreshold = secondFsrReleaseThreshold;
    secondFsrReleaseThreshold = tmp;
  }
}

void loadSettings() {
  preferences.begin(PREF_NAMESPACE, false);

  fsrPressThreshold = preferences.getInt(PREF_FSR_PRESS_KEY, fsrPressThreshold);
  fsrReleaseThreshold = preferences.getInt(PREF_FSR_RELEASE_KEY, fsrReleaseThreshold);
  secondFsrPressThreshold = preferences.getInt(PREF_SECOND_FSR_PRESS_KEY, secondFsrPressThreshold);
  secondFsrReleaseThreshold = preferences.getInt(PREF_SECOND_FSR_RELEASE_KEY, secondFsrReleaseThreshold);

  normalizeThresholds();

  Serial.print(F("Loaded FSR thresholds: press <= "));
  Serial.print(fsrPressThreshold);
  Serial.print(F(", release >= "));
  Serial.println(fsrReleaseThreshold);
  Serial.print(F("Loaded SCL FSR thresholds: press <= "));
  Serial.print(secondFsrPressThreshold);
  Serial.print(F(", release >= "));
  Serial.println(secondFsrReleaseThreshold);
}

void saveThresholds() {
  normalizeThresholds();
  preferences.putInt(PREF_FSR_PRESS_KEY, fsrPressThreshold);
  preferences.putInt(PREF_FSR_RELEASE_KEY, fsrReleaseThreshold);
  preferences.putInt(PREF_SECOND_FSR_PRESS_KEY, secondFsrPressThreshold);
  preferences.putInt(PREF_SECOND_FSR_RELEASE_KEY, secondFsrReleaseThreshold);

  Serial.print(F("Saved FSR thresholds: press <= "));
  Serial.print(fsrPressThreshold);
  Serial.print(F(", release >= "));
  Serial.println(fsrReleaseThreshold);
  Serial.print(F("Saved SCL FSR thresholds: press <= "));
  Serial.print(secondFsrPressThreshold);
  Serial.print(F(", release >= "));
  Serial.println(secondFsrReleaseThreshold);
}

// ---------- Web debug ----------

String buildStatusJson() {
  String json = "{";
  json += "\"uptime_ms\":" + String(millis() - bootMs) + ",";
  json += "\"ip\":\"" + (wifiConnected ? WiFi.localIP().toString() : WiFi.softAPIP().toString()) + "\",";
  json += "\"wifi_connected\":" + jsonBool(wifiConnected) + ",";
  json += "\"mdns\":\"" + jsonEscape(String(MDNS_NAME) + ".local") + "\",";
  json += "\"bt401_configured\":" + jsonBool(bt401Configured) + ",";
  json += "\"last_event\":\"" + jsonEscape(lastEvent) + "\",";

  json += "\"inputs\":{";
  json += "\"fsr_value\":" + String(fsrA.value) + ",";
  json += "\"fsr_pressed\":" + jsonBool(fsrA.pressed) + ",";
  json += "\"switch_input_kind\":\"fsr_scl\",";
  json += "\"switch_value\":" + String(secondFsr.value) + ",";
  json += "\"switch_pressed\":" + jsonBool(secondFsr.pressed) + ",";
  json += "\"switch_raw_low\":" + jsonBool(secondFsr.value <= secondFsrPressThreshold);
  json += "},";

  json += "\"thresholds\":{";
  json += "\"press\":" + String(fsrPressThreshold) + ",";
  json += "\"release\":" + String(fsrReleaseThreshold);
  json += ",\"second_press\":" + String(secondFsrPressThreshold) + ",";
  json += "\"second_release\":" + String(secondFsrReleaseThreshold);
  json += "},";

  json += "\"neopixel\":{";
  json += "\"r\":" + String(pixelR) + ",";
  json += "\"g\":" + String(pixelG) + ",";
  json += "\"b\":" + String(pixelB);
  json += "},";

  json += "\"payload\":{";
  json += "\"reason\":\"" + jsonEscape(lastPayloadReason) + "\",";
  json += "\"hex\":\"" + jsonEscape(lastPayloadHex) + "\",";
  json += "\"fsr_hex\":\"" + jsonEscape(payloadToHexString(fsrPayload, fsrPayloadLength)) + "\",";
  json += "\"switch_hex\":\"" + jsonEscape(payloadToHexString(switchPayload, switchPayloadLength)) + "\"";
  json += "},";

  json += "\"bt401\":{";
  json += "\"last_command\":\"" + jsonEscape(lastBt401Command) + "\",";
  json += "\"last_response\":\"" + jsonEscape(lastBt401Response) + "\"";
  json += "}";

  json += "}";
  return json;
}

void sendJson(String json) {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", json);
}

void handleApiStatus() {
  sendJson(buildStatusJson());
}

void handleApiThresholds() {
  if (server.hasArg("press")) {
    fsrPressThreshold = constrain(server.arg("press").toInt(), 0, 4095);
  }
  if (server.hasArg("release")) {
    fsrReleaseThreshold = constrain(server.arg("release").toInt(), 0, 4095);
  }
  if (server.hasArg("second_press")) {
    secondFsrPressThreshold = constrain(server.arg("second_press").toInt(), 0, 4095);
  }
  if (server.hasArg("second_release")) {
    secondFsrReleaseThreshold = constrain(server.arg("second_release").toInt(), 0, 4095);
  }

  saveThresholds();

  lastEvent = "thresholds saved";
  sendJson(buildStatusJson());
}

void handleApiSendFsr() {
  sendBt401Bytes("manual-fsr", fsrPayload, fsrPayloadLength);
  sendJson(buildStatusJson());
}

void handleApiSendSwitch() {
  sendBt401Bytes("manual-switch", switchPayload, switchPayloadLength);
  sendJson(buildStatusJson());
}

void handleApiPayloads() {
  if (server.hasArg("fsr")) {
    size_t nextLength = fsrPayloadLength;
    uint8_t nextPayload[MAX_PAYLOAD_BYTES];
    if (!parseHexPayload(server.arg("fsr"), nextPayload, nextLength)) {
      server.send(400, "application/json", "{\"error\":\"invalid fsr payload hex\"}");
      return;
    }
    memcpy(fsrPayload, nextPayload, nextLength);
    fsrPayloadLength = nextLength;
  }

  if (server.hasArg("sw") || server.hasArg("switch")) {
    String value = server.hasArg("sw") ? server.arg("sw") : server.arg("switch");
    size_t nextLength = switchPayloadLength;
    uint8_t nextPayload[MAX_PAYLOAD_BYTES];
    if (!parseHexPayload(value, nextPayload, nextLength)) {
      server.send(400, "application/json", "{\"error\":\"invalid switch payload hex\"}");
      return;
    }
    memcpy(switchPayload, nextPayload, nextLength);
    switchPayloadLength = nextLength;
  }

  lastEvent = "payload bytes updated";
  sendJson(buildStatusJson());
}

void handleApiBt401At() {
  String command = "";
  if (server.hasArg("cmd")) {
    command = server.arg("cmd");
  } else if (server.hasArg("plain")) {
    command = server.arg("plain");
  } else {
    command = server.arg(0);
  }

  String response = sendBt401CommandWithResponse(command);

  String json = "{";
  json += "\"cmd\":\"" + jsonEscape(command) + "\",";
  json += "\"response\":\"" + jsonEscape(response) + "\"";
  json += "}";
  sendJson(json);
}

String birdBuddyThemeOverrides() {
  return R"rawliteral(
  :root {
    --bg: #fff9df;
    --butter: #f7d96b;
    --butter2: #d5a935;
    --cream: #fff4b8;
    --ink: #3a2a06;
    --soft: #fff2a8;
    --good: #4c8f2f;
    --warn: #a56a00;
    --dark: #3a2a06;
  }

  body {
    background:
      radial-gradient(circle at 10% 10%, rgba(247,217,107,0.18), transparent 25%),
      radial-gradient(circle at 90% 80%, rgba(255,244,184,0.28), transparent 22%),
      var(--bg);
    color: #3a2a06;
  }

  h1 { color: #5a4308; }
  .subtitle { color: #735b17; }

  .tile {
    background:
      radial-gradient(circle at 18% 8%, rgba(255,255,255,0.50), transparent 16%),
      radial-gradient(circle at 78% 80%, rgba(255,246,184,0.48), transparent 30%),
      linear-gradient(135deg, #fff0a6 0%, #f7d96b 48%, #d5a935 100%);
    color: #3a2a06;
    box-shadow:
      0 0 18px rgba(247, 217, 107, 0.58),
      0 0 42px rgba(247, 217, 107, 0.34),
      0 18px 46px rgba(143, 104, 13, 0.20),
      inset 0 0 28px rgba(255, 255, 225, 0.30);
    border: 1px solid rgba(255, 249, 198, 0.72);
  }

  .tile::before {
    background:
      radial-gradient(circle at 50% 0%, rgba(255,255,230,0.58), transparent 35%),
      linear-gradient(90deg, transparent, rgba(255,255,255,0.22), transparent);
  }

  @keyframes emberPulse {
    0%, 100% {
      box-shadow:
        0 0 18px rgba(247, 217, 107, 0.52),
        0 0 42px rgba(247, 217, 107, 0.30),
        0 18px 46px rgba(143, 104, 13, 0.20),
        inset 0 0 28px rgba(255, 255, 225, 0.26);
    }
    50% {
      box-shadow:
        0 0 26px rgba(255, 244, 184, 0.78),
        0 0 62px rgba(247, 217, 107, 0.46),
        0 22px 54px rgba(143, 104, 13, 0.26),
        inset 0 0 36px rgba(255, 255, 235, 0.36);
    }
  }

  .glyph {
    color: rgba(58, 42, 6, 0.50);
    text-shadow:
      0 0 8px rgba(255, 255, 230, 0.90),
      0 0 18px rgba(255, 239, 140, 0.75),
      0 0 32px rgba(210, 165, 45, 0.30);
  }

  button,
  input {
    color: #3a2a06;
    border-color: rgba(80, 58, 8, 0.24);
  }

  button:hover {
    background: rgba(255,255,255,0.34);
    box-shadow:
      0 0 14px rgba(255,244,184,0.56),
      inset 0 0 16px rgba(255,255,255,0.20);
  }

  input {
    box-shadow: inset 0 0 18px rgba(110,78,0,0.10);
  }

  pre { color: #4d3908; }
  .label { color: #5a4308; }
)rawliteral";
}

String htmlPage() {
  String page = R"rawliteral(
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bird Buddy Field Control</title>
<style>
  :root {
    --bg: #f7efe3;
    --burnt: #c85f22;
    --burnt2: #923914;
    --ember: #f0a15b;
    --ink: #fff9f2;
    --soft: #ffe4c7;
    --good: #ffe36f;
    --warn: #ffd166;
    --dark: #3c1609;
  }

  body {
    margin: 0;
    background:
      radial-gradient(circle at 10% 10%, rgba(200,95,34,0.10), transparent 25%),
      radial-gradient(circle at 90% 80%, rgba(240,161,91,0.16), transparent 22%),
      var(--bg);
    color: #28140b;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }

  .shell {
    max-width: 1180px;
    margin: 0 auto;
    padding: 28px;
  }

  h1 {
    font-size: 22px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    font-weight: 600;
    margin: 0 0 20px 0;
    color: #4b1f0e;
  }

  .subtitle {
    margin-bottom: 22px;
    color: #65402b;
    font-size: 12px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(12, 1fr);
    gap: 14px;
  }

  .tile {
    background:
      radial-gradient(circle at 18% 8%, rgba(255,255,255,0.30), transparent 16%),
      radial-gradient(circle at 78% 80%, rgba(255,190,118,0.36), transparent 30%),
      linear-gradient(135deg, #e89143 0%, #c85f22 48%, #7d2f11 100%);
    color: var(--ink);
    padding: 18px;
    min-height: 120px;
    box-shadow:
      0 0 18px rgba(200, 95, 34, 0.50),
      0 0 42px rgba(200, 95, 34, 0.30),
      0 18px 46px rgba(100, 43, 16, 0.26),
      inset 0 0 28px rgba(255, 215, 170, 0.18);
    border: 1px solid rgba(255,225,190,0.50);
    position: relative;
    overflow: hidden;
  }

  .tile::before {
    content: "";
    position: absolute;
    inset: -2px;
    background:
      radial-gradient(circle at 50% 0%, rgba(255,226,194,0.38), transparent 35%),
      linear-gradient(90deg, transparent, rgba(255,255,255,0.13), transparent);
    filter: blur(10px);
    opacity: 0.9;
    pointer-events: none;
  }

  .tile::after {
    content: "";
    position: absolute;
    inset: -35%;
    background:
      radial-gradient(circle, rgba(255,255,255,0.20), transparent 38%),
      repeating-linear-gradient(
        127deg,
        rgba(255,255,255,0.045) 0px,
        rgba(255,255,255,0.045) 1px,
        transparent 1px,
        transparent 9px
      );
    transform: rotate(18deg);
    pointer-events: none;
    mix-blend-mode: screen;
  }

  .tile > * {
    position: relative;
    z-index: 2;
  }

  @keyframes emberPulse {
    0%, 100% {
      box-shadow:
        0 0 18px rgba(200, 95, 34, 0.48),
        0 0 42px rgba(200, 95, 34, 0.28),
        0 18px 46px rgba(100, 43, 16, 0.25),
        inset 0 0 28px rgba(255, 215, 170, 0.16);
    }
    50% {
      box-shadow:
        0 0 26px rgba(255, 183, 107, 0.72),
        0 0 62px rgba(200, 95, 34, 0.44),
        0 22px 54px rgba(100, 43, 16, 0.34),
        inset 0 0 36px rgba(255, 230, 200, 0.24);
    }
  }

  .tile {
    animation: emberPulse 4.8s ease-in-out infinite;
  }

  .tile:nth-child(2n) { animation-delay: -1.4s; }
  .tile:nth-child(3n) { animation-delay: -2.7s; }

  .glyph {
    position: absolute;
    top: 12px;
    right: 16px;
    z-index: 3;
    font-size: 30px;
    line-height: 1;
    color: rgba(255, 249, 242, 0.78);
    text-shadow:
      0 0 8px rgba(255, 239, 220, 0.90),
      0 0 18px rgba(255, 183, 107, 0.75),
      0 0 32px rgba(255, 120, 42, 0.30);
    transform: rotate(-7deg);
    user-select: none;
    pointer-events: none;
  }

  .odd1 { border-radius: 34px 14px 28px 18px; transform: skew(-1.5deg); }
  .odd2 { border-radius: 16px 42px 16px 30px; transform: skew(1.2deg); }
  .odd3 { border-radius: 24px 22px 44px 12px; transform: rotate(-0.4deg); }
  .odd4 { border-radius: 42px 18px 18px 28px; transform: rotate(0.4deg); }

  .wide { grid-column: span 6; }
  .third { grid-column: span 4; }
  .full { grid-column: span 12; }

  .label {
    font-size: 11px;
    color: var(--soft);
    letter-spacing: 0.12em;
    text-transform: uppercase;
    margin-bottom: 8px;
    padding-right: 48px;
  }

  .big {
    font-size: 30px;
    line-height: 1;
    margin-bottom: 12px;
  }

  .kv {
    display: grid;
    grid-template-columns: 150px 1fr;
    gap: 6px 10px;
    font-size: 13px;
  }

  button {
    background: rgba(255,255,255,0.16);
    color: var(--ink);
    border: 1px solid rgba(255,255,255,0.35);
    border-radius: 999px;
    padding: 9px 13px;
    margin: 4px 4px 4px 0;
    font-family: inherit;
    cursor: pointer;
    box-shadow:
      0 0 10px rgba(255,255,255,0.10),
      inset 0 0 12px rgba(255,255,255,0.08);
  }

  button:hover {
    background: rgba(255,255,255,0.27);
    box-shadow:
      0 0 14px rgba(255,210,170,0.44),
      inset 0 0 16px rgba(255,255,255,0.12);
  }

  input {
    background: rgba(255,255,255,0.14);
    color: var(--ink);
    border: 1px solid rgba(255,255,255,0.35);
    border-radius: 10px;
    padding: 10px;
    font-family: inherit;
    width: 120px;
    box-shadow: inset 0 0 18px rgba(55,20,0,0.14);
  }

  pre {
    white-space: pre-wrap;
    word-break: break-word;
    color: var(--soft);
    font-size: 12px;
    max-height: 260px;
    overflow: auto;
  }

  .good { color: var(--good); }
  .warn { color: var(--warn); }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .swatch {
    width: 76px;
    height: 76px;
    border-radius: 50%;
    border: 1px solid rgba(255,255,255,0.58);
    box-shadow:
      0 0 18px rgba(255,255,255,0.28),
      inset 0 0 14px rgba(255,255,255,0.18);
  }
  .swatch-wrap {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  @media (max-width: 900px) {
    .wide, .third, .full { grid-column: span 12; }
  }
</style>
</head>
<body>
<div class="shell">
  <h1>Bird Buddy Field Control</h1>
  <div class="subtitle">local test surface · BT401 bridge · input telemetry · ember debug field</div>

  <div class="grid">
    <div class="tile wide odd1">
      <div class="glyph">◌</div>
      <div class="label">System</div>
      <div class="big" id="activeText">...</div>
      <div class="kv">
        <div>IP</div><div id="ip">...</div>
        <div>mDNS</div><div id="mdns">...</div>
        <div>Uptime</div><div id="uptime">...</div>
        <div>Wi-Fi</div><div id="wifi">...</div>
        <div>BT401 setup</div><div id="bt401">...</div>
        <div>Last event</div><div id="event">...</div>
      </div>
    </div>

    <div class="tile wide odd2">
      <div class="glyph">⌁</div>
      <div class="label">Input Field</div>
      <div class="kv">
        <div>FSR value</div><div id="fsrValue">...</div>
        <div>FSR state</div><div id="fsrState">...</div>
        <div>Switch stable</div><div id="switchState">...</div>
        <div>Switch raw</div><div id="switchRaw">...</div>
        <div>Press threshold</div><div id="pressText">...</div>
        <div>Release threshold</div><div id="releaseText">...</div>
        <div class="second-threshold">SCL press threshold</div><div class="second-threshold" id="secondPressText">...</div>
        <div class="second-threshold">SCL release threshold</div><div class="second-threshold" id="secondReleaseText">...</div>
      </div>
      <br>
      <div class="row">
        <input id="pressInput" type="number" min="0" max="4095" step="1">
        <input id="releaseInput" type="number" min="0" max="4095" step="1">
        <button onclick="saveThresholds()">Save Thresholds</button>
      </div>
      <div class="row second-threshold">
        <input id="secondPressInput" type="number" min="0" max="4095" step="1">
        <input id="secondReleaseInput" type="number" min="0" max="4095" step="1">
      </div>
    </div>

    <div class="tile third odd3">
      <div class="glyph">⋮</div>
      <div class="label">NeoPixel</div>
      <div class="swatch-wrap">
        <div id="pxSwatch" class="swatch"></div>
        <div id="pxText">...</div>
      </div>
    </div>

    <div class="tile third odd4">
      <div class="glyph">◍</div>
      <div class="label">Trigger Debug</div>
      <div class="kv">
        <div>FSR bytes</div><div id="fsrPayloadText">...</div>
        <div>Switch bytes</div><div id="switchPayloadText">...</div>
      </div>
      <br>
      <div class="row">
        <input id="fsrPayloadInput" style="width:min(260px,90%)" spellcheck="false">
        <button onclick="savePayloads()">Save FSR</button>
      </div>
      <div class="row">
        <input id="switchPayloadInput" style="width:min(260px,90%)" spellcheck="false">
        <button onclick="savePayloads()">Save Switch</button>
      </div>
      <br>
      <button onclick="post('/api/send_fsr')">Test FSR</button>
      <button onclick="post('/api/send_switch')">Test Switch</button>
      <button onclick="post('/api/setup_bt')">Re-send BT Setup</button>
    </div>

    <div class="tile third odd1">
      <div class="glyph">⌬</div>
      <div class="label">Last Payload</div>
      <div class="kv">
        <div>Reason</div><div id="payloadReason">...</div>
        <div>Hex</div><div id="payloadHex">...</div>
      </div>
    </div>

    <div class="tile full odd3">
      <div class="glyph">⟐</div>
      <div class="label">Raw BT401 AT Bridge</div>
      <div class="row">
        <input id="rawCmd" value="AT+TS" style="width:min(480px,80%)">
        <button onclick="sendRawAt()">Send AT Command</button>
      </div>
      <pre id="rawOut">ready</pre>
    </div>

    <div class="tile full odd4">
      <div class="glyph">⊚</div>
      <div class="label">Raw Status JSON</div>
      <pre id="jsonOut">loading...</pre>
    </div>
  </div>
</div>

<script>
let thresholdsDirty = false;
let payloadsDirty = false;

function setThresholdInputs(j) {
  if (thresholdsDirty) return;
  document.getElementById('pressInput').value = j.thresholds.press;
  document.getElementById('releaseInput').value = j.thresholds.release;
  if ('second_press' in j.thresholds) {
    document.getElementById('secondPressInput').value = j.thresholds.second_press;
    document.getElementById('secondReleaseInput').value = j.thresholds.second_release;
  }
}

function setPayloadInputs(j) {
  if (payloadsDirty) return;
  document.getElementById('fsrPayloadInput').value = j.payload.fsr_hex;
  document.getElementById('switchPayloadInput').value = j.payload.switch_hex;
}

document.addEventListener('input', e => {
  if (e.target.id === 'pressInput' || e.target.id === 'releaseInput' ||
      e.target.id === 'secondPressInput' || e.target.id === 'secondReleaseInput') {
    thresholdsDirty = true;
  }
  if (e.target.id === 'fsrPayloadInput' || e.target.id === 'switchPayloadInput') {
    payloadsDirty = true;
  }
});

async function refresh() {
  try {
    const r = await fetch('/api/status');
    const j = await r.json();

    document.getElementById('jsonOut').textContent = JSON.stringify(j, null, 2);

    document.getElementById('activeText').innerHTML =
      j.inputs.fsr_pressed || j.inputs.switch_pressed
        ? '<span class="good">ACTIVE INPUT</span>'
        : '<span>inputs idle</span>';

    document.getElementById('ip').textContent = j.ip;
    document.getElementById('mdns').textContent = j.mdns;
    document.getElementById('uptime').textContent = Math.round(j.uptime_ms / 1000) + ' s';
    document.getElementById('wifi').textContent = j.wifi_connected ? 'station' : 'access point';
    document.getElementById('bt401').textContent = j.bt401_configured ? 'configured' : 'pending';
    document.getElementById('event').textContent = j.last_event;

    document.getElementById('fsrValue').textContent = j.inputs.fsr_value;
    document.getElementById('fsrState').textContent = j.inputs.fsr_pressed ? 'pressed' : 'idle';
    document.getElementById('switchState').textContent = j.inputs.switch_pressed ? 'pressed' : 'released';
    document.getElementById('switchRaw').textContent =
      j.inputs.switch_input_kind === 'fsr_scl'
        ? `${j.inputs.switch_value} / ${j.inputs.switch_raw_low ? 'LOW' : 'HIGH'}`
        : (j.inputs.switch_raw_low ? 'LOW' : 'HIGH');
    document.getElementById('pressText').textContent = j.thresholds.press;
    document.getElementById('releaseText').textContent = j.thresholds.release;
    const hasSecondThresholds = 'second_press' in j.thresholds;
    document.querySelectorAll('.second-threshold').forEach(el => {
      el.style.display = hasSecondThresholds ? '' : 'none';
    });
    if (hasSecondThresholds) {
      document.getElementById('secondPressText').textContent = j.thresholds.second_press;
      document.getElementById('secondReleaseText').textContent = j.thresholds.second_release;
    }

    const rgb = `rgb(${j.neopixel.r}, ${j.neopixel.g}, ${j.neopixel.b})`;
    document.getElementById('pxSwatch').style.background = rgb;
    document.getElementById('pxText').textContent = rgb;

    document.getElementById('payloadReason').textContent = j.payload.reason || '[none]';
    document.getElementById('payloadHex').textContent = j.payload.hex || '[none]';
    document.getElementById('fsrPayloadText').textContent = j.payload.fsr_hex;
    document.getElementById('switchPayloadText').textContent = j.payload.switch_hex;
    document.getElementById('rawOut').textContent =
      `last command: ${j.bt401.last_command || '[none]'}\nlast response: ${j.bt401.last_response || '[none]'}`;

    setThresholdInputs(j);
    setPayloadInputs(j);
  } catch (e) {
    document.getElementById('jsonOut').textContent = 'refresh error: ' + e;
  }
}

async function post(url) {
  const r = await fetch(url, { method: 'POST' });
  const t = await r.text();
  document.getElementById('jsonOut').textContent = t;
  refresh();
}

async function saveThresholds() {
  const press = document.getElementById('pressInput').value;
  const release = document.getElementById('releaseInput').value;
  const secondPress = document.getElementById('secondPressInput').value;
  const secondRelease = document.getElementById('secondReleaseInput').value;
  let url = '/api/thresholds?press=' + encodeURIComponent(press) + '&release=' + encodeURIComponent(release);
  if (secondPress !== '' && secondRelease !== '') {
    url += '&second_press=' + encodeURIComponent(secondPress) + '&second_release=' + encodeURIComponent(secondRelease);
  }
  thresholdsDirty = false;
  await post(url);
}

async function savePayloads() {
  const fsr = document.getElementById('fsrPayloadInput').value;
  const sw = document.getElementById('switchPayloadInput').value;
  payloadsDirty = false;
  await post('/api/payloads?fsr=' + encodeURIComponent(fsr) + '&sw=' + encodeURIComponent(sw));
}

async function sendRawAt() {
  const cmd = document.getElementById('rawCmd').value;
  const r = await fetch('/api/bt401_at?cmd=' + encodeURIComponent(cmd), { method: 'POST' });
  const t = await r.text();
  document.getElementById('rawOut').textContent = t;
  refresh();
}

setInterval(refresh, 750);
refresh();
  </script>
</body>
</html>
)rawliteral";

  page.replace("</style>", birdBuddyThemeOverrides() + String("\n</style>"));
  page.replace("Bird Buddy Field Control", WEB_TITLE);
  page.replace("local test surface · BT401 bridge · input telemetry · ember debug field", WEB_SUBTITLE);
  page.replace("Switch stable", SECOND_INPUT_STATE_LABEL);
  page.replace("Switch raw", SECOND_INPUT_RAW_LABEL);

  return page;
}

void handleRoot() {
  server.send(200, "text/html", htmlPage());
}

void setupWiFi() {
  Serial.println();
  Serial.print(F("Connecting to Wi-Fi: "));
  Serial.println(WIFI_SSID);

  WiFi.mode(WIFI_STA);
  WiFi.persistent(false);
  WiFi.setSleep(false);
  WiFi.setTxPower(WIFI_POWER_19_5dBm);
  WiFi.setHostname(MDNS_NAME);
  WiFi.disconnect(true, true);
  delay(100);
  Serial.print(F("Wi-Fi MAC: "));
  Serial.println(WiFi.macAddress());
  delay(250);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000) {
    delay(300);
    Serial.print(F("."));
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    wifiConnected = true;
    Serial.print(F("Wi-Fi connected: "));
    Serial.println(WiFi.localIP());
    printWiFiDiagnostics();
    lastEvent = "wifi connected";

    if (MDNS.begin(MDNS_NAME)) {
      mdnsStarted = true;
      MDNS.addService("http", "tcp", HTTP_PORT);
      Serial.print(F("mDNS started: http://"));
      Serial.print(MDNS_NAME);
      Serial.println(F(".local"));
    } else {
      Serial.println(F("mDNS failed."));
    }
  } else {
    wifiConnected = false;
    WiFi.disconnect(false, true);
    delay(300);
    printWiFiDiagnostics();
    scanForTargetWiFi();

    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(AP_SSID, AP_PASSWORD);
    Serial.print(F("AP fallback started: "));
    Serial.println(AP_SSID);
    Serial.print(F("AP IP: "));
    Serial.println(WiFi.softAPIP());
    lastEvent = "wifi AP fallback";
  }

  printWebUrl();
}

void maintainWiFi() {
  if (WiFi.getMode() != WIFI_STA && WiFi.getMode() != WIFI_AP_STA) {
    return;
  }

  bool connected = WiFi.status() == WL_CONNECTED;
  if (connected != wifiConnected) {
    wifiConnected = connected;
    lastEvent = connected ? "wifi reconnected" : "wifi disconnected";
    Serial.println(lastEvent);
    if (connected) {
      Serial.print(F("Wi-Fi IP: "));
      Serial.println(WiFi.localIP());
      printWiFiDiagnostics();
      if (!mdnsStarted && MDNS.begin(MDNS_NAME)) {
        mdnsStarted = true;
        MDNS.addService("http", "tcp", HTTP_PORT);
        Serial.print(F("mDNS started: http://"));
        Serial.print(MDNS_NAME);
        Serial.println(F(".local"));
      }
    }
  }
}

void setupServer() {
  server.on("/", HTTP_GET, handleRoot);
  server.on("/api/status", HTTP_GET, handleApiStatus);
  server.on("/api/thresholds", HTTP_POST, handleApiThresholds);
  server.on("/api/thresholds", HTTP_GET, handleApiThresholds);
  server.on("/api/payloads", HTTP_POST, handleApiPayloads);
  server.on("/api/payloads", HTTP_GET, handleApiPayloads);
  server.on("/api/send_fsr", HTTP_POST, handleApiSendFsr);
  server.on("/api/send_switch", HTTP_POST, handleApiSendSwitch);
  server.on("/api/bt401_at", HTTP_POST, handleApiBt401At);
  server.on("/api/bt401_at", HTTP_GET, handleApiBt401At);
  server.on("/api/setup_bt", HTTP_POST, []() {
    configureBt401AudioBleMode();
    sendJson(buildStatusJson());
  });
  server.begin();
  Serial.println(F("HTTP server started."));
}


#ifdef DEBUG_INPUTS
void printInputDebug() {
  static uint32_t lastInputDebugPrintMs = 0;

  if (!inputDebugEnabled) {
    return;
  }

  uint32_t now = millis();
  if (now - lastInputDebugPrintMs < INPUT_DEBUG_PRINT_MS) {
    return;
  }
  lastInputDebugPrintMs = now;

  Serial.print(F("raw fsrA="));
  Serial.print(fsrA.value);
  Serial.print(F(" fsrScl="));
  Serial.print(secondFsr.value);
  Serial.print(secondFsr.pressed ? F("(pressed)") : F("(released)"));
  Serial.print(F(" thresholds press<="));
  Serial.print(fsrPressThreshold);
  Serial.print(F(" release>="));
  Serial.print(fsrReleaseThreshold);
  Serial.print(F(" sclPress<="));
  Serial.print(secondFsrPressThreshold);
  Serial.print(F(" sclRelease>="));
  Serial.print(secondFsrReleaseThreshold);
  Serial.println();
}
#endif

void handleUsbCommand(String command) {
  command.trim();
  if (command.length() == 0) {
    return;
  }

  if (command == "/help") {
    Serial.println(F("Commands:"));
    Serial.println(F("  /help       Show this help."));
    Serial.println(F("  /status     Query BT401 and print pin/input status."));
    Serial.println(F("  /setup-bt   Re-send BT401 audio + BLE setup sequence."));
    Serial.println(F("  /thresholds Print active-low FSR thresholds."));
#ifdef DEBUG_INPUTS
    Serial.println(F("  /inputs on  Print raw FSR/switch values every 200 ms."));
    Serial.println(F("  /inputs off Stop periodic raw input prints."));
#endif
    Serial.println(F("  /send-fsr   Send FSR test payload."));
    Serial.println(F("  /send-sw    Send switch test payload."));
  } else if (command == "/status") {
    printPinout();
    printRuntimeStatus();
    queryBt401Status();
  } else if (command == "/setup-bt") {
    configureBt401AudioBleMode();
  } else if (command == "/thresholds") {
    Serial.print(F("FSR active-low press <= "));
    Serial.print(fsrPressThreshold);
    Serial.print(F(", release >= "));
    Serial.println(fsrReleaseThreshold);
    Serial.print(F("SCL FSR active-low press <= "));
    Serial.print(secondFsrPressThreshold);
    Serial.print(F(", release >= "));
    Serial.println(secondFsrReleaseThreshold);
#ifdef DEBUG_INPUTS
  } else if (command == "/inputs on") {
    inputDebugEnabled = true;
    Serial.println(F("Input debug prints enabled."));
  } else if (command == "/inputs off") {
    inputDebugEnabled = false;
    Serial.println(F("Input debug prints disabled."));
#endif
  } else if (command == "/send-fsr") {
    sendBt401Bytes("manual-fsr", fsrPayload, fsrPayloadLength);
  } else if (command == "/send-sw") {
    sendBt401Bytes("manual-switch", switchPayload, switchPayloadLength);
  } else {
    Serial.print(F("Unknown command: "));
    Serial.println(command);
    Serial.println(F("Type /help."));
  }
}

void pumpUsbCommands() {
  static String usbLine;

  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (usbLine.length() > 0) {
        handleUsbCommand(usbLine);
        usbLine = "";
      }
    } else {
      usbLine += c;
    }
  }
}

// ---------- Arduino lifecycle ----------

void setup() {
  Serial.begin(USB_BAUD);
  uint32_t serialStartMs = millis();
  while (!Serial && millis() - serialStartMs < SERIAL_STARTUP_WAIT_MS) {
    delay(10);
  }

  Serial1.begin(BT401_BAUD, SERIAL_8N1, BT401_RX_PIN, BT401_TX_PIN);

  statusPixel.begin();
  statusPixel.setBrightness(255);
  setStatusPixel(0, 255, 0);

  analogReadResolution(12);
  analogSetPinAttenuation(FSR_A_PIN, ADC_11db);
  analogSetPinAttenuation(SECOND_INPUT_PIN, ADC_11db);

  Serial.println();
  Serial.println(F("Bird Buddy firmware skeleton ready."));
  Serial.println(F("Arduino IDE: USB CDC On Boot must be Enabled for QT Py ESP32-S3 Serial Monitor."));
  loadSettings();
  WiFi.onEvent(onWiFiEvent);
  printPinout();
  Serial.println(F("Type /help for commands."));

  bootMs = millis();
  setupWiFi();
  setupServer();
  configureBt401AudioBleMode();
  queryBt401Status();

  printWebUrl();
}

void loop() {
  server.handleClient();
  maintainWiFi();
  pumpBt401ToUsb();
  pumpUsbCommands();
  updateInputs();
#ifdef DEBUG_INPUTS
  printInputDebug();
#endif
  updateStatusPixel();
  printRuntimeStatus();
}

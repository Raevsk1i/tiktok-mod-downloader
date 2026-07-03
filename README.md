# tiktok-region-patcher

Патчит **TikTok**, чтобы приложение не определяло регион как **Россию**: все
вызовы `TelephonyManager` для страны/оператора подменяются на фиксированные
значения (по умолчанию Германия: `de` / MCC `262`), после чего лента «Для вас»
отдаёт международный каталог.

> Инструмент для личного использования. Модификация приложения может нарушать
> ToS. TikTok APK этот проект **не скачивает** — его нужно установить
> самостоятельно.

## Android-приложение (рекомендуется)

Скачайте **`dist/TikTokRegionPatcher.apk`** на телефон и установите его.

### Как пользоваться на телефоне

1. Установите **TikTok** (Play Store или APK).
2. Установите **TikTok Region Patcher** (`dist/TikTokRegionPatcher.apk`).
3. Откройте патчер → **«Пропатчить установленный TikTok»**.
4. Разрешите **установку из неизвестных источников** для этого приложения, если система попросит.
5. Подтвердите установку пропатченного TikTok в системном диалоге.

Если установка не удаётся из‑за другой подписи — сначала **удалите оригинальный
TikTok** (кнопка в приложении или вручную), затем повторите патч.

Альтернатива: **«Выбрать APK / XAPK из файлов»** — если TikTok ещё не
установлен, но APK лежит в «Загрузках».

Приложение само: копирует APK → патчит dex → подписывает → запускает установку.
Split-APK (несколько файлов) поддерживаются.

### Сборка APK из исходников

```bash
cd android-app
./gradlew assembleDebug
# APK: android-app/app/build/outputs/apk/debug/app-debug.apk
```

Нужны JDK 17 и Android SDK (см. CI в `.github/workflows/android.yml`).

---

## CLI для ПК (Python)

Альтернатива для компьютера с `adb`: распаковка через apktool, правка smali,
пересборка и подпись на десктопе.

### Как работает

1. **Acquire** – локальный APK / бандл или URL. Split-бандлы (`.xapk`, `.apks`) разбираются на base + splits.
2. **Decode** – `apktool d` → smali.
3. **Patch** – вызовы `TelephonyManager` заменяются на `const-string`.
4. **Rebuild** – `apktool b`.
5. **Sign** – `uber-apk-signer` (v1/v2/v3).

```smali
# было
invoke-virtual {v2}, Landroid/telephony/TelephonyManager;->getNetworkCountryIso()Ljava/lang/String;
move-result-object v2
# стало
const-string v2, "de"
```

| Method | Значение (по умолчанию) |
| --- | --- |
| `getNetworkCountryIso` / `getSimCountryIso` | `de` |
| `getNetworkOperator` / `getSimOperator` | `26201` |
| `getNetworkOperatorName` / `getSimOperatorName` | `Telekom.de` |

### Требования (CLI)

Python 3.10+, Java 8+, `aapt2`. `apktool` и `uber-apk-signer` скачиваются автоматически.

```bash
pip install -e .
python -m tiktok_region_patcher path/to/tiktok.apk -o ./out
adb install -r out/*Signed.apk
```

## Ограничения

- Если **0 патчей** — TikTok читает регион через обёртку/нативный код.
- Регион также зависит от **IP и локали** — используйте VPN.
- Пропатченная сборка не обновляется из Play Store; после обновления TikTok патч нужно повторить.

## Разработка

```bash
pip install -e '.[dev]' && pytest          # Python
cd android-app && ./gradlew assembleDebug  # Android
```

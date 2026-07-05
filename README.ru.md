# Hijacker2 — версия для встроенной карты (OnePlus 7 Pro / qcacld-3.0)

[🇬🇧 English](README.md) · **🇷🇺 Русский**

Форк [yesimxev/Hijacker](https://github.com/yesimxev/Hijacker) (который сам форк
[chrisk44/Hijacker](https://github.com/chrisk44/Hijacker)) — графическая оболочка для набора
Aircrack-ng (airodump-ng, aireplay-ng, MDK3, reaver) на Android.

**В чём отличие:** эта сборка заставляет airodump/aireplay работать на **встроенном Wi-Fi
OnePlus 7 Pro** (Qualcomm **qcacld-3.0**, FullMAC) — *внешний USB-адаптер не нужен.*
Оригинальный Hijacker рассчитан на mac80211/`airmon-ng`-адаптеры и на этом железе просто
пишет **«Airodump is not running!»**. Здесь же он ловит **19 точек + клиентские станции на
каналах 1/6/11** через внутреннюю карту.

> Нужно ядро с поддержкой monitor mode + инъекции в qcacld. См.
> [Требования](#требования).

---

## Требования

- **OnePlus 7 Pro** (`guacamole`, SM8150) на **OxygenOS 11**, разблокированный загрузчик,
  root через **Magisk**.
- **Ядро NetHunter с monitor mode + инъекцией пакетов на встроенной карте** (порт
  qcacld-3.0). Берётся из ветки op7 на 4PDA:
  **<https://4pda.to/forum/index.php?showtopic=954422&view=findpost&p=144130227>**
  Проверка: файл `/sys/module/wlan/parameters/con_mode` должен существовать и принимать
  значение `4` (монитор).
- Бинарники aircrack-ng/`iw`/busybox уже внутри APK (распаковываются в
  `/data/data/com.hijacker/files/bin` при первом запуске).

Другие устройства Qualcomm с таким же портом монитора/инъекции тоже могут завестись —
приложению нужны только sysfs-ручка `con_mode` и `iw` с поддержкой nl80211.

## Как это работает (адаптация под qcacld)

Пришлось решить четыре device-специфичные проблемы. Все фиксы — в самом приложении:

| Проблема | Причина на qcacld | Решение |
|---|---|---|
| `«Airodump is not running!»` | Тулзы запускались через `su -c <cmd>`, а он **наследует `CapEff=0`** приложения → нет `CAP_NET_RAW` (raw-сокет) / `CAP_NET_ADMIN` (интерфейс) / `CAP_DAC_OVERRIDE` (свой каталог `0700`). airodump умирает мгновенно. | `execRoot()` запускает всё через **login-`su`** (`Runtime.exec("su")` + команда в stdin) — Magisk даёт **полные capabilities**. |
| Монитор не включался | `enable_monMode` был пуст; `airmon-ng`/`iw type monitor` FullMAC-драйвер игнорирует. | Дефолтный `enable_monMode` переключает `con_mode`: `echo 4 \| tee /sys/module/wlan/parameters/con_mode` (mksh не открывает sysfs через `>`, а `tee` — да), ждёт ~30-40 с реинициализации прошивки (опрашивает `/sys/class/net/wlan0/type` == `803`/radiotap) и поднимает `wlan0`. Выполняется вне UI-потока; идемпотентно. |
| `«not running»` через ~2 с | Watchdog видел `running == true`, но PID airodump ещё нет *во время* 35-сек окна включения — и глушил процесс. | Флаг `Airodump.starting`; watchdog игнорирует это окно. |
| Пустой список точек | Собственное переключение каналов airodump-ng (wireless-ext ioctl) qcacld **молча игнорирует**, поэтому `--band bg` стоит на одном канале. | **Channel-hopper** переключает канал через встроенный `iw` (nl80211, который драйвер уважает), крутится пока жив airodump и сам завершается при его выходе. Уважает фиксированный канал для перехвата handshake. |

Монитор включается при запуске приложения и при старте airodump (в первый раз увидишь
плашку *«Enabling monitor mode (~35 s)»* — реинициализация прошивки qcacld медленная, это
норма). При выходе из приложения монитор выключается (`con_mode=0`), обычный Wi-Fi Android
возвращается.

## Установка

1. Скачай **`Hijacker2-<версия>.apk`** со страницы
   [Releases](https://github.com/fuad00/Hijacker2/releases) (или артефакт
   `Hijacker2-release-apk` из зелёного прогона
   [Actions](https://github.com/fuad00/Hijacker2/actions)).
2. Установи. Дай root, когда спросит Magisk (Superuser access = *Apps and ADB*).
3. Открой, прими disclaimer, дождись монитора, нажми **▶ Start**.

Release-APK подписаны стабильным ключом — обновления встают поверх друг друга. Если стоял
иначе подписанный билд (например, оригинальный Hijacker) — сначала удали его.

## Сборка из исходников

Полностью воспроизводится на CI — локальный тулчейн не нужен:

- Пуш в `master` (или запуск workflow **Android CI** вручную) →
  [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) собирает на
  `ubuntu-latest` с JDK 17, ставит `ndk;26.1.10909125` + `cmake;3.22.1`, запускает
  `./gradlew assembleDebug assembleRelease` и выкладывает оба APK артефактами.
- Локально: JDK 17, Android SDK 34, NDK 26.1.10909125 → `./gradlew assembleRelease`.

Стек сборки: **Gradle 8.5, AGP 8.2.2, compileSdk 34, targetSdk 29, minSdk 24**, нативный
парсер CSV через CMake/NDK. (Нюанс AGP 8: `android.nonFinalResIds=false` — чтобы исходный
код с `switch(R.id.*)` компилировался без переписывания.)

## Благодарности

- **chrisk44** — оригинальный Hijacker.
- **yesimxev** — поддерживаемый форк, на котором это основано.
- **pr0misc** и **2loch-ness6** — модернизация Gradle/SDK и CI на GitHub Actions, откуда
  взят тулчейн этой сборки.
- **Loukious** — порт монитора/инъекции в qcacld-3.0, благодаря которому внутренняя карта
  вообще пригодна.
- Команды **Aircrack-ng** и **Kali NetHunter**.

## Лицензия

GPLv3 (унаследована от Hijacker). См. [COPYING](COPYING).

## ⚠️ Правовое

Только для сетей, которыми ты владеешь или на тест которых есть явное разрешение. Monitor
mode и инъекция пакетов регулируются законодательством во многих странах. Ответственность за
использование — на тебе. Без гарантий.

# GPS Setter

[English](README.md) | [Русский](README.ru.md)

[![Последний релиз](https://img.shields.io/github/v/release/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/releases/latest)
[![Загрузки](https://img.shields.io/github/downloads/kovag1978-oss/android_gps_setter/total)](https://github.com/kovag1978-oss/android_gps_setter/releases)
[![Лицензия: GPL-3.0](https://img.shields.io/github/license/kovag1978-oss/android_gps_setter)](LICENSE)
[![Открытые задачи](https://img.shields.io/github/issues/kovag1978-oss/android_gps_setter)](https://github.com/kovag1978-oss/android_gps_setter/issues)

GPS Setter — это модуль Xposed для изменения местоположения, которое видят приложения Android. Поддерживается перехват запросов геолокации во всей системе и в выбранных приложениях.

> **Требования:** Android 8.1 или новее и совместимая с Xposed среда, например LSPosed или Vector. Для LSPosed/Vector требуется root-доступ. Также поддерживается LSPatch с вручную встроенной геолокацией.

## Скачать

> **ID приложения изменился:** в старых релизах использовался `io.github.kovag1978oss.gpssetter`, текущий ID — `io.github.kovag1978_oss.gpssetter`. Android считает их разными приложениями, поэтому перед установкой текущей версии удалите старую. Данные и настройки не переносятся.

Скачайте последнюю arm64-версию со страницы [релизов GitHub](https://github.com/kovag1978-oss/android_gps_setter/releases/latest):

| Вариант | Карты | Fused Location | Скачать |
| --- | --- | --- | --- |
| **Full** | Google Maps | Google Play services | [APK Full](https://github.com/kovag1978-oss/android_gps_setter/releases/latest/download/app-full-arm64-v8a-release.apk) |
| **FOSS** | MapLibre | microG | [APK FOSS](https://github.com/kovag1978-oss/android_gps_setter/releases/latest/download/app-foss-arm64-v8a-release.apk) |

Эта версия использует ID приложения `io.github.kovag1978_oss.gpssetter`. Ранее использовался ID `io.github.kovag1978oss.gpssetter`; Android считает их разными приложениями, поэтому перед установкой этой версии удалите старый GPS Setter. Данные и настройки автоматически не переносятся.

## Настройка

1. Установите APK и включите **GPS Setter** в LSPosed или Vector.
2. В списке модулей включите **Android System** (`android`), чтобы подменять геолокацию во всей системе.
3. Для варианта Full включите **Google Play services** (`com.google.android.gms`), чтобы перехватывать запросы геолокации через GMS.
4. Для подмены геолокации только в выбранных приложениях также включите их в списке модулей.
5. Выберите местоположение в GPS Setter и включите подмену. После изменения списка модулей перезапустите затронутые приложения; для перехвата геолокации во всей системе может потребоваться перезагрузка телефона.

Android System и Google Play services указаны в приложении как рекомендуемые пункты для включения, но активировать их нужно в менеджере Xposed.

## Возможности

- Задать местоположение для отдельных приложений или всей системы.
- Выбрать перехват геолокации во всей системе или только в отдельных приложениях.
- Случайным образом изменять координаты вблизи выбранной точки.
- Корректировать активное местоположение с помощью экранного джойстика.
- Сохранять избранные места, а также импортировать и экспортировать их.
- Использовать плитку GPS Setter в быстрых настройках Android, чтобы голосом выбрать сохранённое место или сказать «стоп» для выключения подмены. Открывается системный интерфейс распознавания речи, само приложение GPS Setter не запускается. Варианты распознавания проверяются по порядку, а название избранного может быть частью более длинной фразы. При отсутствии совпадений или отмене ввода текущая подмена не меняется.

## Как это работает

Модуль перехватывает API геолокации Android, включая `Location` и `LocationManager`, и передаёт приложениям выбранные координаты. Для системного перехвата поддерживаются изменения службы геолокации, появившиеся в Android 14 и новее.

## Благодарности

- [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter) — за оригинальный проект.
- [MapLibre](https://github.com/maplibre/maplibre-native) и [microG](https://github.com/microg/GmsCore) — за компоненты карт и геолокации для FOSS-версии.

## Поддержка

Если GPS Setter оказался вам полезен, вы можете [поддержать проект на GitHub](https://github.com/sponsors/kovag1978-oss).

Лицензия: [GPL-3.0-only](LICENSE).

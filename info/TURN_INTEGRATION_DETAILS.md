# TURN-интеграция в «Кроличьей норе»

Документ описывает текущую реализацию Android-клиента. Здесь намеренно нет адресов, ключей и имён рабочих серверов.

## Общая схема

```text
WireGuard userspace backend
        │
        ▼
локальный TURN transport hub
        │
        ├─ поток DTLS/TCP или DTLS/UDP
        ├─ дополнительные параллельные потоки
        └─ необязательная защита WRAP
        │
        ▼
TURN relay → transport proxy → WireGuard endpoint
```

Приложение создаёт Android `VpnService`, защищает внешние сокеты от повторного попадания в туннель и только затем запускает TURN transport hub. При остановке туннеля или отмене подключения связанные transport-потоки закрываются.

## Основные компоненты

### Android UI

- `ui/src/main/java/com/wireguard/android/turn/TurnSettings.kt` — модель TURN-метаданных профиля.
- `ui/src/main/java/com/wireguard/android/turn/TurnConfigProcessor.kt` — чтение метаданных и подготовка активной конфигурации.
- `ui/src/main/java/com/wireguard/android/turn/TurnProxyManager.kt` — запуск, остановка и восстановление transport hub.
- `ui/src/main/java/com/wireguard/android/turn/PhysicalNetworkMonitor.kt` — выбор физической сети и реакция на переключение Wi-Fi/mobile.
- `ui/src/main/java/com/wireguard/android/activity/CaptchaCoordinator.kt` — сериализация запросов ручной CAPTCHA.
- `ui/src/main/java/com/wireguard/android/activity/CaptchaNotification.kt` — системное уведомление о необходимости проверки.

### Tunnel backend

- `tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java` — Android `VpnService`, защита сокетов и userspace WireGuard backend.
- `tunnel/src/main/java/com/wireguard/android/backend/TurnBackend.java` — JNI-мост для transport hub и CAPTCHA callback.
- `tunnel/tools/libwg-go/turn-client.go` — мультипоточный TURN/DTLS transport.
- `tunnel/tools/libwg-go/credentials.go` — ограниченный кэш credentials.
- `tunnel/tools/libwg-go/vk.go` — получение credentials по ссылке на звонок.
- `tunnel/tools/libwg-go/vk_captcha.go` и `slider_captcha.go` — автоматические варианты CAPTCHA.
- `tunnel/tools/libwg-go/stream_pool.go` — управление набором готовых потоков.
- `tunnel/tools/libwg-go/turn-dns-resolver.go` — DNS-запросы через защищённые внешние сокеты.

## Жизненный цикл подключения

1. Пользователь выбирает профиль и нажимает призматическую кнопку.
2. Клиент проверяет, нужны ли профилю GeoIP/GeoSite. Если нужны и файлов нет, сначала выполняется загрузка.
3. Android запрашивает разрешение на создание системного туннеля при необходимости.
4. `GoBackend` создаёт TUN-интерфейс и регистрирует `VpnService` в JNI.
5. Внешние WireGuard и TURN-сокеты исключаются из маршрута приложения через `VpnService.protect()`.
6. `TurnProxyManager` запускает transport hub.
7. Hub получает credentials, создаёт несколько TURN allocation и выполняет DTLS handshake.
8. После готовности транспорта WireGuard-пакеты распределяются между доступными потоками.
9. При смене физической сети DNS/HTTP-состояние сбрасывается, а транспорт восстанавливается.

Повторное нажатие на кнопку во время запуска отменяет текущую попытку и освобождает созданные ресурсы.

## Поддерживаемая авторизация

Пользовательский сценарий использует `Mode = vk_link`: credentials запрашиваются по индивидуальной ссылке на звонок. Другие исторические способы получения credentials не считаются поддерживаемыми и не документируются.

## Режимы transport proxy

### `proxy_v2`

Основной режим. После DTLS handshake клиент передаёт идентификатор сессии и номер потока. Proxy может собрать несколько transport-потоков в одно логическое соединение.

### `proxy_v1`

Совместимый режим без передачи идентификатора сессии. Используется только с соответствующей серверной реализацией.

### `wireguard`

Прямой TURN relay без дополнительного DTLS proxy-протокола. Этот режим требует совместимой топологии и не поддерживает WRAP.

## Мультипоточность и восстановление

- Количество потоков задаётся `StreamNum`.
- `StreamsPerCred` ограничивает число потоков на один набор credentials.
- Передача использует только готовые потоки; временно недоступные исключаются из выбора.
- Потоки запускаются со сдвигом, чтобы не создавать одновременный всплеск allocation.
- `WatchdogTimeout` может перезапустить транспорт при длительном отсутствии входящего трафика.
- Смена физической сети инициирует безопасное переподключение без ручного переключения профиля.

## WRAP

WRAP добавляет симметричную защиту полезной нагрузки между клиентом и совместимым proxy.

Ограничения:

- доступен только для `proxy_v1` и `proxy_v2`;
- требует TCP/DTLS transport, поэтому `UseUDP` должен быть выключен;
- обе стороны должны использовать один ключ;
- ключ не следует публиковать, сохранять в журналах или передавать в открытом виде.

## CAPTCHA

При ответе внешнего API о необходимости проверки клиент:

1. пробует поддерживаемую автоматическую проверку;
2. при наличии slider-варианта пробует локальную обработку;
3. при неудаче создаёт системное уведомление;
4. по уведомлению открывает отдельный WebView для ручного прохождения;
5. после успешного результата возвращает токен в ожидающий transport-поток.

Одновременно показывается только одна ручная CAPTCHA. Остальные запросы ожидают результат, чтобы приложение не открывало несколько перекрывающихся экранов.

## Метаданные профиля

TURN-параметры хранятся в комментариях `#@wgt:` внутри стандартного WireGuard `.conf`. Обычные клиенты воспринимают их как комментарии.

```ini
[Peer]
Endpoint = tunnel.example.com:<wireguard-port>
PublicKey = <wireguard-public-key>
AllowedIPs = <routes>

#@wgt:EnableTURN = true
#@wgt:UseUDP = false
#@wgt:IPPort = proxy.example.com:<proxy-port>
#@wgt:VKLink = https://vk.com/call/join/<call-token>
#@wgt:Mode = vk_link
#@wgt:PeerType = proxy_v2
#@wgt:StreamNum = <stream-count>
#@wgt:LocalPort = <local-port>
#@wgt:StreamsPerCred = <streams-per-credential>
#@wgt:WatchdogTimeout = <timeout-seconds>
#@wgt:UseWrap = false
#@wgt:WrapKeyHex = <shared-wrap-key>
```

Полный обезличенный шаблон находится в [config_example.conf](config_example.conf).

## Подписки и маршрутизация

Подписка хранит индивидуальный URL и полученную конфигурацию. Клиент проверяет её каждые 12 часов и по ручной команде. Ошибка или отзыв на стороне сервиса не должны подменять рабочий профиль случайным содержимым.

Необходимость прямой маршрутизации передаётся вместе с Android-конфигурацией профиля. Только такие профили требуют `geoip.dat` и `geosite.dat`; остальные продолжают работать без геофайлов.

Внутренний backend-контракт намеренно не входит в публичную документацию репозитория.

## Журналы

Экран журналов группирует записи по источникам:

- системный туннель;
- TURN и CAPTCHA;
- подписки;
- прямая маршрутизация;
- приложение и ошибки.

Не добавляйте в журналы приватные ключи, WRAP-ключи, полные subscription URL или credentials.

## Безопасность обновлений

Клиент получает метаданные последнего стабильного GitHub Release, выбирает APK с ожидаемым именем и проверяет опубликованный SHA-256 перед передачей файла Android Package Installer. Android дополнительно требует, чтобы обновление было подписано тем же сертификатом, что и установленная версия.

Release workflow хранит keystore и пароли только в GitHub Secrets. Они не должны попадать в исходный код, Actions artifacts или журналы.

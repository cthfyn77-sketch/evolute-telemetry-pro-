# Evolute Telemetry Pro

Отдельное Android-приложение для локального мониторинга Evolute i-JOY. Package ID: `com.evolute.telemetrypro`.

## Источник телеметрии

- Единственный источник телеметрии — настоящий `evaconnect-mcp` (stdio MCP server).
- На телефоне MCP запускается через Termux; локальный адаптер `tools/evaconnect_mcp_bridge.py` предоставляет приложению узкий HTTP-интерфейс.
- Android обращается только к `http://127.0.0.1:8765/v1/status` с заголовком `X-Bridge-Token`.
- Bridge вызывает только MCP-инструмент `evolute_status`. Он не экспортирует команды управления автомобилем.
- Прямой Android-клиент Evolute API, SMS-вход и refresh-token flow удалены из приложения.
- Ключ bridge хранится в Android Keystore (AES-GCM); локальные снимки сохраняются в SQLite.
- Опрос выполняется раз в 30 секунд. Снимок засчитывается только после успешной записи в SQLite.
- MCP public status не предоставляет флаг `ready`; приложение сохраняет его как неизвестный, не подменяя значением false.

## Запуск локального bridge в Termux

1. Установите evaconnect и MCP-зависимости в том же Python 3.12+ окружении.
2. Убедитесь, что штатная сессия evaconnect доступна через `~/.config/evolute/credentials.json`.
3. Запустите bridge из окружения evaconnect:

   ```bash
   EVOLUTE_MCP_COMMAND="$HOME/evaconnect-termux/.venv/bin/evaconnect-mcp" \
     "$HOME/evaconnect-termux/.venv/bin/python" tools/evaconnect_mcp_bridge.py
   ```

4. Bridge слушает только `127.0.0.1:8765`. Ключ создаётся в `~/.config/evolute/bridge-token` с правами файла 0600. Не публикуйте его и не меняйте адрес прослушивания на `0.0.0.0`.
5. В приложении вставьте содержимое `~/.config/evolute/bridge-token`. Ключ будет сохранён в Android Keystore.
6. Health-check: `GET http://127.0.0.1:8765/health`. Телеметрия: `GET /v1/status` с заголовком `X-Bridge-Token`.

Bridge использует MCP stdio transport и вызывает MCP-инструмент `evolute_status`; GPS/PII не запрашиваются. Не опрашивайте MCP чаще одного раза в 5 секунд.

## Сохранение и аналитика

- Снимки хранятся локально в SQLite.
- Существующие графики, история поездок, зарядные сессии, анализ батареи и расчёт запаса хода работают по сохранённым снимкам.
- Диагностика отдельно показывает успехи и ошибки MCP-сбора.
- Приложение остаётся read-only.

## Проверки и сборка

GitHub Actions запускает unit-тесты. Сборку APK не запускать без отдельного разрешения пользователя.

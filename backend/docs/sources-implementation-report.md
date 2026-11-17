# Подключение источников «Кадра»

Бэкенд проверен 16 ноября 2026 года. [План реализации](sources-implementation-plan.md) · [Контракт и запуск](../README.md)

## Реализация

- `SourceController` обслуживает четыре операции списков и подключения. `SourceConnectionService` берёт метаданные из `MangaSourceRegistry`, сортирует выдачу по `sourceId` через реестр и читает подключения через `JdbcClient`.
- Миграция `V3__create_connected_sources.sql` добавляет `connected_sources` с первичным ключом `source_id` и одним начальным подключением `peppercarrot`. `PUT` использует `INSERT ... ON CONFLICT DO NOTHING`. `DELETE` удаляет только строку подключения. Операции возвращают одинаковые успешные статусы при повторном вызове.
- `GET /api/v1/catalog` применяет те же параметры `q` и `sourceId` к подключённым адаптерам. При отключении прямые маршруты карточки, глав и страниц остаются доступны по зарегистрированному адаптеру. Библиотека продолжает использовать сохранённые данные.
- Фактический REST-контракт совпадает с планом и [заданием для Angular](../../frontend/docs/sources-implementation-prompt.md). `websiteUrl` Pepper&Carrot равен `https://www.peppercarrot.com`.

## Живые запросы и ответы

В примерах указан путь после `/api/v1`. Все запросы выполнены на `127.0.0.1:8080`.

| Запрос | Полученный статус | Полученный ответ |
| --- | --- | --- |
| `GET /extensions` | `200` | `{"items":[{"sourceId":"peppercarrot","sourceName":"Pepper&Carrot","websiteUrl":"https://www.peppercarrot.com","enabled":true}]}` |
| `GET /sources` | `200` | `{"items":[{"sourceId":"peppercarrot","sourceName":"Pepper&Carrot","websiteUrl":"https://www.peppercarrot.com"}]}` |
| `PUT /sources/peppercarrot` без тела, дважды | `200, 200` | `{"sourceId":"peppercarrot","sourceName":"Pepper&Carrot","websiteUrl":"https://www.peppercarrot.com"}` |
| `GET /catalog?sourceId=peppercarrot&q=pepper` | `200` | `items` содержит Pepper&Carrot. Поиск `q=absent` дал пустой список. |
| `DELETE /sources/peppercarrot`, дважды | `204, 204` | Пустое тело. |
| `GET /sources` и `GET /catalog` после отключения | `200` | `{"items":[]}` для обоих запросов. |
| `GET /catalog?sourceId=peppercarrot` после отключения | `409` | `application/problem+json`, `code: "SOURCE_DISABLED"`. |
| `PUT /sources/unknown` и `DELETE /sources/unknown` | `404` | `application/problem+json`, `code: "SOURCE_NOT_FOUND"`. |
| `PUT /sources/BAD_id` и `DELETE /sources/BAD_id` | `400` | `VALIDATION_ERROR` с ошибкой поля `sourceId`. |

Фактический ответ на запрос каталога с отключённым источником:

```json
{
  "detail": "Источник отключён.",
  "instance": "/api/v1/catalog",
  "status": 409,
  "title": "Conflict",
  "type": "about:blank",
  "code": "SOURCE_DISABLED",
  "errors": []
}
```

## Команды и результаты

Запуск и сборка из `backend/`:

```powershell
docker compose config --quiet
docker compose up -d --wait postgres
.\mvnw.cmd -B -ntp verify
```

Бэкенд запущен командой `java -jar target/manga-reader-0.1.0-SNAPSHOT.jar` с переменными из `.env`. Инструкция по переменным есть в [README](../README.md#запуск-из-чистого-клона-в-windows-powershell). При упаковке Windows удерживала JAR запущенного приложения. После остановки старого процесса Maven собрал пакет.

- `verify` завершилась с `BUILD SUCCESS`. Четыре HTTP-теста прошли без пропусков на отдельном PostgreSQL 17.12 в Testcontainers. Проверены V1–V3 на чистой БД, порядок, метаданные, ошибки, восемь параллельных вызовов каждого метода изменения, единственная строка подключения и чтение с сохранённым прогрессом после отключения. Внешние ответы для автоматического теста чтения подставлялись локально.
- На существующем томе `manga-reader_postgres-data` Flyway применил V3 поверх V1 и V2. В исходной библиотеке была одна запись. Ответ списка библиотеки совпал до миграции, после отключения и после перезапуска по полному содержимому.
- При отключённом источнике по записи библиотеки открылись карточка, список глав и пять ссылок на страницы русского эпизода 1 в порядке `0–4`. Первый файл скачался как `200 image/jpeg` с Pepper&Carrot.
- После перезапуска Spring Boot с отключённым источником Flyway подтвердил версию 3, список подключений и каталог остались пустыми, а явный фильтр продолжал возвращать `409`. Затем Pepper&Carrot подключён обратно. В PostgreSQL одна строка, поиск каталога снова находит тайтл.
- Через действующий Angular dev proxy на порту `4200` проверены оба новых списка. Работа с вкладками «Обзор» описана в отдельном задании для Angular.

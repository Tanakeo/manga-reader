# Manga Reader

Локальная веб-читалка по мотивам Suwayomi. Бэкенд использует Java 21, Spring Boot 4.1.2, Spring MVC, Spring Data JPA, Flyway и PostgreSQL 17.12. Источники включают Pepper&Carrot, MangaDex, MangaKakalot и MangaLib. Библиотека поддерживает создание, чтение, редактирование, сохранение прогресса и удаление.

Этот README описывает текущую реализацию. Исходники, Maven Wrapper, Docker Compose и настройки PostgreSQL находятся в `backend/`. Angular находится в соседнем каталоге `frontend/`. [Первоначальный план](BACKEND_PLAN.md) · [Подключение источников](docs/sources-implementation-report.md) · [Каталог MangaDex и офлайн-чтение](docs/catalog-and-offline-report.md)

## Запуск из чистого клона в Windows PowerShell

Нужны **JDK 21**, **Docker Desktop** с запущенным Linux Engine и Docker Compose. Maven 3.9.11 загружает Wrapper при первом запуске. Доступ к Maven Central нужен для сборки, доступ к `www.peppercarrot.com` — для глав, страниц и изображений.

Из корня репозитория перейдите в `backend/`. Команды Compose и Maven в этом README выполняются из этой папки.

```powershell
Set-Location backend
java -version
docker version
docker compose version

Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d --wait postgres
docker compose ps postgres
```

В таблице Compose ожидается `healthy`. Контейнер использует официальный образ `postgres:17.12-bookworm`, именованный том `manga-reader_postgres-data` и TCP healthcheck `pg_isready`. PostgreSQL опубликован на `127.0.0.1:5432`. Если порт занят, измените `POSTGRES_PORT` в `.env`, например на `5433`, и повторите `docker compose up -d --wait postgres`. Именованный том при этом сохраняется.

Передайте параметры подключения приложению в том же окне PowerShell. Пример `.env` использует простые значения без кавычек и подстановок.

```powershell
Get-Content -LiteralPath .env | ForEach-Object {
    if ($_ -match '^(POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_PORT)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run
```

Ожидаемый адрес API — `http://127.0.0.1:8080/api/v1`. При занятом порте приложения задайте `$env:SERVER_PORT = '8081'` перед запуском. При пользовательском порте синхронизируйте его с настройкой dev proxy Angular. Для сборки JAR выполните `.\mvnw.cmd -B verify`. Готовый JAR запускается командой `java -jar target/manga-reader-0.1.0-SNAPSHOT.jar` в окружении с теми же переменными.

Из второго окна PowerShell:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/catalog'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/library'
```

Если Docker Engine не отвечает, запустите Docker Desktop и повторите `docker version`. Если контейнер остался `unhealthy`, проверьте `docker compose ps -a postgres` и `docker compose logs --tail 80 postgres`. Параметры `POSTGRES_DB`, `POSTGRES_USER` и `POSTGRES_PASSWORD` инициализируют новую БД при первом использовании тома. Чтобы сменить их у уже созданной БД, сначала измените соответствующего пользователя или БД внутри PostgreSQL. `.env` исключён из Git.

## Контракт API

Адрес для Angular — относительный `/api/v1`. Для разработки настройте dev proxy для `/api` на `http://127.0.0.1:8080`. REST API и БД слушают loopback. Библиотека одна, авторизации нет. Angular подставляет `coverUrl` и `imageUrl` в `<img>`. Pepper&Carrot возвращает абсолютные HTTPS URL, MangaDex — относительные URL обложек и страниц через Spring Boot.

В таблице `T` означает `/api/v1/sources/{sourceId}/titles/{externalTitleId}`, а `L` — `/api/v1/library`. Для рабочего источника путь `T` подставляется как `/api/v1/sources/peppercarrot/titles/pepper-and-carrot`.

| Метод и путь | Запрос | Успешный ответ | Основные ошибки |
|---|---|---|---|
| `GET /api/v1/extensions` | Без параметров. | `200 Items<Extension>` со всеми зарегистрированными адаптерами и флагом `enabled`. | `503` БД. |
| `GET /api/v1/sources` | Без параметров. | `200 Items<Source>` с подключёнными адаптерами. | `503` БД. |
| `PUT /api/v1/sources/{sourceId}` | ID в пути, без тела. | `200 Source` при первом и повторных подключениях. | `400` формат ID, `404 SOURCE_NOT_FOUND`, `503` БД. |
| `DELETE /api/v1/sources/{sourceId}` | ID в пути, без тела. | `204` при первом и повторных отключениях. | `400` формат ID, `404 SOURCE_NOT_FOUND`, `503` БД. |
| `GET /api/v1/catalog` | Необязательные `sourceId` и `q` (до 120 символов). Для MangaDex доступны до десяти `tagId` и `sort`. Без `sourceId` обычный поиск опрашивает все подключённые адаптеры. | `200 Items<TitleSummary>`. Без подключений или совпадений приходит `{"items":[]}`. | `400` параметры, `404 SOURCE_NOT_FOUND`, `409 SOURCE_DISABLED`, `422 UNKNOWN_TAG` или `CATALOG_OPTIONS_UNSUPPORTED`, `502/503` источник или БД. |
| `GET T` | `sourceId` и `externalTitleId` в пути. | `200 TitleDetail`, включая массив `tags`. | `400` формат ID, `404 SOURCE_NOT_FOUND` / `TITLE_NOT_FOUND`, `502/503` источник, `503` БД. |
| `GET T/chapter-count` | Та же пара ID. | `200 { "chapterCount": number }` — число глав, доступных в читалке. | `400` формат ID, `404` источник или тайтл, `502/503` источник. |
| `GET T/chapters` | Та же пара ID. | `200 Items<Chapter>` по возрастанию номера. | `400` формат ID, `404` источник или тайтл, `502/503` источник. |
| `GET T/chapters/{chapterId}/pages?language=ru` | Внешний ID главы и обязательный параметр `language`. | `200 ChapterPages`. Соседние главы относятся к тому же языку. | `400` формат или пропущенный параметр, `404` источник, тайтл или глава, `422 LANGUAGE_UNAVAILABLE`, `502/503` источник. |
| `POST L` | `CreateLibraryEntry`, `Content-Type: application/json`. | `201 LibraryEntry` и `Location: /api/v1/library/{entryId}`. | `400` поля, `404` источник или тайтл, `409 DUPLICATE_LIBRARY_ENTRY`, `503` БД. |
| `GET L` | Без параметров. | `200 Items<LibraryEntry>` по убыванию `updatedAt`. Пустой список — `{"items":[]}`. | `503` БД. |
| `GET L/{entryId}` | `entryId` в формате UUID. | `200 LibraryEntry`. | `400` формат ID, `404 LIBRARY_ENTRY_NOT_FOUND`, `503` БД. |
| `PATCH L/{entryId}` | `UpdateLibraryEntry`, `Content-Type: application/json`. Передайте хотя бы одно поле. | `200 LibraryEntry` после применения изменений. | `400` поля, `404` запись, `422 INVALID_PROGRESS`, `502/503` источник при проверке позиции, `503` БД. |
| `DELETE L/{entryId}` | `entryId` в формате UUID. | `204` без тела. | `400` формат ID, `404 LIBRARY_ENTRY_NOT_FOUND`, `503` БД. |

Списки источников и расширений отсортированы по `sourceId`. Они используют локальные метаданные адаптеров и состояние из PostgreSQL. После первого применения V3 Pepper&Carrot подключён. Последующие запуски сохраняют текущее состояние. Прямые ссылки на карточки, главы и страницы работают и после отключения источника из каталога. Тайтлы, заметки и прогресс сохраняются в библиотеке.

### Секции библиотеки

Названия секций хранятся в `library_sections`, состав пользовательских подборок — в `library_section_entries`. Миграция `V9` добавляет `system_key` и четыре встроенные секции. Ключи `planned`, `reading`, `completed` связывают секции со статусами записей, `downloads` открывает загрузки. Переименование сохраняет этот ключ. «Все тайтлы» является общей выборкой. Существующие записи, заметки, статусы и прогресс сохраняются.

| Метод | Действие |
| --- | --- |
| `GET /api/v1/library/sections` | Все секции `{items: [{id, name, entryIds, systemKey}]}`. У пользовательских секций `systemKey=null`. |
| `POST /api/v1/library/sections` | Создать секцию по `{name}`. |
| `GET /api/v1/library/sections/{id}` | Получить одну секцию. |
| `PATCH /api/v1/library/sections/{id}` | Переименовать секцию по `{name}`. |
| `DELETE /api/v1/library/sections/{id}` | Удалить секцию и её связи. Записи библиотеки сохраняются. |
| `POST /api/v1/library/sections/{id}/entries` | Добавить записи по `{entryIds}`. Повторы одного ID объединяются. |
| `DELETE /api/v1/library/sections/{id}/entries/{entryId}` | Убрать запись из секции и вернуть обновлённую секцию. |

Смена статуса сохраняет пользовательские связи. Удаление записи из библиотеки убирает её из всех секций. Повторяющееся название секции возвращает `409 LIBRARY_SECTION_NAME_EXISTS`. Секция или запись с неизвестным ID возвращает `404` там, где операция требует её наличия.

Все сохранённые секции можно переименовывать. Название «Все тайтлы» зарезервировано. У встроенных секций состав вычисляется из статуса или загрузок. Удаление встроенной секции и изменение её состава через методы членства возвращают `422 SYSTEM_SECTION_ACTION_UNSUPPORTED`.

### MangaDex

В чистой базе подключите MangaDex через «Обзор → Расширения» или командой `Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/sources/mangadex' -Method Put`. Подключение хранится в существующей таблице `connected_sources`. Затем выберите MangaDex в фильтре каталога и найдите тайтл по названию.

Адаптер использует официальный API. Каталог возвращает до 24 результатов на запрос и ищет тайтлы с рейтингом `safe` или `suggestive` и доступными английскими или русскими переводами. Читалка показывает главы, изображения которых обслуживает MangaDex. Дробные номера глав сохраняются, у выпусков без номера поле `number` равно `null`. При отсутствии опубликованной лицензии поле `license` равно `null`. Читалка показывает MangaDex, группу перевода при её наличии и ссылку на оригинал.

`coverUrl` ведёт на `/api/v1/sources/mangadex/titles/{titleId}/covers/{fileName}`. Spring Boot загружает миниатюру с фиксированного хоста MangaDex и возвращает изображение с кешированием на один час. В старых записях библиотеки может храниться абсолютный URL миниатюры. Angular переводит его на тот же маршрут обложки.

`chapterCount` считает уникальные главы с доступными в адаптере русскими или английскими выпусками. Для MangaDex переводы и дубликаты одного тома и номера считаются один раз, выпуски без номера отдельно. Счётчик использует тот же фильтр, что и читалка, и обновляется вместе с кешем списка глав. Ноль означает отсутствие глав по этим условиям.

`imageUrl` для MangaDex ведёт на `/api/v1/sources/mangadex/titles/{titleId}/chapters/{chapterId}/images/{index}?language=en`. Этот маршрут загружает изображение через MangaDex@Home, сообщает сети результат загрузки и один раз обновляет адрес при сбое. Для исключения выпусков по запросу группы перевода задайте `$env:MANGADEX_EXCLUDED_GROUP_IDS = 'uuid1,uuid2'`, подставив UUID групп, и перезапустите API. [Правила MangaDex](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/raw/main/index.md) · [Загрузка страниц](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/raw/main/04-chapter/retrieving-chapter.md)

### MangaKakalot и MangaLib

Модули включаются через «Обзор → Расширения». MangaLib использует `api2.mangalib.me` для поиска, глав и манифестов. В браузере проверено чтение первой главы «Человека-бензопилы». MangaKakalot отдаёт каталог, карточки, главы и адреса изображений, но при проверке его CDN вернул `403`. [Реализация и результаты](docs/mangakakalot-and-mangalib.md)

Исходный BATO.TO закрылся, а `bbato.com` перенаправляет на другой домен. Для MangaNel подтверждено чтение на сайте. Оба источника рассматривались на этапе исследования. [Отчёт CODA о BATO.TO](https://coda-cj.jp/en/news/830/) · [MangaNel](https://manganel.me/)

### Теги и сортировка каталога

- `GET /api/v1/sources` объявляет `supportsTags` и `sorts`. Для подключённого MangaDex доступен `GET /api/v1/sources/mangadex/tags`. Карточка тайтла возвращает `tags` с полями `id`, `group` и `names`.
- `GET /api/v1/catalog` принимает поиск `q`, повторяемый `tagId` (до 10) и `sort`. Для фильтра или сортировки нужен `sourceId`. MangaDex применяет все выбранные теги и сортирует до ограничения выдачи. Поддерживаются `relevance`, `year_asc`, `year_desc` и `latest_chapter_desc`. При `UNKNOWN_TAG` сервер возвращает `invalidTagIds`.
- Каталог и карточка обновляют теги при получении тайтла из MangaDex. Для пакетной синхронизации ранее сохранённых тайтлов задайте `$env:KADR_TAGS_BACKFILL = 'true'` перед запуском Spring Boot.

### Офлайн-загрузки Pepper&Carrot

База хранит состояние задачи, метаданные страниц и локальный манифест. Изображения по умолчанию сохраняются в `backend/data/downloads/`. Для другого пути задайте `KADR_OFFLINE_DIR` перед запуском Spring Boot.

| Метод | Назначение |
| --- | --- |
| `GET /api/v1/downloads` | Список `{supported, items}`. Для списка одного тайтла передайте оба параметра `sourceId` и `externalTitleId`. |
| `POST /api/v1/downloads` | Начать загрузку по `sourceId`, `externalTitleId`, `chapterId` и `language`. Тот же ключ возвращает тот же ID. |
| `GET /api/v1/downloads/{id}` | Состояние, прогресс и причина ошибки. |
| `POST /api/v1/downloads/{id}/retry` | Поставить неудавшуюся загрузку в очередь повторно. |
| `DELETE /api/v1/downloads/{id}` | Удалить локальные файлы и запись о загрузке. Очистка начинается со статуса `DELETING`. |
| `GET /api/v1/downloads/{id}/manifest` | Локальные сведения о тайтле, глава со страницами и список готовых переводов. |
| `GET /api/v1/downloads/{id}/pages/{index}/image` | Локальное изображение готовой главы. |

Для офлайн-чтения нужны Spring Boot, PostgreSQL и сохранённые файлы. Проверка позиции в библиотеке использует число страниц готовой копии, если она найдена для главы и языка.

### Типы для Angular

```typescript
type Items<T> = { items: T[] };
type License = { code: string; url: string };
type Source = { sourceId: string; sourceName: string; websiteUrl: string | null };
type Extension = Source & { enabled: boolean };

interface TitleSummary {
  sourceId: string;
  externalTitleId: string;
  sourceName: string;
  title: string;
  author: string | null;
  coverUrl: string | null;
  titleRu: string | null;
  titleEn: string | null;
  publicationStatus: 'ongoing' | 'completed' | 'hiatus' | 'cancelled' | null;
  releaseYear: number | null;
}

interface TitleDetail extends TitleSummary {
  description: string | null;
  sourceUrl: string;
  license: License | null;
}

interface Chapter {
  id: string;
  number: number | null;
  title: string;
  languages: string[];
  pageCount: number;
}

interface ChapterPages {
  chapterId: string;
  title: string;
  language: string;
  pageCount: number;
  previousChapterId: string | null;
  nextChapterId: string | null;
  attribution: {
    author: string | null;
    creditsText: string;
    sourceUrl: string;
    license: License | null;
  };
  pages: { index: number; imageUrl: string }[];
}

type ReadingStatus = 'PLANNED' | 'READING' | 'COMPLETED';

interface Progress {
  chapterId: string;
  language: string;
  pageIndex: number;
}

interface LibraryEntry {
  id: string;
  title: TitleSummary;
  status: ReadingStatus;
  note: string | null;
  progress: Progress | null;
  createdAt: string;
  updatedAt: string;
}

interface CreateLibraryEntry {
  sourceId: string;
  externalTitleId: string;
  status: ReadingStatus;
  note?: string | null;
}

interface UpdateLibraryEntry {
  status?: ReadingStatus;
  note?: string | null;
  progress?: Progress | null;
}

interface ApiProblem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: string;
  errors: { field: string; code: string; message: string }[];
  availableLanguages?: string[];
}
```

`createdAt` и `updatedAt` передаются как строки ISO 8601 в UTC. `entryId` — ID записи библиотеки, а ссылка на тайтл собирается из `title.sourceId` и `title.externalTitleId`. Чтобы связать карточку тайтла с записью для сохранения прогресса, Angular может взять `GET /api/v1/library` и найти совпадение по этой паре.

`status` обязателен при создании и принимает три значения из `ReadingStatus`. Заметка допускает до 2000 символов. В `PATCH` пропущенные поля сохраняются. Явный `null` очищает заметку или прогресс. Значение `status: null`, пустой объект и неизвестные поля дают `400`. Если передан объект `progress`, обязательны все три его поля. `pageIndex` начинается с нуля. Сервер проверяет главу, язык и диапазон страниц. Ошибка в одном поле отменяет весь `PATCH`.

### Ошибки

Ошибки API возвращаются как `application/problem+json`. При наличии `errors` свяжите `field` с контролом формы, например `note` или `progress.pageIndex`. Для ошибок без полей массив пуст. Для неподдерживаемого языка `422 LANGUAGE_UNAVAILABLE` дополнительно содержит `availableLanguages`. Сбой соединения с источником или его ответ `429/5xx` отображаются как `503 SOURCE_UNAVAILABLE`. Ошибка формата ответа источника даёт `502 SOURCE_BAD_RESPONSE`. Недоступность БД даёт `503 DATABASE_UNAVAILABLE`.

Пример `400` при создании записи без статуса:

```json
{
  "type": "about:blank",
  "title": "Ошибка проверки запроса",
  "status": 400,
  "detail": "Исправьте указанные поля.",
  "instance": "/api/v1/library",
  "code": "VALIDATION_ERROR",
  "errors": [
    {"field":"status","code":"REQUIRED","message":"Заполните поле."}
  ]
}
```

### Сценарий на живом API

```powershell
$api = 'http://127.0.0.1:8080/api/v1'
$title = "$api/sources/peppercarrot/titles/pepper-and-carrot"
$library = "$api/library"

Invoke-RestMethod "$api/catalog?q=pepper"
Invoke-RestMethod $title
Invoke-RestMethod "$title/chapters"
$chapter = Invoke-RestMethod "$title/chapters/ep01_Potion-of-Flight/pages?language=ru"
$chapter.pages | Format-Table index, imageUrl

$add = @{
    sourceId = 'peppercarrot'
    externalTitleId = 'pepper-and-carrot'
    status = 'PLANNED'
} | ConvertTo-Json
$entry = Invoke-RestMethod -Method Post $library -ContentType 'application/json' -Body $add
$entryUri = "$library/$($entry.id)"
Invoke-RestMethod $library
Invoke-RestMethod $entryUri

$patch = @{
    status = 'READING'
    note = 'Continue reading'
    progress = @{ chapterId = 'ep01_Potion-of-Flight'; language = 'ru'; pageIndex = 2 }
} | ConvertTo-Json
Invoke-RestMethod -Method Patch $entryUri -ContentType 'application/json' -Body $patch

# После проверки сохранения данных при перезапуске
Invoke-RestMethod -Method Delete $entryUri
```

На защите этот же сценарий проходит через Angular. Откройте каталог, выберите тайтл и русский эпизод, прочитайте страницу, добавьте тайтл через форму, обновите вкладку, измените статус и заметку, возобновите чтение с сохранённой позиции и удалите запись.

До удаления запись можно показать напрямую из двух связанных таблиц:

```powershell
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT t.title, l.status, l.note, l.progress_chapter_id, l.progress_language, l.progress_page_index FROM library_entries l JOIN titles t ON t.id = l.title_id;"'
```

## Структура и данные

- `catalog/CatalogController` обслуживает каталог, карточку, главы и страницы. `source/MangaSourceRegistry` выбирает Spring-компоненты `MangaSource` по стабильному `id()`. Второй источник добавляется своим компонентом и реализацией этих методов. Внешние ID разных источников могут совпадать, поскольку ключ тайтла включает `sourceId`. Уникальность идентификаторов самих источников проверяется при запуске.
- `source/peppercarrot/PepperCarrotSource` держит метаданные одной серии. Живой `episodes.json` используется для списка глав и языков. HTML выбранного эпизода даёт фактические URL `low-res` и титры перевода. Список страниц проверяется по индексу и возвращается в порядке от `0` до `pageCount - 1`. Успешно разобранные ответы кэшируются в памяти до пяти минут.
- Первая миграция Flyway создаёт `titles` со снимком карточки и уникальным ключом `(source_id, external_title_id)`. Вторая создаёт `library_entries` с внешним ключом и уникальным `title_id`. Статус и состав прогресса защищены ограничениями SQL. `library/LibraryService` выполняет изменения в транзакциях. При повторном добавлении сохраняется прежняя запись и приходит `409`. Удаление записи сохраняет строку тайтла.
- Миграция V4 добавляет `title_ru`, `title_en`, `publication_status` и `release_year`. Раньше `titles` пополнялась при добавлении в библиотеку. Теперь каталог и карточка записывают полученные метаданные пакетно по ключу источника и внешнего ID, сохраняя существующий UUID и связи с библиотекой. При новом запросе существующие строки получают актуальные поля, а ранее показанные результаты появляются в БД при повторном получении. MangaDex берёт переводы из `title` и `altTitles`. Отсутствующий перевод хранится как `null`, а `title` используется для подстановки.
- Третья миграция создаёт `connected_sources` с первичным ключом `source_id`. `SourceConnectionService` использует эту таблицу для списков и каталога. Подключение и отключение выполняются одним SQL-запросом на операцию.
- При сбое Pepper&Carrot каталог, карточка и операции с локальными статусом и заметкой остаются доступны. Запись нового прогресса проверяет главу, перевод и индекс через адаптер. Сохранённая позиция и возможность её очистить доступны из PostgreSQL. Если список страниц получен, но не грузится само изображение, клиенту следует показать ошибку изображения и ссылку на оригинал из `attribution.sourceUrl`.

## Источник и лицензия

Веб-комикс **Pepper&Carrot** создан David Revoy и распространяется по CC BY 4.0. В читалке показывайте `attribution.author`, `attribution.creditsText`, ссылку на оригинал главы и ссылку на лицензию. [Pepper&Carrot](https://www.peppercarrot.com/en/webcomics/peppercarrot.html) · [API источника](https://www.peppercarrot.com/en/documentation/130_API.html) · [Рекомендации по атрибуции](https://www.peppercarrot.com/en/documentation/120_License_best_practices.html) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

## Результаты проверки

Основной сценарий читалки проверен 16 ноября 2026 года на Windows с JDK 21, Docker Engine 29.1.5 и Docker Compose 5.0.1. Проверки подключения источников приведены в [отчёте](docs/sources-implementation-report.md). Команда `.\mvnw.cmd -B -ntp verify` запускает интеграционные тесты в отдельном PostgreSQL Testcontainers и требует работающего Docker Engine.

- `docker compose config --quiet` прошла. PostgreSQL 17.12 поднялся на `127.0.0.1:5432` и получил статус `healthy`. Обе миграции отмечены как успешные в `flyway_schema_history`. При повторном запуске Flyway подтвердил версию схемы 2.
- `.\mvnw.cmd -B -ntp verify` прошла. Из чистого временного Git-клона создал `.env` по образцу, проверил Compose, собрал и запустил приложение через Maven Wrapper.
- Живой API вернул карточку Pepper&Carrot, 39 глав и 5 изображений первого русского эпизода в порядке `0–4`. Все 5 URL отдали `200 image/jpeg`. Проверены другие главы, включая эпизод 39 с 13 английскими страницами и ответом `422` для недоступного русского перевода.
- Прошли создание, чтение списка и отдельной записи, изменение статуса, заметки и прогресса, сброс полей через `null` и удаление. Два одновременных добавления дали `201` и `409`. Проверены ошибки отдельных полей, неверный JSON и отклонение всего `PATCH` при индексе страницы за пределами главы.
- Статус, русскоязычная заметка и позиция сохранились после перезапуска Spring Boot и остановки и запуска контейнера PostgreSQL с тем же томом. После проверки запись удалили. Снимок тайтла остался в `titles`. В текущей версии при недоступности БД каталог и списки подключений возвращают `503 DATABASE_UNAVAILABLE`.
- После переноса в `backend/` повторно проверены конфигурация Compose, сборка Maven и запуск Spring Boot из новой папки. PostgreSQL использовал существующий том, а API вернул каталог, пустую библиотеку и пять страниц первого русского эпизода.
- Для MangaDex проверены подключение, поиск `I Met the Male Lead in Prison`, карточка и первая английская глава из 17 страниц. Первый JPEG загружен через Angular dev proxy. В Edge пройден путь «Источники → каталог MangaDex → поиск → тайтл → глава» и проверена подпись группы Princess Alliance.
- После V4 проверено сохранение 24 результатов каталога MangaDex в PostgreSQL. У 23 есть русское название, у 24 есть английское. Повторный запрос сохраняет тот же набор строк. Для Frieren API вернул оба названия, год 2020, статус `hiatus` и пять глав, доступных через адаптер. Пять тестов Maven прошли.

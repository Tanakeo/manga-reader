# Этап 1. План бэкенда веб-читалки

Это первоначальный план. Фактическая структура, команды и REST-контракт описаны в [README.md](README.md). Бэкенд теперь находится в `backend/`, поэтому команды из примеров выполняются из этой папки.

Для курсовой предлагаю один Spring Boot API с двумя независимыми сценариями. Каталог, главы и страницы приходят через адаптер внешнего источника. Личная библиотека хранится в PostgreSQL, поэтому статус, заметку и сохранённый прогресс можно посмотреть даже при сбое Pepper&Carrot.

Рабочая папка осталась пустой. На этом этапе я изучил исходники Suwayomi и проверил публичные данные и изображения Pepper&Carrot.

## 1. Что взять из Suwayomi

| Что изучил | Что переносим в курсовую |
|---|---|
| README сервера и WebUI описывают сервер с подключаемыми источниками и отдельный веб-клиент с каталогом, библиотекой и читалкой. [README сервера](https://github.com/Suwayomi/Suwayomi-Server#what-is-suwayomi) · [README WebUI](https://github.com/Suwayomi/Suwayomi-WebUI/blob/c10605b9ba669ddfa22f1d7b26d98a64ac8e7ba9/README.md#features) | Spring Boot отвечает за доступ к источникам и сохранение данных. Angular работает через один REST API. |
| `Source` и `CatalogueSource` задают операции каталога, метаданных, глав и страниц. `GetSource` находит реализацию по `sourceId` и при необходимости загружает расширение. Документация описывает настройку репозиториев расширений. [Source.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt) · [CatalogueSource.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt) · [GetSource.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/suwayomi/tachidesk/manga/impl/util/source/GetSource.kt#L31-L82) · [Getting Extensions](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/docs/Getting-Extensions.md) | Каждый источник реализует `MangaSource` и регистрируется как Spring-компонент. Выбор по `sourceId` происходит на сервере. |
| `SourceMutation.fetchSourceManga` вызывает каталог выбранного источника. `Manga.fetchManga` берёт источник из записи тайтла. `ChapterMutation.fetchChapterPages` возвращает упорядоченные серверные URL страниц. [SourceMutation.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/suwayomi/tachidesk/graphql/mutations/SourceMutation.kt#L291-L350) · [Manga.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/suwayomi/tachidesk/manga/impl/Manga.kt#L145-L191) · [ChapterMutation.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/suwayomi/tachidesk/graphql/mutations/ChapterMutation.kt#L459-L532) | Сохраняем цепочку «каталог → тайтл → глава → страницы». Для Pepper&Carrot отдаём Angular проверенный список исходных URL, которые браузер открывает через `<img>`. |
| `MangaMutation` хранит признак `inLibrary` в пользовательских данных, а `ChapterMutation` обновляет `lastPageRead`. WebUI использует отдельные экраны для каталога источника, тайтла, чтения и библиотеки. [MangaMutation.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/suwayomi/tachidesk/graphql/mutations/MangaMutation.kt#L74-L115) · [ChapterMutation.kt](https://github.com/Suwayomi/Suwayomi-Server/blob/d5215280158b9b78d2e3ca46312e39dccde6b97b/server/src/main/kotlin/suwayomi/tachidesk/graphql/mutations/ChapterMutation.kt#L47-L125) · [SourceMangas.tsx](https://github.com/Suwayomi/Suwayomi-WebUI/blob/c10605b9ba669ddfa22f1d7b26d98a64ac8e7ba9/src/features/source/browse/screens/SourceMangas.tsx) · [Manga.tsx](https://github.com/Suwayomi/Suwayomi-WebUI/blob/c10605b9ba669ddfa22f1d7b26d98a64ac8e7ba9/src/features/manga/screens/Manga.tsx) · [Reader.tsx](https://github.com/Suwayomi/Suwayomi-WebUI/blob/c10605b9ba669ddfa22f1d7b26d98a64ac8e7ba9/src/features/reader/screens/Reader.tsx) · [Library.tsx](https://github.com/Suwayomi/Suwayomi-WebUI/blob/c10605b9ba669ddfa22f1d7b26d98a64ac8e7ba9/src/features/library/screens/Library.tsx) | Отделяем тайтл от записи библиотеки. В первой версии у записи один указатель на последнюю сохранённую главу, язык и страницу. |

## 2. Pepper&Carrot как первый источник

**Один веб-комикс — один тайтл.** В каталоге будет карточка `Pepper&Carrot` с парой идентификаторов `peppercarrot` / `pepper-and-carrot`. Адаптер задаёт название, автора David Revoy, краткое описание, ссылку на официальный каталог, лицензию и обложку первого эпизода как обложку тайтла. Индекс `episodes.json` даёт список глав, идентификаторы папок, число страниц и доступные переводы. Отображаемое название главы в списке можно получить из части `name` после `_`. В читалке можно взять локализованный заголовок с официальной страницы эпизода. [Официальный каталог](https://www.peppercarrot.com/en/webcomics/peppercarrot.html) · [О проекте](https://www.peppercarrot.com/en/about/index.html) · [Индекс эпизодов](https://www.peppercarrot.com/0_sources/episodes.json)

- **Главы и язык.** `name`, например `ep01_Potion-of-Flight`, служит внешним ID главы. Номер извлекаем из префикса `ep01` и сортируем главы по нему. Для первой версии предлагаю `ru` и `en`. Для каждой главы отдаём пересечение этих двух кодов с её `translated_languages`. На момент проверки индекс содержал 39 эпизодов, из них 39 на английском и 35 на русском. Для `ep39_The-Tavern` русский вариант отсутствовал. Если запрошенный язык недоступен, API возвращает ошибку и список допустимых языков. [Документация API](https://www.peppercarrot.com/en/documentation/130_API.html#json-episode-index) · [Живой индекс](https://www.peppercarrot.com/0_sources/episodes.json)
- **Страницы и обложка.** Путь к изображению строится по схеме `/0_sources/{name}/low-res/{lang}_Pepper-and-Carrot_by-David-Revoy_E{episode}P{page}.{ext}`. Номера до 10 дополняются нулём. Страницы идут от `0` до `total_pages` включительно, поэтому `pageCount = total_pages + 1`. На проверенной странице первый файл `P00` используется как заголовок. Отдельный файл обложки имеет имя без `P{page}`. Для первого русского эпизода все пять файлов `P00`–`P04` вернули `200 image/jpeg`, а `P05` вернул `404`. Для эпизодов 24 и 39 сверил по официальному HTML последовательности `P00`–`P08` и `P00`–`P12`. [Формат URL](https://www.peppercarrot.com/en/documentation/130_API.html#downloadable-files) · [Русский эпизод 1](https://www.peppercarrot.com/ru/webcomic/ep01_Potion-of-Flight.html) · [Эпизод 24](https://www.peppercarrot.com/en/webcomic/ep24_The-Unity-Tree.html) · [Эпизод 39](https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html)
- **Атрибуция.** При открытии главы адаптер читает одну официальную HTML-страницу выбранного языка. Из `.webcomic-page img` получает фактические URL с расширениями `.jpg` или `.gif`, сверяет номера и порядок с индексом, из `.translacredit` извлекает подпись участников как обычный текст. Читалка показывает название, автора, подпись участников, ссылку на оригинал главы и `CC BY 4.0`. Те же сведения об авторе, источнике и лицензии указываем в README. Изображения показываем в исходном виде. [Пример русских титров](https://www.peppercarrot.com/ru/webcomic/ep01_Potion-of-Flight.html) · [Рекомендации автора](https://www.peppercarrot.com/en/documentation/120_License_best_practices.html) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

Для следующего источника сначала выбираем опубликованный JSON API, если он даёт устойчивые идентификаторы, порядок глав и страниц, ссылки на изображения и нужные сведения об авторстве. jsoup подходит, когда эти данные доступны в серверном HTML и сайт разрешает автоматический сбор и показ. Селекторы и разбор URL остаются внутри адаптера. У Pepper&Carrot JSON даёт структуру глав, а HTML дополняет её сведениями конкретного перевода.

## 3. Архитектура и подключаемые источники

**Стек на 16 ноября 2026 года.** JDK 21 LTS, Spring Boot 4.1.2, Maven Wrapper и PostgreSQL 17.12 из официального образа `postgres:17.12-bookworm`. Boot 4.1.2 совместим с Java 21. PostgreSQL 17 поддерживается до ноября 2029 года, а точный тег фиксирует версию для чистого запуска. В Maven подключаем Spring MVC, Validation, Spring Data JPA, PostgreSQL JDBC, Flyway и модуль `flyway-database-postgresql`. Версии управляемых зависимостей задаёт Spring Boot. [Java LTS](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) · [Требования Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html) · [Flyway в Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html) · [Версии PostgreSQL](https://www.postgresql.org/support/versioning/) · [Официальный образ](https://hub.docker.com/_/postgres)

```text
compose.yaml
.env.example
.gitignore
README.md
docs/api.md
.mvn/wrapper/
mvnw, mvnw.cmd, pom.xml
src/main/java/.../
  catalog/           CatalogController, CatalogService, DTO
  source/            MangaSource, MangaSourceRegistry, модели источников
    peppercarrot/    PepperCarrotSource, PepperCarrotClient, DTO индекса
  library/           LibraryController, LibraryService, сущности и репозитории
  error/             ApiExceptionHandler
src/main/resources/
  application.yaml
  db/migration/
frontend/            Angular на этапе 3
```

```java
public interface MangaSource {
    String id();
    String displayName();

    List<SourceTitleSummary> searchTitles(String query);
    SourceTitle getTitle(String externalTitleId);
    List<SourceChapter> listChapters(String externalTitleId);
    ChapterPages getPages(String externalTitleId, String chapterId, String language);
}
```

`PepperCarrotSource` помечен `@Component`. Spring передаёт `List<MangaSource>` в конструктор `MangaSourceRegistry`. Реестр строит неизменяемую карту по `id()` и останавливает запуск при совпадении двух идентификаторов. `CatalogService` вызывает нужную реализацию по `sourceId`. При добавлении второго источника достаточно нового компонента с собственным `id`, адаптера запросов и проверок его формата.

Тайтл адресуется парой `(sourceId, externalTitleId)` во всех слоях. Например, `peppercarrot / pepper-and-carrot` и `second-source / pepper-and-carrot` — разные записи. `chapterId` имеет смысл в пределах этой пары. Адаптер проверяет ID по индексу и строит URL от заданного адреса источника. HTTP-клиент использует таймауты, а успешно разобранный индекс и страницы эпизодов можно держать в памяти до пяти минут, с ограниченным числом записей.

## 4. PostgreSQL и поведение при сбое

| Миграция | Поля и ограничения |
|---|---|
| `V1__create_titles.sql` | `titles.id UUID PRIMARY KEY`, обязательные `source_id`, `external_title_id`, `source_name`, `title`, `source_url`. `author`, `description`, `cover_url`, `license_code`, `license_url` допускают `NULL` для будущих источников. `UNIQUE (source_id, external_title_id)` защищает от двух локальных копий одного тайтла. |
| `V2__create_library_entries.sql` | `library_entries.id UUID PRIMARY KEY`, `title_id UUID NOT NULL UNIQUE REFERENCES titles(id) ON DELETE RESTRICT`, `status NOT NULL` с `CHECK` на `PLANNED`, `READING`, `COMPLETED`, необязательная `note` до 2000 символов, `created_at` и `updated_at` типа `timestamptz`. `progress_chapter_id`, `progress_language`, `progress_page_index` либо все `NULL`, либо все заполнены с `progress_page_index >= 0`. |

Flyway применяет миграции при запуске. Hibernate проверяет соответствие схемы через `ddl-auto: validate`. При добавлении сервис получает карточку тайтла, затем в одной транзакции получает или создаёт локальную строку `titles` и вставляет `library_entries`. Ограничение `UNIQUE (title_id)` защищает и при двух одновременных запросах. Повторное добавление возвращает `409`, сохраняя существующие данные. Удаление убирает запись библиотеки. Строка тайтла остаётся пригодной для повторного добавления.

PostgreSQL хранит снимок карточки и пользовательские поля. Главы, список URL и атрибуция конкретного перевода приходят из источника. При его сбое список и карточки записей библиотеки, статус, заметка, удаление и сброс прогресса доступны через БД. Каталог и карточка Pepper&Carrot также доступны благодаря метаданным адаптера. Запись нового прогресса требует доступного списка страниц для проверки главы, языка и индекса. Бинарные изображения загружает браузер. Если сам файл недоступен после успешного ответа API, читалка показывает ошибку загрузки изображения и ссылку на оригинал.

## 5. REST-контракт для Angular

Все пути начинаются с `/api/v1`. В примерах `T` обозначает путь `/api/v1/sources/peppercarrot/titles/pepper-and-carrot`, а `E` — строковый UUID записи библиотеки. У запросов `GET` и `DELETE` тела нет.

| Запрос | Параметры и результат | Ошибки |
|---|---|---|
| `GET /api/v1/catalog?sourceId=peppercarrot&q=pepper` | Оба параметра необязательны. Без `sourceId` запрашиваются все зарегистрированные адаптеры. `q` ищет по названию без учёта регистра, максимум 120 символов. `200` с `{"items":[TitleSummary]}`. При отсутствии совпадений `{"items":[]}`. | `400` некорректный параметр, `404` неизвестный источник, `502/503` сбой адаптера. |
| `GET T` | Источник и внешний ID берутся из пути. `200` с карточкой `TitleDetail`. | `400` некорректный ID, `404` неизвестный источник или тайтл, `502/503` сбой адаптера. |
| `GET T/chapters` | `200` с `{"items":[Chapter]}`. Список по возрастанию номера, у каждой главы свой `languages`. | `400` некорректный ID, `404` неизвестный источник или тайтл, `502/503` сбой источника. |
| `GET T/chapters/ep01_Potion-of-Flight/pages?language=ru` | `chapterId` из пути, `language` обязателен. `200` с `ChapterPages`, включая соседние главы на выбранном языке. | `400` отсутствующий или некорректный параметр, `404` неизвестный источник, тайтл или глава, `422` язык недоступен, `502/503` сбой источника. |
| `POST /api/v1/library` | Тело с `sourceId`, `externalTitleId`, `status` и необязательной `note`. `201` с `LibraryEntry` и `Location: /api/v1/library/{entryId}`. | `400` ошибки полей, `404` неизвестный источник или тайтл, `409` тайтл уже в библиотеке, `502/503` если адаптеру для карточки нужен недоступный источник. |
| `GET /api/v1/library` | `200` с `{"items":[LibraryEntry]}` по убыванию времени изменения. Пустая библиотека возвращает `{"items":[]}`. | `503` БД недоступна. |
| `GET /api/v1/library/{entryId}` | Параметр пути `entryId` типа UUID. `200` с `LibraryEntry`. | `400` неверный UUID, `404` запись отсутствует, `503` БД недоступна. |
| `PATCH /api/v1/library/{entryId}` | Тело с любым непустым набором полей `status`, `note`, `progress`. `200` с обновлённой `LibraryEntry`. | `400` ошибки тела и формата, `404` запись отсутствует, `422` неверная глава, язык или индекс в прогрессе, `502/503` при сбое проверки через источник, `503` при недоступной БД. |
| `DELETE /api/v1/library/{entryId}` | Параметр пути `entryId` типа UUID. `204` с пустым телом. | `400` неверный UUID, `404` запись отсутствует, `503` БД недоступна. |

### Примеры ответов каталога и глав

Элемент `items` в ответе каталога имеет форму `TitleSummary`. Карточка `GET T` возвращает те же поля и дополнительно `description`, `sourceUrl` и `license`.

```json
{
  "sourceId": "peppercarrot",
  "externalTitleId": "pepper-and-carrot",
  "sourceName": "Pepper&Carrot",
  "title": "Pepper&Carrot",
  "author": "David Revoy",
  "coverUrl": "https://www.peppercarrot.com/0_sources/ep01_Potion-of-Flight/low-res/en_Pepper-and-Carrot_by-David-Revoy_E01.jpg",
  "description": "Веб-комикс о молодой ведьме Пеппер и её коте Морковке.",
  "sourceUrl": "https://www.peppercarrot.com/en/webcomics/peppercarrot.html",
  "license": {"code":"CC BY 4.0","url":"https://creativecommons.org/licenses/by/4.0/"}
}
```

Сокращённый ответ `GET T/chapters`:

```json
{
  "items": [
    {"id":"ep01_Potion-of-Flight","number":1,"title":"Potion of Flight","languages":["en","ru"],"pageCount":5},
    {"id":"ep02_Rainbow-potions","number":2,"title":"Rainbow potions","languages":["en","ru"],"pageCount":7}
  ]
}
```

Сокращённый ответ `GET T/chapters/ep01_Potion-of-Flight/pages?language=ru`. В рабочем ответе `pages` содержит все пять объектов с `index` от `0` до `4`.

```json
{
  "chapterId": "ep01_Potion-of-Flight",
  "title": "Зелье полета",
  "language": "ru",
  "pageCount": 5,
  "previousChapterId": null,
  "nextChapterId": "ep02_Rainbow-potions",
  "attribution": {
    "author": "David Revoy",
    "creditsText": "Перевод: Andrew \"Akari\" Alexeyew. Вклад: GunChleoc, Hồ Nhựt Châu, Denis Prisukhin.",
    "sourceUrl": "https://www.peppercarrot.com/ru/webcomic/ep01_Potion-of-Flight.html",
    "license": {"code":"CC BY 4.0","url":"https://creativecommons.org/licenses/by/4.0/"}
  },
  "pages": [
    {"index":0,"imageUrl":"https://www.peppercarrot.com/0_sources/ep01_Potion-of-Flight/low-res/ru_Pepper-and-Carrot_by-David-Revoy_E01P00.jpg"}
  ]
}
```

### Примеры CRUD библиотеки и сохранения прогресса

Тело `POST /api/v1/library`:

```json
{"sourceId":"peppercarrot","externalTitleId":"pepper-and-carrot","status":"PLANNED"}
```

Ответ `201` и последующий ответ `GET /api/v1/library/{entryId}` имеют одну форму. `GET /api/v1/library` оборачивает такие записи в `items`.

```json
{
  "id": "6eaa5352-eac5-4d06-a4dc-ada2d91ed2fb",
  "title": {
    "sourceId": "peppercarrot",
    "externalTitleId": "pepper-and-carrot",
    "sourceName": "Pepper&Carrot",
    "title": "Pepper&Carrot",
    "author": "David Revoy",
    "coverUrl": "https://www.peppercarrot.com/0_sources/ep01_Potion-of-Flight/low-res/en_Pepper-and-Carrot_by-David-Revoy_E01.jpg"
  },
  "status": "PLANNED",
  "note": null,
  "progress": null,
  "createdAt": "2026-11-16T10:00:00Z",
  "updatedAt": "2026-11-16T10:00:00Z"
}
```

Тело `PATCH /api/v1/library/6eaa5352-eac5-4d06-a4dc-ada2d91ed2fb`:

```json
{
  "status": "READING",
  "note": "Продолжить с первой главы",
  "progress": {"chapterId":"ep01_Potion-of-Flight","language":"ru","pageIndex":2}
}
```

Ответ `200` возвращает запись целиком с этими значениями и новым `updatedAt`. Пропущенное поле сохраняет прежнее значение. `{"note":null}` очищает заметку, `{"progress":null}` сбрасывает прогресс, а `status:null` вызывает ошибку. Если передан объект `progress`, все три поля обязательны. Сервер проверяет принадлежность главы тайтлу, наличие перевода и диапазон `0 <= pageIndex < pageCount`. Операция применяет все поля вместе после проверки. Для последнего шага `DELETE /api/v1/library/6eaa5352-eac5-4d06-a4dc-ada2d91ed2fb` возвращает `204`, последующий `GET` записи возвращает `404`.

### Единый формат ошибок

`@RestControllerAdvice` возвращает `application/problem+json`. Angular использует `code` для выбора состояния экрана, а `errors[].field` для показа сообщения у поля формы.

```json
{
  "type": "about:blank",
  "title": "Ошибка проверки запроса",
  "status": 400,
  "detail": "Исправьте указанные поля.",
  "instance": "/api/v1/library",
  "code": "VALIDATION_ERROR",
  "errors": [
    {"field":"status","code":"REQUIRED","message":"Выберите статус чтения."}
  ]
}
```

Для ошибок без полей `errors` содержит `[]`. У ошибки языка код `LANGUAGE_UNAVAILABLE` и дополнительное поле `availableLanguages`, например `["en"]`. Коды `DUPLICATE_LIBRARY_ENTRY`, `SOURCE_BAD_RESPONSE`, `SOURCE_UNAVAILABLE` и `DATABASE_UNAVAILABLE` позволяют клиенту различать дубликат, неожиданный ответ источника и временную недоступность. Неразбираемый JSON возвращает `400`, неподдерживаемый формат тела — `415`.

## 6. Порядок реализации и критерии готовности

| Шаг | Основные файлы и классы | Проверка и критерий готовности |
|---|---|---|
| 1. Каркас и окружение | `pom.xml`, Maven Wrapper, `application.yaml`, `compose.yaml`, `.env.example`, `.gitignore` | `docker version`, `docker compose version`, `docker compose config --quiet`. `docker compose up -d postgres` запускает базу, `docker compose ps postgres` показывает `healthy`. Именованный том смонтирован в `/var/lib/postgresql/data`, порт опубликован только на `127.0.0.1`. |
| 2. Миграции и модель | Два SQL-файла Flyway, `Title`, `LibraryEntry`, репозитории | На пустой БД применяются обе миграции, JPA проходит проверку схемы. Повторный старт сохраняет данные. Проверяем ограничения составного ключа, внешнего ключа и уникальности записи библиотеки. |
| 3. Адаптер и каталог | `MangaSourceRegistry`, `PepperCarrotClient`, `PepperCarrotSource`, `CatalogController` | Получаем карточку, главы и реальные страницы `ep01` на `ru`. Сверяем счётчик и порядок, открываем изображения, проверяем атрибуцию. Для `ep39` запрос `ru` даёт `422`, а `en` возвращает страницы. |
| 4. Библиотека и ошибки | `LibraryController`, `LibraryService`, `ApiExceptionHandler` | Живыми HTTP-запросами проверяем создание, список и отдельную запись, изменение статуса, заметки и прогресса, валидацию и дубликат. После перезапуска Spring Boot и PostgreSQL с тем же томом запись сохраняется. После удаления запись исчезает из списка. |
| 5. Чистый запуск и передача фронтенду | `README.md`, `docs/api.md` | Проверяем запуск из чистого клона в PowerShell. Фиксируем фактические URL, DTO, ответы ошибок и результаты проверок. Для занятого порта БД README объясняет, как изменить `POSTGRES_PORT` в `.env` и пересоздать контейнер с тем же томом. |

В `compose.yaml` имя БД, пользователь, пароль и порт берутся из `.env`. Healthcheck использует `pg_isready` с TCP внутри контейнера. В README покажу передачу тех же переменных Spring Boot из PowerShell. Для демонстрационного `.env` с простыми незаключёнными в кавычки значениями команды будут такими:

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d postgres
docker compose ps postgres

Get-Content .env | ForEach-Object {
    if ($_ -match '^(POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_PORT)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run
```

**Сценарий защиты.** Открыть каталог, выбрать Pepper&Carrot, переключиться на русский и прочитать первый эпизод с атрибуцией. Затем добавить тайтл через форму, обновить браузер, изменить статус и заметку, сохранить позицию и снова открыть читалку на сохранённой странице. Показать запись в PostgreSQL, проверить её после перезапуска и удалить через интерфейс.

**Допущения первой версии.** Одна локальная библиотека без учётных записей, API на `127.0.0.1:8080`, Angular подключается через dev proxy. Интерфейс на русском, переводы глав `ru` и `en`. Прогресс хранит одну позицию на тайтл, включая язык выбранной главы. Для одного каталожного тайтла достаточно фильтра по названию без пагинации. Чтение требует доступа к серверу изображений источника.

Жду команды **«Реализуй бэкенд»**.
